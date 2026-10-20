package org.res.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** Readable workflow changes without repeated full graph snapshots. */
final class WorkflowHistoryFormat {
    private WorkflowHistoryFormat() { }

    static String format(String event, JsonObject record) {
        boolean prepared = event.equals("workflow_prepared");
        StringBuilder out = new StringBuilder("# workflow: ")
                .append(prepared ? "Prepared; commit not yet confirmed" : "Committed")
                .append("; configuration ").append(value(record.get("configuration")))
                .append("; transaction ").append(record.get("transaction").getAsString()).append('\n');
        out.append(record.get("summary").getAsString()).append('\n');
        if (!prepared) return out.toString();
        JsonObject before = record.getAsJsonObject("before");
        JsonObject after = record.getAsJsonObject("after");
        Map<String, JsonObject> oldNodes = nodes(before);
        Map<String, JsonObject> newNodes = nodes(after);
        oldNodes.forEach((id, node) -> {
            if (!newNodes.containsKey(id)) {
                out.append("# workflow: Removed task ").append(value(new JsonPrimitive(id))).append('\n');
                node.entrySet().forEach(field -> detail(out, field.getKey(), field.getValue()));
            }
        });
        newNodes.forEach((id, node) -> {
            JsonObject old = oldNodes.get(id);
            if (node.equals(old)) return;
            out.append("# workflow: ").append(old == null ? "Added task " : "Updated task ")
                    .append(value(new JsonPrimitive(id))).append('\n');
            node.entrySet().forEach(field -> {
                if (old == null || !field.getValue().equals(old.get(field.getKey())))
                    detail(out, field.getKey(), field.getValue());
            });
            if (old != null) old.keySet().stream().filter(key -> !node.has(key))
                    .forEach(key -> out.append("# workflow:   ").append(key).append(": cleared\n"));
        });
        for (JsonElement edge : before.getAsJsonArray("edges"))
            if (!after.getAsJsonArray("edges").contains(edge)) edge(out, "Removed", edge.getAsJsonObject());
        for (JsonElement edge : after.getAsJsonArray("edges"))
            if (!before.getAsJsonArray("edges").contains(edge)) edge(out, "Added", edge.getAsJsonObject());
        if (!before.get("focus").equals(after.get("focus"))) detail(out, "focus", after.get("focus"));
        return out.toString();
    }

    private static Map<String, JsonObject> nodes(JsonObject graph) {
        Map<String, JsonObject> nodes = new LinkedHashMap<>();
        graph.getAsJsonArray("nodes").forEach(element -> {
            JsonObject node = element.getAsJsonObject();
            nodes.put(node.get("id").getAsString(), node);
        });
        return nodes;
    }

    private static void detail(StringBuilder out, String field, JsonElement value) {
        out.append("# workflow:   ").append(field).append(": ").append(value(value)).append('\n');
    }

    private static void edge(StringBuilder out, String action, JsonObject edge) {
        out.append("# workflow: ").append(action).append(" dependency: ")
                .append(value(edge.get("from"))).append(" must finish before ")
                .append(value(edge.get("to"))).append('\n');
    }

    private static String value(JsonElement value) {
        if (value.isJsonNull()) return "none";
        if (value.isJsonArray()) return value.getAsJsonArray().isEmpty() ? "none"
                : StreamSupport.stream(value.getAsJsonArray().spliterator(), false)
                        .map(WorkflowHistoryFormat::value).collect(Collectors.joining(", "));
        // Quote strings to preserve embedded newlines and escape terminal controls.
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? new JsonPrimitive(value.getAsString()).toString() : value.getAsString();
    }
}
