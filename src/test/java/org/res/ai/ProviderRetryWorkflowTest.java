package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Offline workflow coverage; injected waits never sleep in real time. */
class ProviderRetryWorkflowTest {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private static ProviderLimitException limit() {
        return new ProviderLimitException("test", ProviderLimitException.Kind.USAGE_LIMIT,
                null, null, Duration.ofSeconds(2), null);
    }

    private static ProviderRetry controller(AtomicLong elapsed) {
        return new ProviderRetry(Clock.fixed(NOW, ZoneOffset.UTC), elapsed::get,
                nanos -> elapsed.addAndGet(nanos), false);
    }

    @Test void wallClockJumpsDoNotShortenOrExtendRelativeWaits() throws Exception {
        for (long jump : List.of(-86400L, 86400L)) {
            AtomicReference<Instant> wall = new AtomicReference<>(NOW);
            AtomicLong elapsed = new AtomicLong();
            Clock clock = new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
                @Override public Instant instant() { return wall.get(); }
            };
            ProviderRetry retry = new ProviderRetry(clock, elapsed::get, nanos -> {
                assertTrue(nanos > 0 && nanos <= 1_000_000_000L);
                elapsed.addAndGet(nanos);
                wall.updateAndGet(value -> value.plusSeconds(jump));
            }, true);
            int[] calls = {0};
            var provider = new CapturingLLMProvider(prompt -> {
                if (calls[0]++ == 0) throw limit();
                assertEquals(Duration.ofSeconds(7).toNanos(), elapsed.get());
                return "[]";
            });
            var output = new ByteArrayOutputStream();
            assertEquals("[]", retry.complete(provider, "prompt", output, null, false, true));
            assertEquals(2, provider.calls());
            assertTrue(output.toString().contains("00:00:07"));
            assertTrue(output.toString().contains("00:00:01"));
        }
    }

    @Test void retriesDoNotConsumeControlFollowUpSlots(@TempDir Path directory) throws Exception {
        AtomicLong elapsed = new AtomicLong();
        List<String> prompts = new ArrayList<>();
        var provider = new CapturingLLMProvider(prompt -> {
            prompts.add(prompt);
            int call = prompts.size();
            if (call % 2 == 1) throw limit();
            return call < 12 ? "[{\"op\":\"list_files\"}]" : "[]";
        });
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            app.setRetryController(controller(elapsed));
            app.run(new byte[0], List.of());
        }
        assertEquals(12, provider.calls());
        assertEquals(Duration.ofSeconds(42).toNanos(), elapsed.get());
        for (int index = 0; index < prompts.size(); index += 2)
            assertSame(prompts.get(index), prompts.get(index + 1));
    }

    @Test void historyFailureBeforeWaitingPreventsResubmission(@TempDir Path directory) throws Exception {
        Path historyDirectory = Files.createDirectory(directory.resolve("conversation_history"));
        ConversationHistory history = new ConversationHistory(directory, null);
        history.input(new byte[0]);
        // Replace the daily file with a directory to force a deterministic append failure.
        try (var files = Files.list(historyDirectory)) {
            Path daily = files.findFirst().orElseThrow();
            Files.delete(daily);
            Files.createDirectory(daily);
        }
        AtomicLong elapsed = new AtomicLong();
        ProviderLimitException limit = limit();
        var provider = new CapturingLLMProvider(prompt -> { throw limit; });
        var output = new ByteArrayOutputStream();
        Exception failure = assertThrows(Exception.class, () -> controller(elapsed)
                .complete(provider, "prompt", output, history, false, true));
        assertTrue(failure instanceof IOException || failure instanceof RuntimeException);
        assertNotSame(limit, failure);
        assertArrayEquals(new Throwable[] {limit}, failure.getSuppressed());
        assertNull(FailureReporting.limit(failure));
        assertEquals(1, provider.calls());
        assertEquals(0, elapsed.get());
        assertEquals(0, output.size());
    }
}
