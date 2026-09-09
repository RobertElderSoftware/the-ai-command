package org.res.ai;

/** Test backend which returns every prompt unchanged. */
public final class LoopbackLLMProvider implements LLMProvider {
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
