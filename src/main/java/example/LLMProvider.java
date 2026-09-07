package example;

/**
 * Generic interface for an LLM backend that takes a single prompt string and returns a single output string.
 *
 * Implementations should own any relevant API client state and be responsible for graceful shutdown.
 */
public interface LLMProvider extends AutoCloseable {

    /**
     * Initialize any internal state required to service requests.
     *
     * Implementations should be idempotent.
     */
    default void initialize() {
        // no-op by default
    }

    /**
     * Submit the prompt and return the full model output text.
     */
    String complete(String prompt);

    /**
     * Close resources.
     */
    @Override
    void close();
}
