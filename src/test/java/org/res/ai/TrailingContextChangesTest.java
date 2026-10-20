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
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.res.ai.TestJson.*;

/** Integration coverage for final, transactional selection changes. */
class TrailingContextChangesTest {
    @TempDir Path directory;

    private RequestContext setup() throws Exception {
        Files.writeString(directory.resolve("work.txt"), "one\ntwo\n");
        Files.writeString(directory.resolve("custom.json"), contextJson(readWrite("work.txt")));
        return RequestContext.load(directory, "custom.json");
    }

    private static JsonObject change(List<?> add, List<String> remove) {
        return object("op", "change_contexts", "add", add, "remove", remove, "follow_up", false);
    }

    private static JsonObject excerpt(String path, int first, int last) {
        return object("path", path, "lines", List.of(List.of(first, last)));
    }

    private static JsonObject workflow() {
        return object("op", "workflow_update", "expected_revision", 0, "actions", lines(
                object("action", "add_node", "node", object("id", "done", "text", "Edited file",
                        "status", "done", "owner", "assistant"))));
    }

    private ByteArrayOutputStream run(RequestContext context, CapturingLLMProvider provider, String... explicit)
            throws Exception {
        var output = new ByteArrayOutputStream();
        try (var app = new AICommandApplication(provider, output, directory)) {
            app.run(new byte[0], List.of(explicit), context);
        }
        return output;
    }

    private RequestContext saved() throws Exception { return RequestContext.load(directory, "custom.json"); }

    @Test void defaultsAndExplicitTrueStillFollowUp() throws Exception {
        for (boolean explicit : List.of(false, true)) {
            RequestContext context = setup();
            JsonObject operation = change(List.of(), List.of("work.txt"));
            if (explicit) operation.addProperty("follow_up", true);
            else operation.remove("follow_up");
            int[] calls = {0};
            var provider = new CapturingLLMProvider(prompt -> calls[0]++ == 0 ? batch(operation) : "[]");
            run(context, provider);
            assertEquals(2, provider.calls());
            assertTrue(saved().files().isEmpty());
        }
    }

    @Test void mixedBatchWritesConfigurationOnceAndNextPromptOmitsReleasedFile() throws Exception {
        RequestContext context = setup();
        var policy = context.permissionsDocument();
        int[] configWrites = {0};
        var provider = new CapturingLLMProvider(batch(fileWrite("work.txt", "changed"), workflow(),
                stdout("answer"), change(List.of(), List.of("work.txt"))));
        var output = new ByteArrayOutputStream();
        try (var app = new AICommandApplication(provider, output, directory) {
            @Override protected void commitFile(Path target, byte[] bytes) throws IOException {
                if (target.getFileName().toString().equals("custom.json")) configWrites[0]++;
                super.commitFile(target, bytes);
            }
        }) { app.run(new byte[0], List.of("work.txt"), context); }
        assertEquals(1, provider.calls());
        assertEquals(1, configWrites[0]);
        assertEquals("changed", Files.readString(directory.resolve("work.txt")));
        assertEquals(policy, saved().permissionsDocument());
        assertTrue(saved().files().isEmpty());
        assertEquals(1, saved().workflow().get("revision").getAsInt());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\u001b[32;4m# context:"));
        assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("answer"));
        var next = new CapturingLLMProvider("[]");
        run(saved(), next);
        assertFalse(next.prompt().contains("path=work.txt---"));
    }

    @Test void stagedCreationDeletionAndExcerptUpdates() throws Exception {
        RequestContext context = setup();
        JsonObject cleanup = change(List.of(excerpt("new.txt", 2, 2)), List.of("work.txt"));
        run(context, new CapturingLLMProvider(batch(fileWrite("new.txt", "a\nb\n"),
                object("op", "file_delete", "path", "work.txt"), cleanup)));
        assertFalse(Files.exists(directory.resolve("work.txt")));
        assertEquals("new.txt", saved().files().get(0).path());
        assertEquals(ContextSelection.Kind.LINES, saved().files().get(0).selection().kind());
        JsonObject update = change(List.of(), List.of());
        update.add("update", lines(excerpt("new.txt", 3, 3)));
        run(saved(), new CapturingLLMProvider(batch(fileWrite("new.txt", "a\nb\nc\n"), update)));
        assertEquals(3, saved().files().get(0).selection().ranges().get(0).first());
        assertNull(saved().workflow());
    }

    @Test void stagedBinaryRangesUseBytesAndLinesRequireWholeSourceUtf8() throws Exception {
        RequestContext context = setup();
        JsonObject bytes = object("path", "new.bin", "bytes", List.of(List.of(1, 1)));
        JsonObject write = object("op", "file_write", "path", "new.bin", "encoding", "base64", "data", "AP8=");
        run(context, new CapturingLLMProvider(batch(write, change(List.of(bytes), List.of()))));
        assertEquals(ContextSelection.Kind.BYTES, saved().files().get(1).selection().kind());
        String before = Files.readString(directory.resolve("custom.json"));
        JsonObject update = change(List.of(), List.of());
        update.add("update", lines(excerpt("new.bin", 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> run(saved(), new CapturingLLMProvider(batch(update))));
        assertEquals(before, Files.readString(directory.resolve("custom.json")));
    }

    @Test void invalidBatchesDoNotCommitOrPublish() throws Exception {
        RequestContext context = setup();
        String before = Files.readString(directory.resolve("custom.json"));
        JsonObject cleanup = change(List.of(), List.of("work.txt"));
        JsonObject nullFlag = cleanup.deepCopy();
        nullFlag.add("follow_up", com.google.gson.JsonNull.INSTANCE);
        JsonObject textFlag = cleanup.deepCopy();
        textFlag.addProperty("follow_up", "false");
        JsonObject numberFlag = cleanup.deepCopy();
        numberFlag.addProperty("follow_up", 0);
        for (String response : List.of(
                batch(cleanup, stdout("after")), batch(cleanup, cleanup),
                batch(object("op", "list_files"), cleanup), batch(nullFlag), batch(textFlag), batch(numberFlag),
                batch(fileWrite("work.txt", "short"), change(List.of(excerpt("new.txt", 1, 1)), List.of())),
                batch(fileWrite("new.txt", "one\n"), change(List.of(excerpt("new.txt", 2, 2)), List.of())),
                batch(fileWrite("new.txt", "one"), change(List.of("new.txt", "./new.txt"), List.of())),
                batch(fileWrite("new.txt", "one"), change(List.of("../outside.txt"), List.of())))) {
            var output = new ByteArrayOutputStream();
            var provider = new CapturingLLMProvider(response);
            try (var app = new AICommandApplication(provider, output, directory)) {
                assertThrows(IllegalArgumentException.class, () -> app.run(new byte[0], List.of(), context));
            }
            assertEquals(1, provider.calls());
            assertEquals(0, output.size());
            assertEquals(before, Files.readString(directory.resolve("custom.json")));
            assertEquals("one\ntwo\n", Files.readString(directory.resolve("work.txt")));
            assertFalse(Files.exists(directory.resolve("new.txt")));
        }
    }

    @Test void invalidRetainedExcerptRejectsDeletionAndNoopPreservesBytes() throws Exception {
        setup();
        JsonObject config = StrictJson.parse(Files.readString(directory.resolve("custom.json"))).getAsJsonObject();
        config.add("contexts", lines(excerpt("work.txt", 2, 2)));
        Files.writeString(directory.resolve("custom.json"), config.toString());
        String before = Files.readString(directory.resolve("custom.json"));
        JsonObject empty = change(List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> run(saved(), new CapturingLLMProvider(batch(
                object("op", "file_delete", "path", "work.txt"), empty))));
        assertThrows(IllegalArgumentException.class, () -> run(saved(), new CapturingLLMProvider(batch(
                fileWrite("work.txt", "one\n"), empty))));
        var output = run(saved(), new CapturingLLMProvider(batch(empty)));
        assertEquals(0, output.size());
        assertEquals(before, Files.readString(directory.resolve("custom.json")));
        assertEquals("one\ntwo\n", Files.readString(directory.resolve("work.txt")));
    }

    @Test void rollbackRestoresFilesAndConfiguration() throws Exception {
        RequestContext context = setup();
        String before = Files.readString(directory.resolve("custom.json"));
        var output = new ByteArrayOutputStream();
        var provider = new CapturingLLMProvider(batch(fileWrite("work.txt", "changed"), workflow(),
                stdout("hidden"), change(List.of(), List.of("work.txt"))));
        try (var app = new AICommandApplication(provider, output, directory) {
            @Override protected void commitFile(Path target, byte[] bytes) throws IOException {
                super.commitFile(target, bytes);
                if (target.getFileName().toString().equals("custom.json")) throw new IOException("Injected failure after config write");
            }
        }) {
            IOException failure = assertThrows(IOException.class, () -> app.run(new byte[0], List.of(), context));
            assertTrue(failure.getMessage().contains("rollback completed"));
        }
        assertEquals(1, provider.calls());
        assertEquals(0, output.size());
        assertEquals(before, Files.readString(directory.resolve("custom.json")));
        assertEquals("one\ntwo\n", Files.readString(directory.resolve("work.txt")));
    }

    @Test void staleConfigurationAndMissingConfigurationRejectCleanup() throws Exception {
        RequestContext context = setup();
        var provider = new CapturingLLMProvider(prompt -> {
            try { Files.writeString(directory.resolve("custom.json"), Files.readString(directory.resolve("custom.json")) + "\n"); }
            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            return batch(fileWrite("work.txt", "hidden"), change(List.of(), List.of("work.txt")));
        });
        assertThrows(IOException.class, () -> run(context, provider));
        assertEquals("one\ntwo\n", Files.readString(directory.resolve("work.txt")));
        assertThrows(IllegalArgumentException.class, () -> run(RequestContext.empty(),
                new CapturingLLMProvider(batch(change(List.of(), List.of())))));
    }

    @Test void historyExcerptsAndAliasesRemainProtectedAndChangesAreReadable() throws Exception {
        setup();
        Files.createDirectory(directory.resolve("conversation_history"));
        String history = "conversation_history/old.txt";
        Files.writeString(directory.resolve(history), "keep\nthis\n");
        Files.createLink(directory.resolve("history-alias.txt"), directory.resolve(history));
        JsonObject config = StrictJson.parse(Files.readString(directory.resolve("custom.json"))).getAsJsonObject();
        config.getAsJsonArray("contexts").add(excerpt(history, 1, 1));
        Files.writeString(directory.resolve("custom.json"), config.toString());
        for (String name : List.of(history, "history-alias.txt")) {
            JsonObject update = change(List.of(), List.of());
            update.add("update", lines(excerpt(name, 1, 1)));
            for (JsonObject request : List.of(change(List.of(), List.of(name)), update)) {
                assertThrows(IllegalArgumentException.class, () -> run(saved(), new CapturingLLMProvider(batch(request))));
            }
        }
        var provider = new CapturingLLMProvider(batch(change(List.of(), List.of("work.txt"))));
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            app.runWithHistory(new byte[0], List.of(), "custom.json");
        }
        assertEquals(1, provider.calls());
        ContextFile retained = saved().files().stream().filter(file -> file.path().equals(history)).findFirst().orElseThrow();
        assertEquals(ContextSelection.Kind.LINES, retained.selection().kind());
        assertEquals(1, retained.selection().ranges().get(0).second());
        try (var files = Files.list(directory.resolve("conversation_history"))) {
            StringBuilder log = new StringBuilder();
            for (Path file : files.toList()) log.append(Files.readString(file));
            assertTrue(log.toString().contains(" | contexts_changed | "));
            assertTrue(log.toString().contains("# context: Removed from context: \"work.txt\""));
        }
    }

    @Test void outputFailureAfterCommitDoesNotReplay() throws Exception {
        RequestContext context = setup();
        var provider = new CapturingLLMProvider(batch(fileWrite("work.txt", "changed"),
                change(List.of(), List.of("work.txt"))));
        OutputStream broken = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Broken output"); }
        };
        try (var app = new AICommandApplication(provider, broken, directory)) {
            IOException failure = assertThrows(IOException.class, () -> app.run(new byte[0], List.of(), context));
            assertTrue(failure.getMessage().contains("configuration committed"));
        }
        assertEquals(1, provider.calls());
        assertTrue(saved().files().isEmpty());
        assertEquals("changed", Files.readString(directory.resolve("work.txt")));
    }

    @Test void patchCorrectionRetainsCleanupAndCommitsOnlyCorrectedBatch() throws Exception {
        RequestContext context = setup();
        String before = Files.readString(directory.resolve("custom.json"));
        int[] calls = {0};
        var provider = new CapturingLLMProvider(prompt -> {
            if (calls[0]++ == 0) return batch(replaceFirstLine("work.txt", "one\ntwo\n", "wrong", "changed"),
                    change(List.of(), List.of("work.txt")));
            assertTrue(prompt.contains("__ciop__/PATCH_FAILURE.txt"));
            try {
                assertEquals(before, Files.readString(directory.resolve("custom.json")));
                assertEquals("one\ntwo\n", Files.readString(directory.resolve("work.txt")));
            } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            return batch(replaceFirstLine("work.txt", "one\ntwo\n", "one", "changed"),
                    change(List.of(), List.of("work.txt")));
        });
        run(context, provider);
        assertEquals(2, provider.calls());
        assertTrue(saved().files().isEmpty());
        assertEquals("changed\ntwo\n", Files.readString(directory.resolve("work.txt")));
    }

    @Test void finalCleanupDoesNotConsumeFollowUpBudget() throws Exception {
        RequestContext context = setup();
        int[] calls = {0};
        var provider = new CapturingLLMProvider(prompt -> calls[0]++ < 5
                ? batch(object("op", "list_files")) : batch(change(List.of(), List.of("work.txt"))));
        run(context, provider);
        assertEquals(6, provider.calls());
        assertTrue(saved().files().isEmpty());
    }
}
