package org.res.ai;

/** Test backend which returns every prompt unchanged; file logging defaults to disabled. */
public final class LoopbackLLMProvider implements LLMProvider {
    private final boolean loggingEnabled;

    public LoopbackLLMProvider() {
        this(false);
    }

    public LoopbackLLMProvider(boolean loggingEnabled) {
        this.loggingEnabled = loggingEnabled;
    }

    @Override
    public boolean isLoggingEnabled() {
        return loggingEnabled;
    }

    @Override
    public String complete(String prompt) {
        if (prompt == null) throw new IllegalArgumentException("prompt must not be null");
        return prompt;
    }

    @Override
    public void close() {
        // No resources.
    }
}
