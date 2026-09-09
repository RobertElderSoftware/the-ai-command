package org.res.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** OpenAI backend which owns its client and all executors used by that client. */
public final class OpenAILLMProvider implements LLMProvider {
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final ExecutorService httpExecutor;
    private final ExecutorService streamExecutor;
    private final String model;
    private OpenAIClient client;

    public OpenAILLMProvider(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        this.model = model;
        this.httpExecutor = Executors.newCachedThreadPool(namedThreadFactory("openai-http-"));
        this.streamExecutor = Executors.newCachedThreadPool(namedThreadFactory("openai-stream-"));
    }

    @Override
    public synchronized void initialize() {
        if (client != null) return;
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not set");
        }
        client = OpenAIOkHttpClient.builder()
                .fromEnv()
                .dispatcherExecutorService(httpExecutor)
                .streamHandlerExecutor(streamExecutor)
                .build();
    }

    @Override
    public String complete(String prompt) {
        if (prompt == null) throw new IllegalArgumentException("prompt must not be null");
        initialize();
        Response response = client.responses().create(ResponseCreateParams.builder()
                .model(model)
                .input(prompt)
                .build());
        return response.output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .map(outputText -> outputText.text())
                .collect(Collectors.joining());
    }

    @Override
    public synchronized void close() {
        RuntimeException failure = null;
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException exception) {
                failure = exception;
            } finally {
                client = null;
            }
        }
        shutdown(httpExecutor);
        shutdown(streamExecutor);
        if (failure != null) throw failure;
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger number = new AtomicInteger(1);
        return task -> new Thread(task, prefix + number.getAndIncrement());
    }

    private static void shutdown(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
