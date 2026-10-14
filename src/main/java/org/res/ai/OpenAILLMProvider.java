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
    private java.net.URI endpoint;
    private java.net.Proxy proxy;
    private String responseId;

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
        String base = System.getenv("OPENAI_BASE_URL");
        endpoint = NetworkProbe.endpoint(base == null ? "https://api.openai.com/v1" : base);
        proxy = NetworkProbe.javaProxy(endpoint);
        client = OpenAIOkHttpClient.builder()
                .fromEnv()
                .baseUrl(endpoint.toString())
                .proxy(proxy)
                .maxRetries(0)
                .timeout(com.openai.core.Timeout.builder()
                        .connect(java.time.Duration.ofSeconds(10))
                        .read(java.time.Duration.ZERO).write(java.time.Duration.ZERO)
                        .request(java.time.Duration.ZERO).build())
                .dispatcherExecutorService(httpExecutor)
                .streamHandlerExecutor(streamExecutor)
                .build();
    }

    @Override
    public void checkNetwork() throws java.io.IOException {
        initialize();
        NetworkProbe.check("openai", endpoint, proxy);
    }

    @Override
    public String complete(String prompt) {
        if (prompt == null) throw new IllegalArgumentException("prompt must not be null");
        initialize();
        responseId = null;
        final Response response;
        boolean background = Boolean.getBoolean("ai.openai.background");
        try {
            var params = ResponseCreateParams.builder().model(model).input(prompt);
            if (background) params.background(true);
            response = client.responses().create(params.build());
        } catch (com.openai.errors.OpenAIIoException failure) {
            if (!ProviderNetworkException.transport(failure)) throw failure;
            // The SDK does not prove that no bytes were accepted, even for connect errors
            // after internal route selection. Never guess that a replay is safe.
            throw new ProviderNetworkException("openai", NetworkProbe.display(endpoint),
                    ProviderNetworkException.Phase.UNCERTAIN);
        } catch (com.openai.errors.OpenAIServiceException failure) {
            throw OpenAILimits.normalize(failure);
        }
        if (background) responseId = response.id();
        return awaitResponse(response, background);
    }

    @Override public boolean canRecover() { return responseId != null; }

    @Override public String recover() {
        if (responseId == null) throw new IllegalStateException("No response ID available");
        return awaitResponse(retrieve(), true);
    }

    private Response retrieve() {
        try {
            return client.responses().retrieve(responseId);
        } catch (com.openai.errors.OpenAIIoException failure) {
            if (!ProviderNetworkException.transport(failure)) throw failure;
            throw new ProviderNetworkException("openai", NetworkProbe.display(endpoint),
                    ProviderNetworkException.Phase.UNCERTAIN);
        } catch (com.openai.errors.OpenAIServiceException failure) {
            // A lookup limit is not a rejected prompt: never hand it to the submission limit loop.
            throw new IllegalStateException("Original response lookup failed; no fresh prompt was submitted", failure);
        }
    }

    private String awaitResponse(Response response, boolean background) {
        if (background) {
            while (response.status().filter(status ->
                    status.equals(com.openai.models.responses.ResponseStatus.QUEUED)
                    || status.equals(com.openai.models.responses.ResponseStatus.IN_PROGRESS)).isPresent()) {
                try { Thread.sleep(1000); }
                catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Response polling cancelled", cancelled);
                }
                response = retrieve();
            }
            if (!response.status().filter(com.openai.models.responses.ResponseStatus.COMPLETED::equals).isPresent())
                throw new IllegalStateException("Original background response did not complete; no replay");
        }
        responseId = null;
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
