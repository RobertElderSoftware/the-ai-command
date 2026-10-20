package org.res.ai;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Codex JSONL backend. Custom commands must emit the same event protocol. */
public final class CodexExecLLMProvider implements LLMProvider {
    private static final List<String> DEFAULT_COMMAND = List.of(
            "codex", "--ask-for-approval", "never", "exec", "--sandbox", "read-only",
            "--ephemeral", "--skip-git-repo-check", "--json");
    private final List<String> commandParts;
    private final boolean resetLookup;
    private final boolean preflight;
    private java.net.URI endpoint;
    private ProviderActivity.Listener listener = ProviderActivity.Listener.NONE;

    public CodexExecLLMProvider() {
        this(DEFAULT_COMMAND, Boolean.parseBoolean(System.getProperty("ai.codex.rateLimitLookup", "true")), true);
    }

    public CodexExecLLMProvider(List<String> commandParts) {
        this(commandParts, false, false);
    }

    private CodexExecLLMProvider(List<String> commandParts, boolean resetLookup, boolean preflight) {
        if (commandParts == null || commandParts.isEmpty())
            throw new IllegalArgumentException("commandParts must not be null/empty");
        this.commandParts = List.copyOf(commandParts);
        this.resetLookup = resetLookup;
        this.preflight = preflight;
    }

    @Override public void setActivityListener(ProviderActivity.Listener listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    @Override public void checkNetwork() throws java.io.IOException {
        if (!preflight) return;
        if (endpoint == null) endpoint = CodexNetworkEndpoint.resolve();
        NetworkProbe.check("codex", endpoint, null);
    }

    @Override public String complete(String prompt) {
        if (prompt == null) throw new IllegalArgumentException("prompt must not be null");
        CodexCommandActivity activity = new CodexCommandActivity(listener);
        CodexEvents events = new CodexEvents(activity);
        RuntimeException primary = null;
        try {
            return stream(prompt, events);
        } catch (ProviderNetworkException network) {
            primary = network;
            if (network.getSuppressed().length != 0 || network.getCause() != null) throw network;
            primary = new ProviderNetworkException("codex", endpoint == null
                    ? "configured Codex endpoint" : NetworkProbe.display(endpoint), network.phase());
            throw primary;
        } catch (ProviderLimitException limit) {
            primary = limit;
            if (resetLookup && limit.kind() == ProviderLimitException.Kind.USAGE_LIMIT) {
                String bucket = System.getProperty("ai.codex.limitId", "codex");
                var windows = CodexRateLimits.lookup(List.of("codex", "app-server"), bucket);
                if (!windows.isEmpty()) {
                    primary = limit.withWindows(windows);
                    throw primary;
                }
            }
            throw limit;
        } catch (RuntimeException exception) {
            primary = exception;
            throw exception;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            primary = new RuntimeException("Failed to run codex exec", exception);
            throw primary;
        } finally {
            try {
                activity.finish();
            } catch (RuntimeException reporting) {
                if (primary != null) reporting.addSuppressed(primary);
                throw reporting;
            }
        }
    }

    private String stream(String prompt, CodexEvents events) throws Exception {
        var writer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "codex-stdin-writer");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> input = null;
        try (ShellProcessRunner runner = new ShellProcessRunner(commandParts, null, null, true)) {
            input = writer.submit(() -> {
                try (OutputStream stdin = runner.getOutputStreamForStdin()) {
                    stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                } catch (Exception failure) {
                    throw new RuntimeException("Cannot deliver Codex prompt", failure);
                }
            });
            while (!runner.isFinished()) {
                consume(events, runner.getPartialResult());
                Thread.sleep(50);
            }
            ShellProcessFinalResult result = runner.getFinalResult();
            consume(events, result.getOutput());
            // Classify explicit early provider failures even when stdin was closed early.
            String response = events.result(result.getReturnValue());
            input.get();
            return response;
        } finally {
            if (input != null) input.cancel(true);
            writer.shutdownNow();
        }
    }

    private static void consume(CodexEvents events, ShellProcessPartialResult result) {
        events.stderr(result.getStderrOutput());
        events.stdout(result.getStdoutOutput());
    }

    @Override public void close() { listener = ProviderActivity.Listener.NONE; }
}
