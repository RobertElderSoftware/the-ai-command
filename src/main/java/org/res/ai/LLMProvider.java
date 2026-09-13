package org.res.ai;

/**
 * Generic interface for an LLM backend that takes a single prompt string and returns a single output string.
 *
 * Implementations should own any relevant API client state and be responsible for graceful shutdown.
 */
public interface LLMProvider extends AutoCloseable {

    /** Initialize internal state. Implementations should be idempotent. */
    default void initialize() {
        // no-op by default
    }

    /**
     * Default policy for application prompt/response file logging.
     * Callers can override this policy in the AICommandApplication constructor.
     */
    default boolean isLoggingEnabled() {
        return true;
    }

    /** Submit the prompt and return the full model output text. */
    String complete(String prompt);

    /** Close resources. */
    @Override
    void close();
}
