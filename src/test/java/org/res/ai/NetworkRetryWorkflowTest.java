package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
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

/** Full filesystem workflows, run by Maven after the CIOP batch is applied. */
class NetworkRetryWorkflowTest {
    private static ProviderRetry controller(AtomicLong elapsed) {
        return new ProviderRetry(Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC),
                elapsed::get, elapsed::addAndGet, false);
    }

    private static ProviderNetworkException down() {
        return new ProviderNetworkException("test", "https://example.invalid",
                ProviderNetworkException.Phase.PREFLIGHT);
    }

    private static String history(Path directory) throws Exception {
        try (var files = Files.list(directory.resolve(ConversationHistory.DIRECTORY))) {
            return Files.readString(files.findFirst().orElseThrow());
        }
    }

    @Test void outageAfterContextCommitDoesNotRepeatCommit(@TempDir Path directory) throws Exception {
        Files.createDirectory(directory.resolve(ConversationHistory.DIRECTORY));
        Files.writeString(directory.resolve("context.json"),
                "{\"contexts\":[],\"permissions\":[{\"result.txt\":\"READ_WRITE\"}]}");
        var prompts = new ArrayList<String>();
        int[] checks = {0};
        LLMProvider provider = new LLMProvider() {
            @Override public void checkNetwork() { if (++checks[0] == 2 || checks[0] == 3) throw down(); }
            @Override public String complete(String prompt) {
                prompts.add(prompt);
                return prompts.size() == 1
                        ? "[{\"op\":\"change_contexts\",\"add\":[\"result.txt\"],\"remove\":[]}]"
                        : "[{\"op\":\"file_write\",\"path\":\"result.txt\",\"encoding\":\"utf-8\",\"data\":\"done\"}]";
            }
            @Override public void close() { }
        };
        var elapsed = new AtomicLong();
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory, false)) {
            app.setRetryController(controller(elapsed));
            app.runWithHistory(new byte[0], List.of(), null);
        }
        assertEquals("done", Files.readString(directory.resolve("result.txt")));
        assertEquals(2, prompts.size());
        assertEquals(Duration.ofSeconds(30).toNanos(), elapsed.get());
        String saved = history(directory);
        for (String event : List.of("contexts_changed", "network_wait_started", "network_wait_finished", "applied"))
            assertEquals(1, saved.split(" \\| " + event + " \\| ", -1).length - 1, event);
        assertTrue(saved.contains("\"contextCommitted\":true"));
        assertFalse(saved.contains("remaining"));
    }

    @Test void networkRetriesPreserveAllFiveControlFollowUps(@TempDir Path directory) throws Exception {
        int[] checks = {0};
        int[] calls = {0};
        LLMProvider provider = new LLMProvider() {
            @Override public void checkNetwork() { if (++checks[0] % 2 == 1) throw down(); }
            @Override public String complete(String prompt) {
                return ++calls[0] <= 5 ? "[{\"op\":\"list_files\"}]"
                        : "[{\"op\":\"file_write\",\"path\":\"result.txt\",\"encoding\":\"utf-8\",\"data\":\"done\"}]";
            }
            @Override public void close() { }
        };
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory, false)) {
            app.setRetryController(controller(new AtomicLong()));
            app.run(new byte[0], List.of("result.txt"));
        }
        assertEquals(6, calls[0]);
        assertEquals(12, checks[0]);
        assertEquals("done", Files.readString(directory.resolve("result.txt")));
    }

    @Test void uncertainFollowUpStopsWithoutApplyingFiles(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("context.json"),
                "{\"contexts\":[],\"permissions\":[{\"result.txt\":\"READ_WRITE\"}]}");
        int[] calls = {0};
        LLMProvider provider = new LLMProvider() {
            @Override public String complete(String prompt) {
                if (++calls[0] == 1)
                    return "[{\"op\":\"change_contexts\",\"add\":[\"result.txt\"],\"remove\":[]}]";
                throw new ProviderNetworkException("test", "https://example.invalid",
                        ProviderNetworkException.Phase.UNCERTAIN);
            }
            @Override public void close() { }
        };
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory, false)) {
            app.setRetryController(controller(new AtomicLong()));
            var failure = assertThrows(ContextCommittedException.class,
                    () -> app.runWithHistory(new byte[0], List.of(), null));
            assertInstanceOf(ProviderNetworkException.class, failure.getCause());
        }
        assertEquals(5, calls[0]);
        assertFalse(Files.exists(directory.resolve("result.txt")));
        assertTrue(RequestContext.load(directory, "context.json").files().stream()
                .anyMatch(file -> file.path().equals("result.txt")));
    }
}
