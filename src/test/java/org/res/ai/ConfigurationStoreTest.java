package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.res.ai.TestJson.*;

class ConfigurationStoreTest {
    @TempDir Path directory;

    private RequestContext setup() throws Exception {
        Files.writeString(directory.resolve("work.txt"), "original\n");
        Files.writeString(directory.resolve("context.json"), contextJson(readWrite("work.txt")));
        Files.createDirectory(directory.resolve("conversation_history"));
        return RequestContext.load(directory, "context.json");
    }

    private static com.google.gson.JsonObject update(long revision) {
        return object("op", "workflow_update", "expected_revision", revision, "actions",
                lines(object("action", "add_node", "node",
                        object("id", "task", "text", "Task", "status", "done", "owner", "assistant"))));
    }

    private long revision() throws Exception {
        return ConfigurationStore.counter(StrictJson.parse(Files.readString(
                directory.resolve("context.json"))).getAsJsonObject(), "revision", true);
    }

    @Test void historyDuringGenerationDoesNotInvalidateWorkflowOrLoseSelections() throws Exception {
        RequestContext context = setup();
        var provider = new CapturingLLMProvider(prompt -> {
            try {
                Files.writeString(directory.resolve("conversation_history/next.txt"), "history");
                context.store.registerHistory("conversation_history/next.txt");
            } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            return batch(update(0));
        });
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            app.run(new byte[0], List.of(), context);
        }
        assertEquals(1, provider.calls());
        assertEquals(2, revision());
        assertEquals(1, context.store.graph().get("revision").getAsLong());
        assertTrue(context.store.context().files().stream()
                .anyMatch(file -> file.path().equals("conversation_history/next.txt")));
    }

    @Test void queuedRegistrationUsesCommittedStateAndNoopsPreserveBytes() throws Exception {
        ConfigurationStore store = setup().store;
        String history = "conversation_history/next.txt";
        Files.writeString(directory.resolve(history), "history");
        byte[] before = store.bytes();
        store.beginBatch();
        store.registerHistory(history);
        assertArrayEquals(before, Files.readAllBytes(store.target));
        var graph = Workflow.update(store.graph(), update(0)).graph();
        store.commit(store.replacement(graph, null));
        store.finishBatch();
        assertEquals(2, revision());
        assertEquals(graph, store.graph());
        byte[] after = store.bytes();
        store.registerHistory(history);
        store.commit(store.replacement(graph, null));
        assertArrayEquals(after, store.bytes());
        assertArrayEquals(after, Files.readAllBytes(store.target));
    }

    @Test void failedBatchDoesNotPublishStagedStoreState() throws Exception {
        RequestContext context = setup();
        byte[] original = context.store.bytes();
        var provider = new CapturingLLMProvider(batch(fileWrite("work.txt", "changed"), update(0)));
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory) {
            @Override protected void commitFile(Path target, byte[] bytes) throws IOException {
                super.commitFile(target, bytes);
                if (target.getFileName().toString().equals("context.json")) throw new IOException("injected");
            }
        }) {
            assertThrows(IOException.class, () -> app.run(new byte[0], List.of(), context));
        }
        assertArrayEquals(original, context.store.bytes());
        assertArrayEquals(original, Files.readAllBytes(context.store.target));
        assertEquals("original\n", Files.readString(directory.resolve("work.txt")));
        assertEquals(1, provider.calls());
        context.store.verify();
    }

    @Test void workflowRecoveryKeepsInputAndHasAnInvocationWideLimit() throws Exception {
        RequestContext context = setup();
        byte[] input = "Keep inline ---SECTION STDIN text and café".getBytes(StandardCharsets.UTF_8);
        int[] calls = {0};
        var provider = new CapturingLLMProvider(prompt -> {
            if (calls[0]++ == 0) return batch(update(99), fileWrite("work.txt", "discarded"));
            assertTrue(prompt.contains("__ciop__/WORKFLOW_FAILURE.txt"));
            assertTrue(prompt.contains(new String(input, StandardCharsets.UTF_8)));
            return batch(update(0));
        });
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            app.run(input, List.of(), context);
        }
        assertEquals(2, provider.calls());
        assertEquals("original\n", Files.readString(directory.resolve("work.txt")));
        for (boolean retry : List.of(false, true)) {
            var bad = new CapturingLLMProvider(batch(update(99)));
            try (var app = new AICommandApplication(bad, new ByteArrayOutputStream(), directory)) {
                app.setAutomaticRetry(retry);
                assertThrows(WorkflowConflictException.class,
                        () -> app.run(input, List.of(), context.store.context()));
            }
            assertEquals(retry ? 3 : 1, bad.calls());
        }
    }

    @Test void realWorkflowChangeRecapturesCurrentGraph() throws Exception {
        RequestContext context = setup();
        int[] calls = {0};
        var provider = new CapturingLLMProvider(prompt -> {
            if (calls[0]++ == 0) {
                try { context.store.commit(context.store.replacement(
                        Workflow.update(context.store.graph(), update(0)).graph(), null)); }
                catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                return batch(object("op", "workflow_update", "expected_revision", 0, "actions", List.of()));
            }
            assertTrue(prompt.contains("workflow changed after prompt preparation"));
            return batch(object("op", "workflow_update", "expected_revision", 1, "actions", List.of()));
        });
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            app.run(new byte[0], List.of(), context);
        }
        assertEquals(2, provider.calls());
        assertEquals(1, revision());
    }

    @Test void countersRejectInvalidValuesAndOverflowWithoutWriting() throws Exception {
        RequestContext context = setup();
        String original = Files.readString(context.store.target);
        for (String value : List.of("-1", "1.0", "1e0", "null", "\"1\"", "9223372036854775808")) {
            var doc = StrictJson.parse(original).getAsJsonObject();
            doc.add("revision", StrictJson.parse(value));
            assertThrows(IllegalArgumentException.class, () -> RequestContext.parse(directory,
                    "context.json", doc.toString().getBytes(StandardCharsets.UTF_8)));
        }
        var doc = StrictJson.parse(original).getAsJsonObject();
        doc.addProperty("revision", Long.MAX_VALUE);
        Files.writeString(context.store.target, doc.toString());
        ConfigurationStore exhausted = RequestContext.load(directory, "context.json").store;
        assertArrayEquals(exhausted.bytes(), exhausted.replacement(null, null));
        assertThrows(IllegalArgumentException.class, () -> exhausted.replacement(
                Workflow.update(exhausted.graph(), update(0)).graph(), null));
        assertEquals(doc.toString(), Files.readString(exhausted.target));
    }

    @Test void patchCorrectionDoesNotSearchInlineStdinMarkers() throws Exception {
        RequestContext context = setup();
        int[] calls = {0};
        String input = "inline ---SECTION STDIN marker";
        var provider = new CapturingLLMProvider(prompt -> {
            if (calls[0]++ == 0)
                return batch(replaceFirstLine("work.txt", "original\n", "wrong", "fixed"));
            int diagnostic = prompt.indexOf("path=__ciop__/PATCH_FAILURE.txt---");
            int header = prompt.indexOf("\n---SECTION STDIN ");
            assertTrue(diagnostic > 0 && diagnostic < header);
            assertTrue(prompt.contains(input));
            return batch(fileWrite("work.txt", "fixed"));
        });
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            app.run(input.getBytes(StandardCharsets.UTF_8), List.of(), context);
        }
        assertEquals(2, provider.calls());
    }
}
