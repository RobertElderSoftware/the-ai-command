package org.res.ai;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Invocation-owned configuration. Assumes a single application instance and no external edits. */
final class ConfigurationStore {
    private final Path directory;
    private final WorkingDirectoryPaths paths;
    final String filename;
    final Path target;
    private byte[] saved;
    private JsonObject document;
    private boolean batching;
    private boolean historySuppressed;
    private final Set<String> pendingHistory = new LinkedHashSet<>();

    ConfigurationStore(Path directory, String filename) throws IOException {
        this.directory = WorkingDirectoryPaths.canonical(directory);
        paths = new WorkingDirectoryPaths(this.directory);
        this.filename = filename;
        target = paths.contextFile(new ContextFile(filename, FileAccess.READ));
        if (target.startsWith(paths.resolve(ConversationHistory.DIRECTORY)))
            throw new IllegalArgumentException("Configuration must be outside conversation_history");
        saved = WorkingDirectoryPaths.read(target);
        RequestContext.parse(this.directory, filename, saved);
        document = StrictJson.parse(Encoding.strictUtf8(saved)).getAsJsonObject();
    }

    static long counter(JsonObject object, String name, boolean optional) {
        var value = object.get(name);
        if (value == null && optional) return 0;
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("0|[1-9][0-9]*"))
            throw new IllegalArgumentException(name + " must be a nonnegative JSON integer");
        try { return Long.parseLong(value.getAsString()); }
        catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " exceeds signed 64-bit bounds", failure);
        }
    }

    RequestContext context() throws IOException {
        RequestContext result = RequestContext.parse(directory, filename, saved);
        result.store = this;
        return result;
    }

    JsonObject graph() {
        return WorkflowSession.effectiveGraph(document.has("workflow")
                ? Workflow.validate(document.get("workflow")) : null);
    }

    byte[] bytes() { return saved.clone(); }

    /** External edits are unsupported; fail clearly rather than overwrite one if detected. */
    void verify() throws IOException {
        paths.contextFile(new ContextFile(filename, FileAccess.READ));
        if (!Arrays.equals(saved, WorkingDirectoryPaths.read(target)))
            throw new IOException("Configuration " + filename
                    + " changed outside its owning store. Use one application instance and do not edit it during a request.");
    }

    byte[] replacement(JsonObject graph, List<ContextFile> selections) {
        JsonObject next = document.deepCopy();
        if (graph != null && !graph.equals(graph())) next.add("workflow", graph.deepCopy());
        if (selections != null) {
            JsonArray entries = new JsonArray();
            selections.forEach(file -> entries.add(file.contextEntry()));
            next.add("contexts", entries);
        }
        return encodeChange(next);
    }

    private byte[] encodeChange(JsonObject next) {
        if (next.equals(document)) return bytes();
        long revision = counter(document, "revision", true);
        if (revision == Long.MAX_VALUE) throw new IllegalArgumentException("Configuration revision exhausted");
        next.addProperty("revision", revision + 1);
        return (new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls()
                .create().toJson(next) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Called only after the entire file batch has committed successfully. */
    void accept(byte[] committed) {
        document = StrictJson.parse(Encoding.strictUtf8(committed)).getAsJsonObject();
        saved = committed.clone();
    }

    void commit(byte[] replacement) throws IOException {
        verify();
        if (Arrays.equals(saved, replacement)) return;
        AtomicFileReplacement.replace(target, replacement, ".ciop-config-", true, this::verify);
        accept(replacement);
    }

    void beginBatch() throws IOException {
        if (batching) throw new IllegalStateException("Nested configuration transaction");
        flushHistory();
        verify();
        batching = true;
    }

    /** On failure keep queued registrations for the next history event, without masking the failure. */
    void endBatch() { batching = false; }

    void finishBatch() throws IOException {
        batching = false;
        flushHistory();
    }

    void suppressHistory() {
        historySuppressed = true;
        pendingHistory.clear();
    }

    void registerHistory(String filename) throws IOException {
        if (historySuppressed) return;
        pendingHistory.add(filename);
        if (!batching) flushHistory();
    }

    private void flushHistory() throws IOException {
        if (historySuppressed || batching) return;
        while (!pendingHistory.isEmpty()) {
            String history = pendingHistory.iterator().next();
            JsonObject registration = context().withHistory(paths, history);
            JsonObject next = document.deepCopy();
            next.add("contexts", registration.get("contexts").deepCopy());
            next.add("permissions", registration.get("permissions").deepCopy());
            commit(encodeChange(next));
            pendingHistory.remove(history);
        }
    }
}
