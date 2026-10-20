package org.res.ai;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class CodexActivityTest {
    private static final String ANSWER = "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"[]\"}}\n";
    private static final String DONE = "{\"type\":\"turn.completed\"}\n";

    private static JsonObject command(String type, String id, String output) {
        JsonObject item = new JsonObject();
        item.addProperty("id", id);
        item.addProperty("type", "command_execution");
        item.addProperty("command", "printf café\u001b[31m\n");
        item.addProperty("status", type.equals("item.completed") ? "completed" : "in_progress");
        if (type.equals("item.completed")) item.addProperty("exit_code", 0);
        item.addProperty("aggregated_output", output);
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.add("item", item);
        return event;
    }

    @Test void splitBytesPreserveUnicodeAndFinalResponseAndDeduplicateCommands() {
        List<String> messages = new ArrayList<>();
        AtomicLong time = new AtomicLong();
        var activity = new CodexCommandActivity((event, text) -> messages.add(text), true, true, false, time::get);
        var parser = new CodexEvents(activity);
        String start = command("item.started", "item_1", "").toString() + "\n";
        for (byte value : (start + start).getBytes(StandardCharsets.UTF_8)) parser.stdout(new byte[] {value});
        time.set(2_000_000_000L);
        String finish = command("item.completed", "item_1", "tool output: usage_limit_reached").toString() + "\n";
        for (byte value : (finish + finish + ANSWER + DONE.stripTrailing()).getBytes(StandardCharsets.UTF_8))
            parser.stdout(new byte[] {value});
        assertEquals("[]", parser.result(0));
        assertEquals(3, messages.size());
        assertTrue(messages.get(0).contains("café\\u001b[31m\\u000a"));
        assertTrue(messages.get(1).contains("observed elapsed 2.000s"));
        assertTrue(messages.get(2).contains("usage_limit_reached"));
        assertFalse(String.join("", messages).contains("\u001b"));
    }

    @Test void missingStartsAndMissingCompletionsAreExplicit() {
        List<String> messages = new ArrayList<>();
        var activity = new CodexCommandActivity((event, text) -> messages.add(text), true, false, false, () -> 0);
        activity.accept(command("item.completed", "orphan", ""));
        activity.accept(command("item.updated", "open", ""));
        activity.finish();
        activity.finish();
        assertEquals(3, messages.size());
        assertTrue(messages.get(0).contains("elapsed unavailable"));
        assertTrue(messages.get(2).contains("completion not observed"));
    }

    @Test void outputAndMemoryReportsAreBoundedAndUntrusted() {
        List<String> messages = new ArrayList<>();
        var activity = new CodexCommandActivity((event, text) -> messages.add(text), true, true, true, () -> 0);
        String marker = "CIOP_MEMORY_FILE {\"label\":\"frame\",\"bytes\":12,\"purpose\":\"render\"}\n";
        activity.accept(command("item.completed", "one", marker + "x".repeat(10000)));
        assertEquals(3, messages.size());
        assertTrue(messages.get(1).contains("[truncated]"));
        assertTrue(messages.get(2).contains("program-reported memory file (unverified): frame; bytes 12"));
        assertTrue(messages.get(1).length() < 4400);
        activity.accept(command("item.completed", "two", "CIOP_MEMORY_FILE {\"bytes\":-1}\n"));
        assertTrue(messages.get(messages.size() - 1).contains("ignored invalid"));
    }

    @Test void disabledReportingAndAttemptIdentifiers() {
        List<String> messages = new ArrayList<>();
        var disabled = new CodexCommandActivity((event, text) -> messages.add(text), false, true, true, () -> 0);
        disabled.accept(command("item.completed", "same", "output"));
        disabled.finish();
        assertTrue(messages.isEmpty());
        for (int index = 0; index < 2; index++)
            new CodexCommandActivity((event, text) -> messages.add(text), true, false, false, () -> 0)
                    .accept(command("item.completed", "same", ""));
        assertNotEquals(messages.get(0), messages.get(1));
    }

    @Test void malformedAndOversizedRecordsCannotBecomeRetrySignals() {
        var parser = new CodexEvents(null);
        parser.stdout(new byte[] {(byte) 0xff, '\n'});
        parser.stdout("{\"type\":\"error\",\"code\":\"usage_limit_reached\"}\n".getBytes(StandardCharsets.UTF_8));
        RuntimeException failure = assertThrows(RuntimeException.class, () -> parser.result(1));
        assertFalse(failure instanceof ProviderLimitException);
        var oversized = new CodexEvents(null);
        byte[] block = new byte[8192];
        java.util.Arrays.fill(block, (byte) ' ');
        for (int index = 0; index < CodexEvents.MAX_RECORD_BYTES / block.length; index++) oversized.stdout(block);
        assertThrows(IllegalArgumentException.class, () -> oversized.stdout(new byte[] {'x'}));
        var diagnostic = new CodexEvents(null);
        diagnostic.stderr(new byte[100000]);
        RuntimeException bounded = assertThrows(RuntimeException.class, () -> diagnostic.result(1));
        assertTrue(bounded.getMessage().length() < 66000);
        assertTrue(bounded.getMessage().contains("diagnostic truncated"));
    }

    @Test void commandNoticesArriveBeforeExitWhileLargePromptIsWritten() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            CountDownLatch started = new CountDownLatch(1);
            String start = command("item.started", "live", "").toString();
            String end = command("item.completed", "live", "").toString();
            // The child emits before consuming stdin, then remains alive after the first notice.
            String script = "printf '%s\\n' '" + start + "'; sleep 1; cat >/dev/null; printf '%s\\n' '"
                    + end + "' '" + ANSWER.stripTrailing() + "' '" + DONE.stripTrailing() + "'";
            var executor = Executors.newSingleThreadExecutor();
            try (var provider = new CodexExecLLMProvider(List.of("sh", "-c", script))) {
                provider.setActivityListener((event, text) -> {
                    if (event.equals("execution_started")) started.countDown();
                });
                var result = executor.submit(() -> provider.complete("p".repeat(2 * 1024 * 1024)));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertFalse(result.isDone());
                assertEquals("[]", result.get(5, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }
        });
    }

    @Test void reportingFailureCannotTriggerProviderRetry() throws Exception {
        String script = "cat >/dev/null; printf '%s\\n' '" + command("item.started", "fail", "")
                + "' '{\"type\":\"turn.failed\",\"error\":{\"code\":\"usage_limit_reached\"}}'";
        AtomicLong notices = new AtomicLong();
        var retry = new ProviderRetry(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> 0,
                nanos -> fail("Reporting failure must not wait or retry"), false);
        try (var provider = new CodexExecLLMProvider(List.of("sh", "-c", script))) {
            provider.setActivityListener((event, text) -> {
                notices.incrementAndGet();
                throw new IOException("broken activity output");
            });
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> retry.complete(provider, "prompt", new ByteArrayOutputStream(), null, false, true));
            assertNull(FailureReporting.limit(failure));
            assertInstanceOf(ProviderActivity.ReportingFailure.class, failure);
            assertTrue(notices.get() >= 1);
        }
    }

    @Test void applicationPublishesBlueNoticesAndReadableHistory(@TempDir Path directory) throws Exception {
        Files.createDirectory(directory.resolve(ConversationHistory.DIRECTORY));
        String script = "cat >/dev/null; printf '%s\\n' '" + command("item.started", "history", "")
                + "' '" + command("item.completed", "history", "") + "' '"
                + ANSWER.stripTrailing() + "' '" + DONE.stripTrailing() + "'";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var app = new AICommandApplication(new CodexExecLLMProvider(List.of("sh", "-c", script)),
                output, directory, false)) {
            app.runWithHistory(new byte[0], List.of(), null);
        }
        String displayed = output.toString(StandardCharsets.UTF_8);
        assertTrue(displayed.contains("\u001b[34;4m# exec:"));
        try (var files = Files.list(directory.resolve(ConversationHistory.DIRECTORY))) {
            String history = Files.readString(files.findFirst().orElseThrow());
            assertTrue(history.contains(" | execution_started | "));
            assertTrue(history.contains(" | execution_completed | "));
            assertFalse(history.contains("\u001b"));
            assertTrue(history.contains("# exec: attempt "));
        }
    }

    @Test void plainPublisherAndOutputFailure(@TempDir Path directory) throws Exception {
        String old = System.getProperty("ai.retry.ansi");
        try {
            System.setProperty("ai.retry.ansi", "false");
            var output = new ByteArrayOutputStream();
            ProviderActivity.publisher(output, null).accept("execution_started", "# exec: hello");
            assertEquals("# exec: hello\n", output.toString(StandardCharsets.UTF_8));
            OutputStream broken = new OutputStream() {
                @Override public void write(int value) throws IOException { throw new IOException("broken"); }
            };
            assertThrows(IOException.class, () -> ProviderActivity.publisher(broken, null)
                    .accept("execution_started", "# exec: hello"));
        } finally {
            if (old == null) System.clearProperty("ai.retry.ansi");
            else System.setProperty("ai.retry.ansi", old);
        }
    }
}
