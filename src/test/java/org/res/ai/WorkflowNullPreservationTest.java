package org.res.ai;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.res.ai.TestJson.*;

class WorkflowNullPreservationTest {
    private static JsonObject graph() {
        return StrictJson.parse("""
                {"version":1,"revision":0,"focus":[],"nodes":[
                  {"id":"choice","text":"Choose","status":"pending","owner":"user",
                   "alternatives":["a"],"selected":null},
                  {"id":"a","text":"Option A","status":"pending","owner":"assistant"}
                ],"edges":[]}
                """).getAsJsonObject();
    }

    private static RequestContext setup(Path directory) throws Exception {
        JsonObject config = StrictJson.parse("""
                {"contexts":["custom.json"],"permissions":[
                  {"custom.json":"READ"},{"extra.txt":"READ"}
                ]}
                """).getAsJsonObject();
        config.add("workflow", graph());
        Files.writeString(directory.resolve("custom.json"), config.toString());
        Files.writeString(directory.resolve("extra.txt"), "reference");
        return RequestContext.load(directory, "custom.json");
    }

    @Test void testBuilderPreservesNestedNulls() {
        JsonObject original = graph();
        JsonObject copied = object("workflow", original).getAsJsonObject("workflow");
        assertEquals(original, copied);
        assertDoesNotThrow(() -> Workflow.validate(copied));
        JsonObject cleared = Workflow.update(original, object("op", "workflow_update",
                "expected_revision", 0, "actions", lines(
                        object("action", "select", "id", "choice", "selected", "a"),
                        object("action", "select", "id", "choice", "selected", null)))).graph();
        assertEquals(original, cleared);
    }

    @Test void contextChangesPreserveUnresolvedDecisions(@TempDir Path directory) throws Exception {
        RequestContext context = setup(directory);
        ChangeContexts.apply(directory, context, List.of(),
                object("op", "change_contexts", "add", List.of("extra.txt"),
                        "remove", List.of()).toString());
        RequestContext saved = RequestContext.load(directory, "custom.json");
        assertEquals(graph(), saved.workflow());
        assertEquals(List.of("custom.json", "extra.txt"),
                saved.files().stream().map(ContextFile::path).toList());
    }

    @Test void historyRegistrationPreservesUnresolvedDecisions(@TempDir Path directory) throws Exception {
        setup(directory);
        Files.createDirectory(directory.resolve(ConversationHistory.DIRECTORY));
        new ConversationHistory(directory, "custom.json").input(new byte[0]);
        RequestContext saved = RequestContext.load(directory, "custom.json");
        assertEquals(graph(), saved.workflow());
        assertTrue(saved.files().stream().anyMatch(file ->
                file.path().startsWith(ConversationHistory.DIRECTORY + "/")
                        && file.access() == FileAccess.READ));
    }
}
