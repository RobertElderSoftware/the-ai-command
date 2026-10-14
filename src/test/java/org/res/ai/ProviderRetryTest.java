package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ProviderRetryTest {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static ProviderLimitException limit(Duration after) {
        return new ProviderLimitException("test", ProviderLimitException.Kind.USAGE_LIMIT,
                null, null, after, null);
    }
    private static ProviderRetry controller(AtomicLong elapsed, boolean terminal) {
        return new ProviderRetry(Clock.fixed(NOW, ZoneOffset.UTC), elapsed::get,
                nanos -> elapsed.addAndGet(nanos), terminal);
    }

    @Test void waitsWithBufferAndReusesExactPromptAcrossRepeatedLimits() throws Exception {
        AtomicLong elapsed = new AtomicLong();
        List<String> prompts = new ArrayList<>();
        String prompt = "exact prompt\n";
        var provider = new CapturingLLMProvider(value -> {
            prompts.add(value);
            if (prompts.size() < 3) throw limit(Duration.ofSeconds(2));
            return "[]";
        });
        var output = new ByteArrayOutputStream();
        assertEquals("[]", controller(elapsed, false).complete(provider, prompt, output, null, false, true));
        assertEquals(Duration.ofSeconds(14).toNanos(), elapsed.get());
        assertEquals(3, prompts.size());
        prompts.forEach(value -> assertSame(prompt, value));
        assertEquals(4, output.toString().lines().count());
        assertFalse(output.toString().contains("\u001b"));
        assertTrue(output.toString().contains("Ctrl+C"));
        assertFalse(output.toString().contains("Ctrl+D"));
    }

    @Test void timingRequiresCompleteFutureMetadataAndUsesLatestWindow() {
        var windows = List.of(
                new ProviderLimitException.ResetWindow("test", "primary", NOW.plusSeconds(10)),
                new ProviderLimitException.ResetWindow("test", "secondary", NOW.plusSeconds(20)));
        assertEquals(Duration.ofSeconds(25), ProviderRetry.delay(limit(null).withWindows(windows), NOW));
        assertNull(ProviderRetry.delay(limit(null), NOW));
        assertNull(ProviderRetry.delay(limit(Duration.ZERO), NOW));
        assertNull(ProviderRetry.delay(new ProviderLimitException("test",
                ProviderLimitException.Kind.QUOTA_EXHAUSTED, null, NOW.plusSeconds(10), null, null), NOW));
        assertNull(ProviderRetry.delay(new ProviderLimitException("test",
                ProviderLimitException.Kind.USAGE_LIMIT, null, NOW.minusSeconds(1), null, "5:19 PM"), NOW));
        assertNull(ProviderRetry.delay(limit(Duration.ofSeconds(Long.MAX_VALUE)), NOW));
        assertNull(ProviderRetry.delay(limit(null).withWindows(List.of(
                new ProviderLimitException.ResetWindow("test", "primary", NOW), windows.get(1))), NOW));
        var incomplete = StrictJson.parse("{\"rateLimits\":{\"limitId\":\"codex\","
                + "\"primary\":{\"usedPercent\":100},"
                + "\"secondary\":{\"usedPercent\":100,\"resetsAt\":1800000000}}}").getAsJsonObject();
        assertTrue(CodexRateLimits.windows(incomplete, "codex").isEmpty());
    }

    @Test void optOutUnknownTimingAndSecondaryFailuresDoNotRetry() throws Exception {
        for (boolean enabled : List.of(false, true)) {
            AtomicLong elapsed = new AtomicLong();
            var provider = new CapturingLLMProvider(prompt -> { throw limit(null); });
            assertThrows(ProviderLimitException.class, () -> controller(elapsed, false)
                    .complete(provider, "prompt", new ByteArrayOutputStream(), null, false, enabled));
            assertEquals(1, provider.calls());
            assertEquals(0, elapsed.get());
        }
        var failure = limit(Duration.ofSeconds(2));
        failure.addSuppressed(new IOException("cleanup"));
        var provider = new CapturingLLMProvider(prompt -> { throw failure; });
        assertSame(failure, assertThrows(ProviderLimitException.class, () -> controller(new AtomicLong(), false)
                .complete(provider, "prompt", new ByteArrayOutputStream(), null, false, true)));
        assertEquals(1, provider.calls());
        var timed = new CapturingLLMProvider(prompt -> { throw limit(Duration.ofSeconds(2)); });
        OutputStream broken = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("broken"); }
        };
        assertThrows(IOException.class, () -> controller(new AtomicLong(), false)
                .complete(timed, "prompt", broken, null, false, true));
        assertEquals(1, timed.calls());
        assertTrue(TheAICommand.parseOptions(new String[]{"--no-auto-retry"}, new ArrayList<>())
                .containsKey("--no-auto-retry"));
        assertThrows(IllegalArgumentException.class, () -> TheAICommand.parseOptions(
                new String[]{"--no-auto-retry=true"}, new ArrayList<>()));
    }

    @Test void interruptionPreventsResubmissionAndRestoresStyle() {
        var retry = new ProviderRetry(Clock.fixed(NOW, ZoneOffset.UTC), () -> 0,
                nanos -> { throw new InterruptedException(); }, true);
        var provider = new CapturingLLMProvider(prompt -> { throw limit(Duration.ofSeconds(1)); });
        var output = new ByteArrayOutputStream();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> retry.complete(provider, "prompt", output, null, false, true));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, provider.calls());
            assertTrue(output.toString().contains("\u001b[0m\n"));
            assertTrue(output.toString().contains("Retry cancelled"));
        } finally { Thread.interrupted(); }
    }

    @Test void retriesFollowUpWithoutRepeatingContextCommitAndKeepsHistoryCompact(@TempDir Path directory)
            throws Exception {
        Files.createDirectory(directory.resolve("conversation_history"));
        Files.writeString(directory.resolve("context.json"),
                "{\"contexts\":[],\"permissions\":[{\"new.txt\":\"READ_WRITE\"}]}");
        List<String> prompts = new ArrayList<>();
        var provider = new CapturingLLMProvider(prompt -> {
            prompts.add(prompt);
            if (prompts.size() == 1)
                return "[{\"op\":\"change_contexts\",\"add\":[\"new.txt\"],\"remove\":[]}]";
            if (prompts.size() == 2) throw limit(Duration.ofSeconds(2));
            return "[{\"op\":\"file_write\",\"path\":\"new.txt\",\"encoding\":\"utf-8\",\"data\":\"done\"}]";
        });
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            app.setRetryController(controller(new AtomicLong(), false));
            app.runWithHistory(new byte[0], List.of(), null);
        }
        assertEquals(3, prompts.size());
        assertSame(prompts.get(1), prompts.get(2));
        assertEquals("done", Files.readString(directory.resolve("new.txt")));
        String history;
        try (var files = Files.list(directory.resolve("conversation_history"))) {
            history = Files.readString(files.findFirst().orElseThrow());
        }
        for (String event : List.of("user", "contexts_changed", "provider_wait", "provider_retry", "applied"))
            assertEquals(1, history.split(" \\| " + event + " \\| ", -1).length - 1, event);
        assertTrue(history.contains("\"automaticReplayAuthorized\":true"));
        assertTrue(history.contains("\"contextCommitted\":true"));
        assertFalse(history.contains("remaining"));
        assertFalse(history.contains(" | provider_limit | "));
    }

    @Test void parseFailuresAreOutsideRetryLoop(@TempDir Path directory) {
        var provider = new CapturingLLMProvider("invalid JSON");
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            app.setRetryController(controller(new AtomicLong(), false));
            assertThrows(IllegalArgumentException.class, () -> app.run(new byte[0], List.of()));
        }
        assertEquals(1, provider.calls());
    }
}
