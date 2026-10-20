package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.res.ai.TestJson.*;

class AutomaticWorkflowTest {
    @TempDir Path directory;

    private static String addTask() {
        return batch(object("op", "workflow_update", "expected_revision", 0,
                "actions", lines(object("action", "add_node", "node",
                        object("id", "task", "text", "Run tests", "status", "pending", "owner", "user")))));
    }

    @Test void automaticallyTracksDefaultAndCustomConfigurationsWithoutPermissionChanges() throws Exception {
        for (String filename : List.of("context.json", "custom.json")) {
            Path work = Files.createDirectory(directory.resolve(filename));
            String original = "{\"contexts\":[],\"permissions\":[]}\n";
            Files.writeString(work.resolve(filename), original);
            var provider = new CapturingLLMProvider(prompt -> {
                assertTrue(prompt.contains("access=READ path=" + WorkflowSession.DOCUMENT_PATH + "---"));
                assertTrue(prompt.contains("\"revision\":0"));
                assertFalse(prompt.contains("path=" + filename + "---"));
                return addTask();
            });
            try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), work)) {
                app.runWithHistory(new byte[0], List.of(), filename, true);
            }
            RequestContext saved = RequestContext.load(work, filename);
            assertEquals(1, saved.workflow().get("revision").getAsInt());
            assertTrue(saved.files().isEmpty());
            assertTrue(saved.permissionsDocument().getAsJsonArray("permissions").isEmpty());
            assertFalse(saved.canWrite(work, "other.txt"));
            var next = new CapturingLLMProvider(prompt -> {
                assertTrue(prompt.contains("\"revision\":1"));
                assertTrue(prompt.contains("\"id\":\"task\""));
                return "[]";
            });
            try (var app = new AICommandApplication(next, new ByteArrayOutputStream(), work)) {
                app.runWithHistory(new byte[0], List.of(), filename, true);
            }
        }
    }

    @Test void noOpDoesNotRewriteConfigurationAndStatusHasAnEmptyGraph() throws Exception {
        String original = "{\"contexts\":[],\"permissions\":[]}\n";
        Files.writeString(directory.resolve("context.json"), original);
        var provider = new CapturingLLMProvider(batch(object("op", "workflow_update",
                "expected_revision", 0, "actions", List.of())));
        var output = new ByteArrayOutputStream();
        try (var app = new AICommandApplication(provider, output, directory)) {
            app.runWithHistory(new byte[0], List.of(), null, true);
        }
        assertEquals(original, Files.readString(directory.resolve("context.json")));
        assertEquals(0, output.size());
        assertTrue(Workflow.render(WorkflowSession.effectiveGraph(null)).contains("Workflow revision 0"));
    }

    @Test void missingAndIgnoredContextsDoNotEnableTracking() throws Exception {
        for (boolean ignored : List.of(false, true)) {
            if (ignored) Files.writeString(directory.resolve("context.json"), "not JSON");
            var provider = new CapturingLLMProvider(prompt -> {
                assertFalse(prompt.contains("path=" + WorkflowSession.DOCUMENT_PATH + "---"));
                return addTask();
            });
            try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
                assertThrows(IllegalArgumentException.class,
                        () -> app.runWithHistory(new byte[0], List.of(), null, true, ignored));
            }
            if (ignored) assertEquals("not JSON", Files.readString(directory.resolve("context.json")));
            else assertFalse(Files.exists(directory.resolve("context.json")));
        }
    }

    @Test void subsetOfConfigurationStillSuppliesCompleteGraph() throws Exception {
        var config = object("contexts", lines(object("path", "context.json", "bytes", List.of(List.of(0, 1)))),
                "permissions", lines(read("context.json")));
        Files.writeString(directory.resolve("context.json"), config.toString());
        var provider = new CapturingLLMProvider(prompt -> {
            assertTrue(prompt.contains("---SECTION FILE parts-utf-8"));
            assertTrue(prompt.contains("access=READ path=" + WorkflowSession.DOCUMENT_PATH + "---"));
            return addTask();
        });
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            app.runWithHistory(new byte[0], List.of(), null, true);
        }
        assertEquals(1, RequestContext.load(directory, "context.json").workflow().get("revision").getAsInt());
    }

    @Test void firstUpdateRejectsConcurrentConfigurationChanges() throws Exception {
        Path config = directory.resolve("context.json");
        String original = "{\"contexts\":[],\"permissions\":[]}";
        Files.writeString(config, original);
        var provider = new CapturingLLMProvider(prompt -> {
            try { Files.writeString(config, original + "\n"); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            return addTask();
        });
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            assertThrows(java.io.IOException.class,
                    () -> app.runWithHistory(new byte[0], List.of(), null, true));
        }
        assertEquals(original + "\n", Files.readString(config));
    }
}
