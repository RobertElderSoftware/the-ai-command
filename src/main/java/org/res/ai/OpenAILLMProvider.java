package org.res.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;

import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * OpenAI implementation of {@link LLMProvider}.
 *
 * Owns the OpenAI client and the executors used by the underlying HTTP and stream handlers.
 */
public final class OpenAILLMProvider implements LLMProvider {

    private final ExecutorService httpExecutor;
    private final ExecutorService streamExecutor;
    private final String model;

    private OpenAIClient client;

    public OpenAILLMProvider(ExecutorService httpExecutor, ExecutorService streamExecutor, String model) {
        if (httpExecutor == null) throw new IllegalArgumentException("httpExecutor must not be null");
        if (streamExecutor == null) throw new IllegalArgumentException("streamExecutor must not be null");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model must not be blank");
        this.httpExecutor = httpExecutor;
        this.streamExecutor = streamExecutor;
        this.model = model;
    }

    @Override
    public void initialize() {
        if (client != null) {
            return;
        }

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY is not set. Run: export OPENAI_API_KEY=\"your-api-key\""
            );
        }

        client = OpenAIOkHttpClient.builder()
                .fromEnv()
                .dispatcherExecutorService(httpExecutor)
                .streamHandlerExecutor(streamExecutor)
                .build();
    }

    @Override
    public String complete(String prompt) {
        if (prompt == null) {
            throw new IllegalArgumentException("prompt must not be null");
        }

        initialize();

        ResponseCreateParams request = ResponseCreateParams.builder()
                .model(model)
                .input(prompt)
                .build();

        Response response = client.responses().create(request);

        return response.output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .map(outputText -> outputText.text())
                .collect(Collectors.joining());
    }

    @Override
    public void close() {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } finally {
            client = null;
        }
    }
}
