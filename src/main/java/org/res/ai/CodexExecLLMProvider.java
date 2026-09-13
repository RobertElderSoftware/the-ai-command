package org.res.ai;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** LLMProvider backed by codex exec; stdout is the response and stderr supplies failure diagnostics. */
public final class CodexExecLLMProvider implements LLMProvider {
    private static final List<String> DEFAULT_COMMAND = List.of(
            "codex",
            "--ask-for-approval", "never",
            "exec",
            "--sandbox", "read-only",
            "--ephemeral",
            "--skip-git-repo-check"
    );

    private final List<String> commandParts;

    public CodexExecLLMProvider() {
        this(DEFAULT_COMMAND);
    }

    public CodexExecLLMProvider(List<String> commandParts) {
        if (commandParts == null || commandParts.isEmpty()) {
            throw new IllegalArgumentException("commandParts must not be null/empty");
        }
        this.commandParts = List.copyOf(commandParts);
    }

    @Override
    public String complete(String prompt) {
        if (prompt == null) {
            throw new IllegalArgumentException("prompt must not be null");
        }
        try {
            ShellProcessRunner runner = new ShellProcessRunner(commandParts, null, null, true);
            OutputStream stdin = runner.getOutputStreamForStdin();
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            stdin.close();
            ShellProcessFinalResult finalResult = runner.getFinalResult();
            ShellProcessPartialResult output = finalResult.getOutput();
            int exit = finalResult.getReturnValue();
            if (exit != 0) {
                String stderr = new String(output.getStderrOutput(), StandardCharsets.UTF_8);
                throw new RuntimeException("codex exec failed with exit code " + exit + ". stderr: " + stderr);
            }
            return new String(output.getStdoutOutput(), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to run codex exec", e);
        }
    }

    @Override
    public void close() {
        // No persistent resources; each complete() call spawns a subprocess.
    }
}
