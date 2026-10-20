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

class WorkflowTest {
    private static JsonObject node(String id, String status) {
        return object("id", id, "text", "Task " + id, "status", status, "owner", "assistant");
    }
    private static JsonObject graph(JsonObject... nodes) {
        return object("version", 1, "revision", 0, "focus", List.of(), "nodes", lines(nodes), "edges", List.of());
    }
    private static JsonObject update(long revision, JsonObject... actions) {
        return object("op", "workflow_update", "expected_revision", revision, "actions", lines(actions));
    }
    private static JsonObject edit(String id, String status) {
        return object("action", "edit_node", "id", id, "set", object("status", status));
    }
    private static JsonObject edge(String from, String to) {
        return object("action", "add_edge", "from", from, "to", to, "kind", "requires");
    }
    private static RequestContext setup(Path directory, JsonObject graph) throws Exception {
        Files.writeString(directory.resolve("work.txt"), "original");
        JsonObject config = object("contexts", List.of("custom.json"), "permissions",
                lines(read("custom.json"), readWrite("work.txt")), "workflow", graph);
        Files.writeString(directory.resolve("custom.json"), config.toString());
        return RequestContext.load(directory, "custom.json");
    }
    private static JsonObject saved(Path directory) throws Exception {
        return RequestContext.load(directory, "custom.json").workflow();
    }
    private static String history(Path directory) throws IOException {
        StringBuilder result = new StringBuilder();
        try (var files = Files.list(directory.resolve("conversation_history"))) {
            for (Path file : files.sorted().toList()) result.append(Files.readString(file));
        }
        return result.toString();
    }

    @Test void interruptionAndUserConfirmation() {
        JsonObject initial = graph(node("implement", "active"), node("test", "pending"), node("repair", "pending"));
        JsonObject current = Workflow.update(initial, update(0, edge("implement", "test"),
                object("action", "edit_node", "id", "test", "set", object("owner", "user")),
                object("action", "set_focus", "ids", List.of("implement")))).graph();
        assertFalse(Workflow.ready(current, "test"));
        current = Workflow.update(current, update(1, edit("implement", "done"))).graph();
        assertTrue(Workflow.ready(current, "test"));
        current = Workflow.update(current, update(2, edit("test", "blocked"), edit("repair", "active"),
                edge("repair", "test"), object("action", "set_focus", "ids", List.of("repair")))).graph();
        assertFalse(Workflow.ready(current, "test"));
        current = Workflow.update(current, update(3, edit("repair", "done"), edit("test", "pending"))).graph();
        assertTrue(Workflow.ready(current, "test"));
        assertEquals(4, current.get("revision").getAsInt());
        assertEquals("active", initial.getAsJsonArray("nodes").get(0).getAsJsonObject().get("status").getAsString());
        assertTrue(Workflow.render(current).contains("user; ready"));
        assertEquals(current, Workflow.update(current, update(4, edit("test", "pending"))).graph());
        JsonObject finalCurrent = current;
        assertThrows(IllegalArgumentException.class, () -> Workflow.update(finalCurrent, update(3)));
    }

    @Test void decisionsReadinessAndStructuralFailures() {
        JsonObject decision = node("decision", "pending");
        decision.add("alternatives", lines());
        decision.getAsJsonArray("alternatives").add("a");
        decision.getAsJsonArray("alternatives").add("b");
        decision.add("selected", com.google.gson.JsonNull.INSTANCE);
        JsonObject child = node("child", "pending");
        child.addProperty("parent", "b");
        JsonObject initial = graph(decision, node("a", "pending"), node("b", "pending"), child);
        Workflow.validate(initial);
        assertFalse(Workflow.ready(initial, "a"));
        JsonObject selected = Workflow.update(initial, update(0,
                object("action", "select", "id", "decision", "selected", "a"))).graph();
        assertTrue(Workflow.ready(selected, "a"));
        assertFalse(Workflow.ready(selected, "child"));
        for (JsonObject request : List.of(
                update(0, edge("a", "b"), edge("b", "a")),
                update(0, edge("a", "missing")),
                update(0, edge("a", "b"), edge("a", "b")),
                update(0, object("action", "remove_node", "id", "a")),
                update(0, object("action", "select", "id", "decision", "selected", "child")),
                update(0, object("action", "edit_node", "id", "b", "set", object("parent", "child"))),
                update(0, object("action", "edit_node", "id", "a", "set", object("status", "unknown"))),
                update(0, object("action", "edit_node", "id", "a", "set", object("id", "renamed"))),
                update(0, object("action", "add_node", "node", node("a", "pending")))))
            assertThrows(IllegalArgumentException.class, () -> Workflow.update(initial, request));
        JsonObject cancelled = graph(node("a", "cancelled"), node("b", "pending"));
        cancelled = Workflow.update(cancelled, update(0, edge("a", "b"))).graph();
        assertFalse(Workflow.ready(cancelled, "b"));
        for (String number : List.of("-1", "1.0", "1e0", "9223372036854775808")) {
            JsonObject invalid = graph();
            invalid.add("revision", StrictJson.parse(number));
            assertThrows(IllegalArgumentException.class, () -> Workflow.validate(invalid));
        }
        assertThrows(RuntimeException.class, () -> StrictJson.parse("{\"revision\":0,\"revision\":1}"));
    }

    @Test void mixedBatchPreservesPolicyAndPrintsNotice(@TempDir Path directory) throws Exception {
        RequestContext context = setup(directory, graph(node("a", "active")));
        var provider = new CapturingLLMProvider(batch(fileWrite("work.txt", "changed"), update(0, edit("a", "done")), stdout("visible")));
        var output = new ByteArrayOutputStream();
        try (var app = new AICommandApplication(provider, output, directory)) { app.run(new byte[0], List.of(), context); }
        assertEquals("changed", Files.readString(directory.resolve("work.txt")));
        assertEquals(1, saved(directory).get("revision").getAsInt());
        assertEquals(context.permissionsDocument(), RequestContext.load(directory, "custom.json").permissionsDocument());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\u001b[32;4m"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("active -> done"));
        assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("visible"));
        var noopOutput = new ByteArrayOutputStream();
        try (var app = new AICommandApplication(new CapturingLLMProvider(batch(update(1, edit("a", "done")))), noopOutput, directory)) {
            app.run(new byte[0], List.of(), RequestContext.load(directory, "custom.json"));
        }
        assertEquals(0, noopOutput.size());
        assertEquals(1, saved(directory).get("revision").getAsInt());
    }

    @Test void rejectedBatchesAndAliasesLeaveFilesUntouched(@TempDir Path directory) throws Exception {
        RequestContext context = setup(directory, graph(node("a", "active")));
        Files.createLink(directory.resolve("alias.json"), directory.resolve("custom.json"));
        String original = Files.readString(directory.resolve("custom.json"));
        for (String response : List.of(
                batch(update(9, edit("a", "done")), fileWrite("work.txt", "hidden")),
                batch(update(0, edit("a", "done")), fileWrite("denied.txt", "hidden")),
                batch(update(0, edit("a", "done")), fileWrite("custom.json", "hidden")),
                batch(update(0, edit("a", "done")), fileWrite("alias.json", "hidden")),
                batch(update(0), update(0)),
                batch(update(0), object("op", "list_files")),
                batch(update(0), object("op", "change_contexts", "add", List.of(), "remove", List.of())))) {
            var output = new ByteArrayOutputStream();
            try (var app = new AICommandApplication(new CapturingLLMProvider(response), output, directory)) {
                app.setAutomaticRetry(false);
                assertThrows(IllegalArgumentException.class, () -> app.run(new byte[0], List.of(), context));
            }
            assertEquals(original, Files.readString(directory.resolve("custom.json")));
            assertEquals("original", Files.readString(directory.resolve("work.txt")));
            assertEquals(0, output.size());
        }
    }

    @Test void rollbackIncludesWorkflowAndOutputFailuresDoNotRollback(@TempDir Path directory) throws Exception {
        RequestContext context = setup(directory, graph(node("a", "active")));
        String original = Files.readString(directory.resolve("custom.json"));
        String response = batch(update(0, edit("a", "done")), fileWrite("work.txt", "changed"), stdout("visible"));
        var output = new ByteArrayOutputStream();
        try (var app = new AICommandApplication(new CapturingLLMProvider(response), output, directory) {
            @Override protected void commitFile(Path target, byte[] bytes) throws IOException {
                super.commitFile(target, bytes);
                if (target.getFileName().toString().equals("work.txt")) throw new IOException("Injected failure");
            }
        }) {
            assertThrows(IOException.class, () -> app.run(new byte[0], List.of(), context));
        }
        assertEquals(original, Files.readString(directory.resolve("custom.json")));
        assertEquals("original", Files.readString(directory.resolve("work.txt")));
        assertEquals(0, output.size());
        OutputStream broken = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Injected output failure"); }
        };
        try (var app = new AICommandApplication(new CapturingLLMProvider(response), broken, directory)) {
            IOException failure = assertThrows(IOException.class, () -> app.run(new byte[0], List.of(), context));
            assertTrue(failure.getMessage().contains("committed"));
        }
        assertEquals(1, saved(directory).get("revision").getAsInt());
        assertEquals("changed", Files.readString(directory.resolve("work.txt")));
    }

    @Test void concurrentConfigurationChangesAreRejected(@TempDir Path directory) throws Exception {
        RequestContext context = setup(directory, graph(node("a", "active")));
        var provider = new CapturingLLMProvider(prompt -> {
            try { Files.writeString(directory.resolve("custom.json"), Files.readString(directory.resolve("custom.json")) + "\n"); }
            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            return batch(update(0, edit("a", "done")), fileWrite("work.txt", "hidden"));
        });
        var output = new ByteArrayOutputStream();
        try (var app = new AICommandApplication(provider, output, directory)) {
            assertThrows(IOException.class, () -> app.run(new byte[0], List.of(), context));
        }
        assertEquals(0, saved(directory).get("revision").getAsInt());
        assertEquals("original", Files.readString(directory.resolve("work.txt")));
        assertEquals(0, output.size());
    }

    @Test void configurationCanBeRemovedWhileWorkflowRemainsAvailable(@TempDir Path directory) throws Exception {
        RequestContext context = setup(directory, graph(node("a", "active")));
        int[] calls = {0};
        var provider = new CapturingLLMProvider(prompt -> {
            assertTrue(prompt.contains("access=READ path=" + WorkflowSession.DOCUMENT_PATH + "---"));
            if (calls[0]++ == 0) return batch(object("op", "change_contexts",
                    "add", List.of(), "remove", List.of("custom.json")));
            assertFalse(prompt.contains("path=custom.json---"));
            return batch(update(0, edit("a", "done")));
        });
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            app.run(new byte[0], List.of(), context);
        }
        assertEquals(2, provider.calls());
        assertEquals(1, saved(directory).get("revision").getAsInt());
    }

    @Test void contextFollowUpsAndHistoryPreserveWorkflow(@TempDir Path directory) throws Exception {
        JsonObject initial = graph(node("a", "active"));
        setup(directory, initial);
        Files.createDirectory(directory.resolve("conversation_history"));
        int[] calls = {0};
        var provider = new CapturingLLMProvider(prompt -> calls[0]++ == 0
                ? batch(object("op", "change_contexts", "add", List.of("work.txt"), "remove", List.of()))
                : batch(update(0, edit("a", "done"))));
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            app.runWithHistory(new byte[0], List.of(), "custom.json");
        }
        assertEquals(2, provider.calls());
        assertEquals(1, saved(directory).get("revision").getAsInt());
        assertTrue(history(directory).contains(" | workflow_prepared | "));
        assertTrue(history(directory).contains(" | workflow_committed | "));
        assertFalse(history(directory).contains("\u001b[32;4m"));
    }

    @Test void archivalRequiresHistoryAndKeepsDisconnectedWork(@TempDir Path directory) throws Exception {
        JsonObject initial = graph(node("done", "done"), node("ongoing", "active"));
        RequestContext context = setup(directory, initial);
        String response = batch(update(0, object("action", "archive_component", "id", "done")));
        try (var app = new AICommandApplication(new CapturingLLMProvider(response), new ByteArrayOutputStream(), directory)) {
            assertThrows(IllegalArgumentException.class, () -> app.run(new byte[0], List.of(), context));
        }
        assertEquals(initial, saved(directory));
        Files.createDirectory(directory.resolve("conversation_history"));
        try (var app = new AICommandApplication(new CapturingLLMProvider(response), new ByteArrayOutputStream(), directory)) {
            app.runWithHistory(new byte[0], List.of(), "custom.json");
        }
        assertEquals(1, saved(directory).getAsJsonArray("nodes").size());
        assertEquals("ongoing", saved(directory).getAsJsonArray("nodes").get(0).getAsJsonObject().get("id").getAsString());
        String savedHistory = history(directory);
        assertTrue(savedHistory.contains("# workflow: Removed task \"done\""));
        assertTrue(savedHistory.contains("# workflow:   text: \"Task done\""));
        assertTrue(savedHistory.contains("# workflow:   status: \"done\""));
        assertFalse(savedHistory.contains("\"before\":"));
        assertFalse(savedHistory.contains("\"after\":"));
        assertTrue(savedHistory.contains(" | workflow_committed | "));
        assertThrows(IllegalArgumentException.class, () -> Workflow.update(initial,
                update(0, object("action", "archive_component", "id", "ongoing"))));
    }

    @Test void failedArchiveCommitRetainsRecoveryRecord(@TempDir Path directory) throws Exception {
        JsonObject initial = graph(node("done", "done"));
        setup(directory, initial);
        Files.createDirectory(directory.resolve("conversation_history"));
        String response = batch(update(0, object("action", "archive_component", "id", "done")), fileWrite("work.txt", "changed"));
        try (var app = new AICommandApplication(new CapturingLLMProvider(response), new ByteArrayOutputStream(), directory) {
            @Override protected void commitFile(Path target, byte[] bytes) throws IOException {
                super.commitFile(target, bytes);
                if (target.getFileName().toString().equals("work.txt")) throw new IOException("Injected failure");
            }
        }) {
            assertThrows(IOException.class, () -> app.runWithHistory(new byte[0], List.of(), "custom.json"));
        }
        assertEquals(initial, saved(directory));
        String record = history(directory);
        assertTrue(record.contains(" | workflow_prepared | "));
        assertFalse(record.contains(" | workflow_committed | "));
    }
}
