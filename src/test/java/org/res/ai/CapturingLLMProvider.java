package org.res.ai;

import java.util.Objects;
import java.util.function.Function;

/** Captures an envelope and returns a controlled response without creating logs. */
public final class CapturingLLMProvider implements LLMProvider {
    private final Function<String, String> response;
    private String prompt;
    private int calls;

    public CapturingLLMProvider(String response) {
        this(prompt -> response);
    }

    public CapturingLLMProvider(Function<String, String> response) {
        this.response = Objects.requireNonNull(response, "response");
    }

    public String prompt() { return prompt; }
    public int calls() { return calls; }
    @Override public boolean isLoggingEnabled() { return false; }

    @Override public String complete(String prompt) {
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        calls++;
        return response.apply(prompt);
    }

    @Override public void close() { }
}
