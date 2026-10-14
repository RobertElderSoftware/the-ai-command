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

    /** A bounded pre-submission check. Never applies a deadline to complete(). */
    default void checkNetwork() throws java.io.IOException { }

    /** Submit the prompt and return the full model output text. */
    String complete(String prompt);

    /** True only when the last failed submission has an ID usable for result retrieval. */
    default boolean canRecover() { return false; }

    /**
     * Retrieve/wait for the original result without submitting a new prompt.
     * Return only a complete response. Transport errors use ProviderNetworkException;
     * permanent lookup failures must not be normalized to submission usage limits.
     */
    default String recover() throws java.io.IOException {
        throw new UnsupportedOperationException("Original response retrieval is unavailable");
    }

    /** Close resources. */
    @Override
    void close();
}
