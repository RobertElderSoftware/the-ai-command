package org.res.ai;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.res.ai.TestJson.*;

class WorkflowHistoryFormatTest {
    @Test void readableChangesPreserveDetailsAndDistinguishCommit() {
        var node = object("id", "task", "text", "Test café\nnext", "status", "done",
                "owner", "user", "completion_note", "Tests passed");
        var before = object("version", 1, "revision", 0, "focus", List.of("task"),
                "nodes", lines(node), "edges", List.of());
        var operation = object("op", "workflow_update", "expected_revision", 0,
                "actions", lines(object("action", "archive_component", "id", "task")));
        var result = Workflow.update(before, operation);
        var record = object("transaction", "test-transaction", "configuration", "custom.json",
                "archive", true, "summary", result.report(), "before", before, "after", result.graph());
        String prepared = WorkflowHistoryFormat.format("workflow_prepared", record);
        String committed = WorkflowHistoryFormat.format("workflow_committed", record);
        assertTrue(prepared.contains("Prepared; commit not yet confirmed"));
        assertTrue(prepared.contains("Removed task \"task\""));
        assertTrue(prepared.contains("Test café\\nnext"));
        assertTrue(prepared.contains("completion_note: \"Tests passed\""));
        assertTrue(committed.contains("# workflow: Committed;"));
        assertTrue(committed.contains(result.report()));
        assertFalse(committed.contains("completion_note:"));
        for (String text : List.of(prepared, committed)) {
            assertTrue(text.contains("test-transaction"));
            assertFalse(text.contains("\"before\":"));
            assertFalse(text.contains("\"after\":"));
            assertFalse(text.contains("\u001b"));
            assertTrue(text.lines().allMatch(line -> line.startsWith("# workflow: ")));
        }
    }
}
