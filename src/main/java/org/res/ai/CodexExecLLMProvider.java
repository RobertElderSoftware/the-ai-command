package org.res.ai;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Codex JSONL backend. Custom commands must emit the same event protocol. */
public final class CodexExecLLMProvider implements LLMProvider {
    private static final List<String> DEFAULT_COMMAND = List.of(
            "codex", "--ask-for-approval", "never", "exec", "--sandbox", "read-only",
            "--ephemeral", "--skip-git-repo-check", "--json");
    private final List<String> commandParts;
    private final boolean resetLookup;
    private final boolean preflight;
    private java.net.URI endpoint;

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

    @Override
    public void checkNetwork() throws java.io.IOException {
        if (!preflight) return; // Custom process commands retain their existing transport contract.
        if (endpoint == null) endpoint = CodexNetworkEndpoint.resolve();
        NetworkProbe.check("codex", endpoint, null);
    }

    @Override
    public String complete(String prompt) {
        if (prompt == null) throw new IllegalArgumentException("prompt must not be null");
        try (ShellProcessRunner runner = new ShellProcessRunner(commandParts, null, null, true)) {
            try (OutputStream stdin = runner.getOutputStreamForStdin()) {
                stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            ShellProcessFinalResult result = runner.getFinalResult();
            return CodexEvents.response(result.getReturnValue(),
                    new String(result.getOutput().getStdoutOutput(), StandardCharsets.UTF_8),
                    new String(result.getOutput().getStderrOutput(), StandardCharsets.UTF_8));
        } catch (ProviderNetworkException network) {
            if (network.getSuppressed().length != 0 || network.getCause() != null) throw network;
            throw new ProviderNetworkException("codex", endpoint == null
                    ? "configured Codex endpoint" : NetworkProbe.display(endpoint), network.phase());
        } catch (ProviderLimitException limit) {
            if (resetLookup && limit.kind() == ProviderLimitException.Kind.USAGE_LIMIT) {
                String bucket = System.getProperty("ai.codex.limitId", "codex");
                var windows = CodexRateLimits.lookup(List.of("codex", "app-server"), bucket);
                if (!windows.isEmpty()) throw limit.withWindows(windows);
            }
            throw limit;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Failed to run codex exec", exception);
        }
    }

    @Override public void close() { }
}
