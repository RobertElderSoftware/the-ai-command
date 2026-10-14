package org.res.ai;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedNetworkRetryTest {
    private static ProviderNetworkException lost() {
        return new ProviderNetworkException("test", "https://example.invalid",
                ProviderNetworkException.Phase.UNCERTAIN);
    }
    private static class Provider implements LLMProvider {
        int calls;
        int lookups;
        @Override public String complete(String prompt) { calls++; throw lost(); }
        @Override public void close() { }
    }
    private static NetworkRetry retry(AtomicLong time, NetworkRetryBudget budget) {
        return new NetworkRetry(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), time::get,
                time::addAndGet, false, Duration.ofSeconds(15), true, false, budget);
    }

    @Test void repeatedAcceptedButLostRequestsStopAfterThreeReplays() {
        AtomicLong time = new AtomicLong();
        List<Long> times = new ArrayList<>();
        List<String> prompts = new ArrayList<>();
        Provider provider = new Provider() {
            @Override public String complete(String prompt) {
                times.add(time.get());
                prompts.add(prompt);
                return super.complete(prompt);
            }
        };
        var output = new ByteArrayOutputStream();
        String prompt = new String("retained");
        assertThrows(ProviderNetworkException.class, () ->
                retry(time, new NetworkRetryBudget(3, 30, () -> 0))
                        .completeWithHistory(provider, prompt, output, null, false, true));
        assertEquals(List.of(0L, 30_000_000_000L, 90_000_000_000L, 210_000_000_000L), times);
        assertEquals(4, provider.calls);
        prompts.forEach(value -> assertSame(prompt, value));
        assertTrue(output.toString().contains("budget exhausted (3/3)"));
        assertTrue(output.toString().contains("duplicate quota"));
    }

    @Test void retrievalReturnsOriginalWithoutSpendingReplayBudget() throws Exception {
        Provider provider = new Provider() {
            @Override public boolean canRecover() { return true; }
            @Override public String recover() { lookups++; return "original"; }
        };
        var budget = new NetworkRetryBudget(0, 30, () -> 0);
        assertEquals("original", retry(new AtomicLong(), budget).completeWithHistory(
                provider, "prompt", new ByteArrayOutputStream(), null, false, true));
        assertEquals(1, provider.calls);
        assertEquals(1, provider.lookups);
        assertEquals(0, budget.used());
    }

    @Test void failedRetrievalsAreBoundedAndDoNotResetReplayCounter() {
        Provider provider = new Provider() {
            @Override public boolean canRecover() { return true; }
            @Override public String recover() { lookups++; throw lost(); }
        };
        var budget = new NetworkRetryBudget(1, 30, () -> 0);
        assertThrows(ProviderNetworkException.class, () -> retry(new AtomicLong(), budget)
                .completeWithHistory(provider, "prompt", new ByteArrayOutputStream(), null, false, true));
        assertEquals(2, provider.calls);
        assertEquals(6, provider.lookups);
        assertEquals(1, budget.used());
    }

    @Test void flappingChecksDoNotSpendOrReplenishBudget() {
        Provider provider = new Provider() {
            int checks;
            @Override public void checkNetwork() {
                if (++checks % 2 == 0) throw new ProviderNetworkException("test", "endpoint",
                        ProviderNetworkException.Phase.PREFLIGHT);
            }
        };
        var budget = new NetworkRetryBudget(2, 30, () -> 0);
        assertThrows(ProviderNetworkException.class, () -> retry(new AtomicLong(), budget)
                .completeWithHistory(provider, "prompt", new ByteArrayOutputStream(), null, false, true));
        assertEquals(3, provider.calls);
        assertEquals(2, budget.used());
    }

    @Test void usageLimitHandoffDoesNotReplenishNetworkBudget() {
        Provider provider = new Provider() {
            @Override public String complete(String prompt) {
                if (++calls == 2) throw new ProviderLimitException("test",
                        ProviderLimitException.Kind.USAGE_LIMIT, null, null, Duration.ofSeconds(1), null);
                throw lost();
            }
        };
        var budget = new NetworkRetryBudget(1, 30, () -> 0);
        var retry = retry(new AtomicLong(), budget);
        assertThrows(ProviderLimitException.class, () -> retry.completeWithHistory(
                provider, "prompt", new ByteArrayOutputStream(), null, false, true));
        assertThrows(ProviderNetworkException.class, () -> retry.completeWithHistory(
                provider, "prompt", new ByteArrayOutputStream(), null, false, true));
        assertEquals(3, provider.calls);
        assertEquals(1, budget.used());
    }

    @Test void cancellationDuringBackoffPreventsRetrievalAndResubmission() {
        var provider = new Provider();
        var retry = new NetworkRetry(Clock.systemUTC(), () -> 0,
                nanos -> { throw new InterruptedException(); }, false, Duration.ofSeconds(15),
                true, false, new NetworkRetryBudget(3, 30, () -> 0));
        try {
            assertThrows(InterruptedIOException.class, () -> retry.completeWithHistory(
                    provider, "prompt", new ByteArrayOutputStream(), null, false, true));
            assertEquals(1, provider.calls);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void jitterNeverShortensDelayAndExponentialDelayCaps() {
        var budget = new NetworkRetryBudget(10, 30, () -> 1);
        assertEquals(Duration.ofSeconds(33).toNanos(), budget.delayNanos());
        for (int i = 0; i < 10; i++) budget.claim();
        assertEquals(Duration.ofSeconds(3960).toNanos(), budget.delayNanos());
        assertThrows(IllegalStateException.class, budget::claim);
        assertThrows(IllegalArgumentException.class, () -> new NetworkRetryBudget(11, 30, () -> 0));
        assertThrows(IllegalArgumentException.class, () -> new NetworkRetryBudget(3, 0, () -> 0));
    }
}
