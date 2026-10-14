package org.res.ai;

import com.openai.client.OpenAIClient;
import com.openai.core.ObjectMappers;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.services.blocking.ResponseService;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpenAIRecoveryTest {
    private static Response response(String status) throws Exception {
        return ObjectMappers.jsonMapper().readValue(
                "{\"id\":\"resp_original\",\"status\":\"" + status
                + "\",\"output\":[{\"type\":\"message\",\"id\":\"msg_1\","
                + "\"role\":\"assistant\",\"status\":\"completed\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"[]\",\"annotations\":[]}]}]}",
                Response.class);
    }

    @Test void backgroundRecoveryRetrievesSavedIdWithoutCreatingAnotherResponse() throws Exception {
        String previous = System.getProperty("ai.openai.background");
        System.setProperty("ai.openai.background", "true");
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger gets = new AtomicInteger();
        Response queued = response("queued");
        Response completed = response("completed");
        ResponseService service = (ResponseService) Proxy.newProxyInstance(
                ResponseService.class.getClassLoader(), new Class<?>[]{ResponseService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("create")) {
                        creates.incrementAndGet();
                        assertTrue(((ResponseCreateParams) args[0]).background().orElseThrow());
                        return queued;
                    }
                    if (method.getName().equals("retrieve")) {
                        assertEquals("resp_original", args[0]);
                        if (gets.incrementAndGet() == 1)
                            throw new ProviderNetworkException("openai", "endpoint",
                                    ProviderNetworkException.Phase.UNCERTAIN);
                        return completed;
                    }
                    throw new AssertionError(method.getName());
                });
        OpenAIClient client = (OpenAIClient) Proxy.newProxyInstance(
                OpenAIClient.class.getClassLoader(), new Class<?>[]{OpenAIClient.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("responses")) return service;
                    if (method.getName().equals("close")) return null;
                    throw new AssertionError(method.getName());
                });
        try (OpenAILLMProvider provider = new OpenAILLMProvider("test-model")) {
            var field = OpenAILLMProvider.class.getDeclaredField("client");
            field.setAccessible(true);
            field.set(provider, client);
            assertThrows(ProviderNetworkException.class, () -> provider.complete("same prompt"));
            assertTrue(provider.canRecover());
            assertEquals("[]", provider.recover());
            assertFalse(provider.canRecover());
            assertEquals(1, creates.get());
            assertEquals(2, gets.get());
        } finally {
            if (previous == null) System.clearProperty("ai.openai.background");
            else System.setProperty("ai.openai.background", previous);
        }
    }
}
