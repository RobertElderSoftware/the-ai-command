package org.res.ai;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class NetworkRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private static ProviderNetworkException down(ProviderNetworkException.Phase phase) {
        return new ProviderNetworkException("test", "https://example.invalid", phase);
    }

    private static NetworkRetry controller(AtomicLong elapsed, boolean uncertain, boolean terminal) {
        return new NetworkRetry(Clock.fixed(NOW, ZoneOffset.UTC), elapsed::get,
                elapsed::addAndGet, terminal, Duration.ofSeconds(15), uncertain, false);
    }

    private static class Provider implements LLMProvider {
        int checks;
        int calls;
        int failedChecks;
        int failedCalls;
        String lastPrompt;
        @Override public void checkNetwork() {
            if (++checks <= failedChecks) throw down(ProviderNetworkException.Phase.PREFLIGHT);
        }
        @Override public String complete(String prompt) {
            lastPrompt = prompt;
            if (++calls <= failedCalls) throw down(ProviderNetworkException.Phase.UNCERTAIN);
            return "[]";
        }
        @Override public void close() { }
    }

    private static class History implements NetworkRetry.HistorySink {
        final List<String> events = new ArrayList<>();
        final List<JsonObject> records = new ArrayList<>();
        @Override public void record(String event, JsonObject data) throws IOException {
            events.add(event);
            records.add(data.deepCopy());
        }
    }

    @Test void unpluggedStartupWaitsWithoutSubmittingAndRecordsOneEpisode() throws Exception {
        var elapsed = new AtomicLong();
        var provider = new Provider();
        provider.failedChecks = 3;
        var history = new History();
        var output = new ByteArrayOutputStream();
        String prompt = "retained prompt";
        assertEquals("[]", controller(elapsed, false, false)
                .completeWithHistory(provider, prompt, output, history, true, true));
        assertSame(prompt, provider.lastPrompt);
        assertEquals(4, provider.checks);
        assertEquals(1, provider.calls);
        assertEquals(Duration.ofSeconds(45).toNanos(), elapsed.get());
        assertEquals(List.of("network_wait_started", "network_wait_finished"), history.events);
        var summary = history.records.get(1);
        assertEquals(3, summary.get("failedChecks").getAsInt());
        assertEquals(0, summary.get("promptResubmissions").getAsInt());
        assertEquals(45000, summary.get("elapsedMillis").getAsLong());
        assertTrue(summary.get("contextCommitted").getAsBoolean());
        assertEquals(2, output.toString().lines().count());
        assertFalse(output.toString().contains("\u001b"));
    }

    @Test void uncertainSubmissionStopsUnlessExplicitlyAuthorized() throws Exception {
        for (boolean authorized : List.of(false, true)) {
            var provider = new Provider();
            provider.failedCalls = 1;
            var elapsed = new AtomicLong();
            var history = new History();
            var output = new ByteArrayOutputStream();
            String prompt = "same object";
            if (authorized) {
                assertEquals("[]", controller(elapsed, true, false)
                        .completeWithHistory(provider, prompt, output, history, false, true));
                assertEquals(2, provider.calls);
                assertSame(prompt, provider.lastPrompt);
                assertEquals(1, history.records.get(1).get("promptResubmissions").getAsInt());
            } else {
                assertThrows(ProviderNetworkException.class, () -> controller(elapsed, false, false)
                        .completeWithHistory(provider, prompt, output, history, false, true));
                assertEquals(1, provider.calls);
                assertEquals(0, elapsed.get());
                assertTrue(history.events.isEmpty());
            }
        }
    }

    @Test void optOutStopsAfterFirstProbeAndSecondaryFailuresDoNotRetry() {
        var provider = new Provider();
        provider.failedChecks = 1;
        var elapsed = new AtomicLong();
        assertThrows(ProviderNetworkException.class, () -> controller(elapsed, false, false)
                .completeWithHistory(provider, "prompt", new ByteArrayOutputStream(), null, false, false));
        assertEquals(0, provider.calls);
        assertEquals(0, elapsed.get());

        var broken = new Provider();
        broken.failedChecks = 3;
        OutputStream output = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("broken"); }
        };
        assertThrows(IOException.class, () -> controller(elapsed, false, false)
                .completeWithHistory(broken, "prompt", output, null, false, true));
        assertEquals(0, broken.calls);
        assertEquals(1, broken.checks);
    }

    @Test void healthySlowResponseHasNoDeadlineAndDoesNotInflateOutageDuration() throws Exception {
        var elapsed = new AtomicLong();
        Provider provider = new Provider() {
            @Override public String complete(String prompt) {
                elapsed.addAndGet(Duration.ofHours(2).toNanos());
                return super.complete(prompt);
            }
        };
        provider.failedChecks = 1;
        var history = new History();
        assertEquals("[]", controller(elapsed, false, false)
                .completeWithHistory(provider, "prompt", new ByteArrayOutputStream(), history, false, true));
        assertEquals(15000, history.records.get(1).get("elapsedMillis").getAsLong());
        assertEquals(1, provider.calls);
    }

    @Test void cancellationRestoresStyleClosesEpisodeAndDoesNotSubmit() {
        var elapsed = new AtomicLong();
        var provider = new Provider();
        provider.failedChecks = 1;
        var history = new History();
        var output = new ByteArrayOutputStream();
        var retry = new NetworkRetry(Clock.fixed(NOW, ZoneOffset.UTC), elapsed::get,
                nanos -> { throw new InterruptedException(); }, true, Duration.ofSeconds(15), false, false);
        try {
            assertThrows(InterruptedIOException.class,
                    () -> retry.completeWithHistory(provider, "prompt", output, history, false, true));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, provider.calls);
            assertTrue(output.toString().contains("\u001b[0m\n"));
            assertEquals("cancelled", history.records.get(1).get("outcome").getAsString());
        } finally { Thread.interrupted(); }
    }

    @Test void historyFailureStopsBeforePromptAndSuccessfulResponseIsNotReplayedOnSummaryFailure() {
        for (boolean atEnd : List.of(false, true)) {
            var provider = new Provider();
            provider.failedChecks = 1;
            History history = new History() {
                @Override public void record(String event, JsonObject data) throws IOException {
                    if (event.equals(atEnd ? "network_wait_finished" : "network_wait_started"))
                        throw new IOException("history unavailable");
                    super.record(event, data);
                }
            };
            assertThrows(IOException.class, () -> controller(new AtomicLong(), false, false)
                    .completeWithHistory(provider, "prompt", new ByteArrayOutputStream(), history, false, true));
            assertEquals(atEnd ? 1 : 0, provider.calls);
        }
    }

    @Test void recoveredNetworkHandsUsageLimitToExistingController() {
        Provider provider = new Provider() {
            @Override public String complete(String prompt) {
                calls++;
                throw new ProviderLimitException("test", ProviderLimitException.Kind.USAGE_LIMIT,
                        null, null, Duration.ofSeconds(2), null);
            }
        };
        provider.failedChecks = 1;
        var history = new History();
        assertThrows(ProviderLimitException.class, () -> controller(new AtomicLong(), false, false)
                .completeWithHistory(provider, "prompt", new ByteArrayOutputStream(), history, true, true));
        assertEquals("provider_limit", history.records.get(1).get("outcome").getAsString());
        assertEquals(1, provider.calls);
    }

    @Test void unknownErrorsCertificateFailuresAndInterruptionStayUnclassified() {
        assertTrue(ProviderNetworkException.transport(new RuntimeException(new java.net.UnknownHostException())));
        assertTrue(ProviderNetworkException.transport(new java.net.SocketException("reset")));
        assertFalse(ProviderNetworkException.transport(new javax.net.ssl.SSLHandshakeException("certificate")));
        assertFalse(ProviderNetworkException.transport(new IOException("network error")));
        assertFalse(ProviderNetworkException.transport(new InterruptedIOException()));
        var secondary = new java.net.ConnectException();
        secondary.addSuppressed(new IOException("other"));
        assertFalse(ProviderNetworkException.transport(secondary));
    }

    @Test void codexClassificationIgnoresToolOutputAndRejectsMixedFailures() {
        String message = "stream disconnected before completion: error sending request for url (https://example.invalid)";
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("message", message);
        var failure = assertThrows(ProviderNetworkException.class,
                () -> CodexEvents.response(1, error + "\n", ""));
        assertEquals(ProviderNetworkException.Phase.UNCERTAIN, failure.phase());
        RuntimeException mixed = assertThrows(RuntimeException.class,
                () -> CodexEvents.response(1, error + "\n{\"type\":\"error\",\"message\":\"authentication failed\"}\n", ""));
        assertFalse(mixed instanceof ProviderNetworkException);
        JsonObject item = new JsonObject();
        item.addProperty("type", "command_execution");
        item.addProperty("text", message);
        JsonObject event = new JsonObject();
        event.addProperty("type", "item.completed");
        event.add("item", item);
        assertFalse(assertThrows(RuntimeException.class,
                () -> CodexEvents.response(1, event + "\n", "")) instanceof ProviderNetworkException);
    }

    @Test void endpointResolutionUsesProviderConfigurationAndSanitizesDisplay() {
        JsonObject config = StrictJson.parse("{\"model_provider\":\"custom\",\"model_providers\":"
                + "{\"custom\":{\"base_url\":\"https://example.invalid/private/path\"}}}").getAsJsonObject();
        assertEquals("https://example.invalid/private/path", CodexNetworkEndpoint.select(config, null, null).toString());
        assertEquals("https://example.invalid", NetworkProbe.display(CodexNetworkEndpoint.select(config, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkProbe.endpoint("https://user:password@example.invalid/path"));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkProbe.endpoint("https://example.invalid/?secret=value"));
        config = new JsonObject();
        JsonObject account = new JsonObject();
        account.addProperty("type", "chatgpt");
        assertEquals("chatgpt.com", CodexNetworkEndpoint.select(config, account, null).getHost());
        account.addProperty("type", "apiKey");
        assertEquals("custom.invalid", CodexNetworkEndpoint.select(config, account, "https://custom.invalid/v1").getHost());
    }

    @Test void sdkAcceptsUnboundedGenerationTimeouts() {
        var client = com.openai.client.okhttp.OpenAIOkHttpClient.builder()
                .apiKey("offline-test-key").maxRetries(0)
                .timeout(com.openai.core.Timeout.builder().connect(Duration.ofSeconds(10))
                        .read(Duration.ZERO).write(Duration.ZERO).request(Duration.ZERO).build())
                .build();
        try { assertNotNull(client); }
        finally { client.close(); }
    }

    @Test void probeRoutingMatchesPinnedSdkProxyAndCodexEnvironment() {
        URI endpoint = URI.create("https://example.invalid/v1");
        var direct = NetworkProbe.command(endpoint, java.net.Proxy.NO_PROXY, 10);
        assertEquals("", direct.get(direct.indexOf("--proxy") + 1));
        assertEquals("--disable", direct.get(1));
        assertTrue(direct.contains("--head"));
        var proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                java.net.InetSocketAddress.createUnresolved("proxy.invalid", 8080));
        var routed = NetworkProbe.command(endpoint, proxy, 10);
        assertEquals("http://proxy.invalid:8080", routed.get(routed.indexOf("--proxy") + 1));
        assertEquals("", routed.get(routed.indexOf("--noproxy") + 1));
        var codex = NetworkProbe.command(endpoint, null, 10);
        assertFalse(codex.contains("--proxy"));
        assertFalse(codex.contains("--noproxy"));
        assertEquals("10", codex.get(codex.indexOf("--max-time") + 1));
    }

    @Test void fakeProbeDistinguishesNetworkCertificateAndProxyAuthentication() {
        URI endpoint = URI.create("https://example.invalid");
        assertThrows(ProviderNetworkException.class,
                () -> NetworkProbe.run(List.of("sh", "-c", "exit 6"), "test", endpoint, 1));
        assertThrows(IOException.class,
                () -> NetworkProbe.run(List.of("sh", "-c", "exit 60"), "test", endpoint, 1));
        assertThrows(IOException.class,
                () -> NetworkProbe.run(List.of("sh", "-c", "printf '000 407'; exit 56"), "test", endpoint, 1));
        assertDoesNotThrow(() -> NetworkProbe.run(List.of("sh", "-c", "printf '401 200'"),
                "test", endpoint, 1));
    }

    @Test void hungProbeIsBoundedIncludingDnsAndCanBeInterrupted() {
        assertTimeoutPreemptively(Duration.ofSeconds(4), () ->
                assertThrows(ProviderNetworkException.class, () -> NetworkProbe.run(
                        List.of("sh", "-c", "sleep 30"), "test", URI.create("https://example.invalid"), 1)));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> NetworkProbe.run(
                    List.of("sh", "-c", "exit 0"), "test", URI.create("https://example.invalid"), 1));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
