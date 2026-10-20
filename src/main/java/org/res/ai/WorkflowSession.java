package org.res.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** The workflow supplied to the model; shared-document persistence belongs to the store. */
final class WorkflowSession {
    static final String DOCUMENT_PATH = "__ciop__/WORKFLOW.json";
    final ConfigurationStore store;
    final Path target;
    final JsonObject graph;
    private final String transaction = UUID.randomUUID().toString();

    static JsonObject effectiveGraph(JsonObject graph) {
        if (graph != null) return Workflow.validate(graph);
        JsonObject empty = new JsonObject();
        empty.addProperty("version", 1);
        empty.addProperty("revision", 0);
        empty.add("focus", new JsonArray());
        empty.add("nodes", new JsonArray());
        empty.add("edges", new JsonArray());
        return empty;
    }

    private WorkflowSession(RequestContext context) throws IOException {
        store = context.store;
        if (store == null) throw new IllegalArgumentException("Missing configuration store");
        store.verify();
        target = store.target;
        graph = store.graph();
    }

    static void validateSelection(Path directory, RequestContext context, List<ContextFile> files) {
        if (context.workflow() != null && context.sourceFilename() == null)
            throw new IllegalArgumentException("Workflow tracking requires a configuration loaded from disk");
    }

    static WorkflowSession capture(Path directory, RequestContext context, List<ContextFile> files) throws IOException {
        validateSelection(directory, context, files);
        return context.sourceFilename() == null ? null : new WorkflowSession(context);
    }

    byte[] promptDocument() {
        JsonObject result = new JsonObject();
        result.addProperty("configuration", store.filename);
        result.add("workflow", graph.deepCopy());
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    boolean matches(Path file) throws IOException { return WorkingDirectoryPaths.sameFile(target, file); }

    void verify() {
        long supplied = graph.get("revision").getAsLong();
        long current = store.graph().get("revision").getAsLong();
        if (supplied != current)
            throw new WorkflowConflictException("Configuration " + store.filename
                    + ": workflow changed after prompt preparation; supplied revision " + supplied
                    + ", current revision " + current);
    }

    Workflow.Result update(JsonObject operation) {
        long expected = ConfigurationStore.counter(operation, "expected_revision", false);
        long supplied = graph.get("revision").getAsLong();
        if (expected != supplied)
            throw new WorkflowConflictException("Configuration " + store.filename
                    + ": response expected workflow revision " + expected
                    + ", but the prompt supplied " + supplied
                    + " and the current revision is " + store.graph().get("revision").getAsLong());
        verify();
        return Workflow.update(graph, operation);
    }

    JsonObject record(Workflow.Result result) {
        JsonObject record = new JsonObject();
        record.addProperty("transaction", transaction);
        record.addProperty("configuration", store.filename);
        record.addProperty("archive", result.archived());
        record.addProperty("summary", result.report());
        record.add("before", graph.deepCopy());
        record.add("after", result.graph().deepCopy());
        return record;
    }
}
