package org.res.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.res.ai.TestJson.*;

class AvailableFilesTest {
    private static final String REQUEST = "[{\"op\":\"list_files\"}]";
    private static final String NOTICE = "\u001b[32;4mSending the requested directory listing back to the LLM.\u001b[0m\n";

    static void followUp(Path directory) throws Exception {
        Files.writeString(directory.resolve("reference.txt"), "reference");
        AtomicInteger calls = new AtomicInteger();
        CapturingLLMProvider provider = new CapturingLLMProvider(prompt -> {
            if (calls.getAndIncrement() == 0) return REQUEST;
            assertTrue(prompt.contains("access=READ path=reference.txt---"));
            JsonObject report = reportFrom(prompt);
            assertEquals(1, report.getAsJsonArray("entries").size());
            assertEquals("reference.txt", report.getAsJsonArray("entries").get(0).getAsJsonObject().get("path").getAsString());
            return batch(stdout("done"));
        });
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (AICommandApplication app = new AICommandApplication(provider, output, directory)) {
            app.run(new byte[0], List.of(), new RequestContext(List.of(new ContextFile("reference.txt", FileAccess.READ))));
        }
        assertEquals(2, calls.get());
        assertEquals(NOTICE + "done", output.toString(StandardCharsets.UTF_8));
    }

    @Test void followsUpWithOriginalContext(@TempDir Path directory) throws Exception {
        followUp(directory);
    }

    @Test void filtersAndInspectsNestedFiles(@TempDir Path directory) throws Exception {
        Files.createDirectories(directory.resolve("hidden/deep"));
        Files.createDirectories(directory.resolve("visible/nested"));
        Files.createDirectory(directory.resolve("__ciop__"));
        Files.writeString(directory.resolve("__ciop__/secret"), "reserved");
        Files.writeString(directory.resolve("hidden/deep/text.txt"), "café\r\n日本語");
        Files.writeString(directory.resolve("denied.txt"), "denied");
        Files.write(directory.resolve("visible/binary"), new byte[] {0, (byte) 255});
        Files.write(directory.resolve("visible/invalid"), new byte[] {(byte) 195});
        Files.writeString(directory.resolve("visible/empty"), "");
        Files.writeString(directory.resolve("visible/lf"), "one\ntwo\n");
        Files.writeString(directory.resolve("visible/cr"), "\r");
        Files.createSymbolicLink(directory.resolve("visible/link"), directory.resolve("hidden"));
        Files.writeString(directory.resolve("policy.json"), "{\"contexts\":[],\"permissions\":["
                + "{\"hidden/deep/text.txt\":\"READ\"},{\"regex:visible(/.*)?\":\"READ_WRITE\"},"
                + "{\"regex:__ciop__/.*\":\"READ_WRITE\"}]}");
        RequestContext policy = RequestContext.load(directory, "policy.json");
        JsonObject report = JsonParser.parseString(AvailableFiles.report(directory, policy, List.of(), Set.of("visible/lf"))).getAsJsonObject();
        assertTrue(report.get("complete").getAsBoolean());
        assertTrue(report.getAsJsonArray("errors").isEmpty());
        java.util.Map<String, JsonObject> entries = new java.util.LinkedHashMap<>();
        report.getAsJsonArray("entries").forEach(value -> {
            JsonObject entry = value.getAsJsonObject();
            entries.put(entry.get("path").getAsString(), entry);
        });
        assertEquals(List.of("hidden/deep/text.txt", "visible", "visible/binary", "visible/cr",
                "visible/empty", "visible/invalid", "visible/lf", "visible/nested"), List.copyOf(entries.keySet()));
        assertEquals("READ", entries.get("hidden/deep/text.txt").get("access").getAsString());
        assertEquals(Files.size(directory.resolve("hidden/deep/text.txt")), entries.get("hidden/deep/text.txt").get("size_bytes").getAsLong());
        assertEquals(2, entries.get("hidden/deep/text.txt").get("line_count").getAsInt());
        assertEquals("READ_WRITE", entries.get("visible/binary").get("access").getAsString());
        assertEquals("READ", entries.get("visible/lf").get("access").getAsString());
        assertEquals(2, entries.get("visible/lf").get("line_count").getAsInt());
        assertEquals(0, entries.get("visible/empty").get("line_count").getAsInt());
        assertEquals(1, entries.get("visible/cr").get("line_count").getAsInt());
        assertFalse(entries.get("visible/binary").has("line_count"));
        assertFalse(entries.get("visible/invalid").has("line_count"));
        assertFalse(entries.get("visible/nested").has("size_bytes"));
    }

    @Test void mixedAndMalformedRequestsHaveNoBatchEffects(@TempDir Path directory) throws Exception {
        for (String response : List.of(
                batch(fileWrite("result.txt", "bad"), object("op", "list_files"), stdout("hidden")),
                "[{\"op\":\"list_files\",\"path\":\".\"}]",
                "[{\"op\":\"list_files\"},{\"op\":\"list_files\"}]")) {
            CapturingLLMProvider provider = new CapturingLLMProvider(response);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (AICommandApplication app = new AICommandApplication(provider, output, directory)) {
                assertThrows(IllegalArgumentException.class, () -> app.run(new byte[0], List.of("result.txt")));
            }
            assertEquals(1, provider.calls());
            assertEquals(0, output.size());
            assertFalse(Files.exists(directory.resolve("result.txt")));
        }
    }

    @Test void boundsFollowUpsAndPropagatesProviderFailure(@TempDir Path directory) throws Exception {
        CapturingLLMProvider provider = new CapturingLLMProvider(REQUEST);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (AICommandApplication app = new AICommandApplication(provider, output, directory)) {
            var failure = assertThrows(IllegalArgumentException.class, () -> app.run(new byte[0], List.of()));
            assertTrue(failure.getMessage().contains("limit exceeded"));
        }
        assertEquals(6, provider.calls());
        assertEquals(NOTICE.repeat(5), output.toString(StandardCharsets.UTF_8));
        AtomicInteger calls = new AtomicInteger();
        CapturingLLMProvider broken = new CapturingLLMProvider(prompt -> {
            if (calls.getAndIncrement() == 0) return REQUEST;
            throw new IllegalStateException("provider failed");
        });
        try (AICommandApplication app = new AICommandApplication(broken, new ByteArrayOutputStream(), directory)) {
            assertEquals("provider failed", assertThrows(IllegalStateException.class,
                    () -> app.run(new byte[0], List.of())).getMessage());
        }
        assertEquals(2, calls.get());
    }

    @Test void recordsReadableListingAndProtectsHistoryPermissions(@TempDir Path directory) throws Exception {
        Files.createDirectory(directory.resolve(ConversationHistory.DIRECTORY));
        Files.writeString(directory.resolve("context.json"), contextJson(readWrite("context.json")));
        AtomicInteger calls = new AtomicInteger();
        CapturingLLMProvider provider = new CapturingLLMProvider(prompt -> {
            if (calls.getAndIncrement() == 0) return REQUEST;
            assertTrue(prompt.contains("list_files_requested"));
            JsonObject report = reportFrom(prompt);
            report.getAsJsonArray("entries").forEach(value -> {
                JsonObject entry = value.getAsJsonObject();
                String path = entry.get("path").getAsString();
                if (path.equals("context.json") || path.startsWith("conversation_history"))
                    assertEquals("READ", entry.get("access").getAsString());
            });
            return batch(stdout("done"));
        });
        try (AICommandApplication app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            app.runWithHistory(new byte[0], List.of(), null);
        }
        try (var paths = Files.list(directory.resolve(ConversationHistory.DIRECTORY))) {
            String saved = paths.map(path -> {
                try { return Files.readString(path); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }).collect(java.util.stream.Collectors.joining());
            assertEquals(1, saved.lines().filter(line -> line.contains(" | list_files_requested | ")).count());
            assertEquals(2, saved.lines().filter(line -> line.contains(" | request_sent | ")).count());
            assertEquals(2, saved.lines().filter(line -> line.contains(" | response_received | ")).count());
            assertEquals(1, saved.lines().filter(line -> line.contains(" | applied | ")).count());
            assertEquals(1, saved.lines().filter(line -> line.contains(" | list_files_report | ")).count());
            String report = ListingHistoryFormat.format(reportFrom(provider.prompt()).toString());
            assertTrue(saved.contains(" | encoding=utf-8 | bytes="
                    + report.getBytes(StandardCharsets.UTF_8).length + " ---\n" + report + "\n"));
            assertTrue(saved.indexOf(" | list_files_report | ") < saved.lastIndexOf(" | request_sent | "));
            assertTrue(provider.prompt().contains(" | list_files_report | "));
            assertTrue(report.contains("file: \"context.json\" | permission: 4 | bytes: "));
            assertTrue(report.contains(" | lines: "));
            assertTrue(report.startsWith("Complete: yes\n"));
            assertTrue(report.endsWith("Errors: 0\n"));
            assertFalse(saved.contains(NOTICE));
        }
    }

    private static JsonObject reportFrom(String prompt) {
        int header = prompt.lastIndexOf("---SECTION STDIN utf-8 ");
        assertTrue(header >= 0);
        int start = prompt.indexOf('\n', header) + 1;
        int end = prompt.indexOf("\n---END SECTION---", start);
        String input = prompt.substring(start, end);
        assertTrue(input.startsWith("This is a listing of available files"));
        return JsonParser.parseString(input.substring(input.indexOf('\n') + 1)).getAsJsonObject();
    }
}
