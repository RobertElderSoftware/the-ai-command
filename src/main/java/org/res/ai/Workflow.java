package org.res.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure validation, mutation and presentation of persistent task graphs. */
final class Workflow {
    private static final Set<String> STATUSES = Set.of("pending", "active", "blocked", "done", "skipped", "cancelled");
    private static final Set<String> OWNERS = Set.of("assistant", "user", "external", "unassigned");
    private static final Set<String> TERMINAL = Set.of("done", "skipped", "cancelled");
    private static final Set<String> NODE_FIELDS = Set.of("id", "text", "status", "owner", "parent", "blocked_reason", "completion_note", "alternatives", "selected");
    record Result(JsonObject graph, String report, boolean archived) { }
    private Workflow() { }

    static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw invalid("Expected an object");
        return value.getAsJsonObject();
    }

    static JsonArray array(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) throw invalid(name + " must be an array");
        return value.getAsJsonArray();
    }

    static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw invalid("Expected a string");
        return value.getAsString();
    }

    private static String text(JsonObject object, String name) { return string(object.get(name)); }
    private static String id(JsonElement value) {
        String result = string(value);
        if (result.isBlank()) throw invalid("IDs must be nonblank");
        return result;
    }

    private static long integer(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("0|[1-9][0-9]*")) throw invalid(name + " must be a nonnegative JSON integer");
        try { return Long.parseLong(value.getAsString()); }
        catch (NumberFormatException failure) { throw invalid(name + " exceeds signed 64-bit bounds"); }
    }

    private static void fields(JsonObject object, Set<String> required, Set<String> allowed) {
        if (!object.keySet().containsAll(required) || !allowed.containsAll(object.keySet()))
            throw invalid("Missing or unknown fields: " + object.keySet());
    }

    private static void exact(JsonObject object, String... names) {
        fields(object, Set.of(names), Set.of(names));
    }

    private static List<String> ids(JsonArray array) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement value : array) {
            String name = id(value);
            if (!seen.add(name)) throw invalid("Duplicate ID: " + name);
            result.add(name);
        }
        return result;
    }

    private static Map<String, JsonObject> nodes(JsonObject graph) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (JsonElement value : array(graph, "nodes")) {
            JsonObject node = object(value);
            String name = id(node.get("id"));
            if (result.putIfAbsent(name, node) != null) throw invalid("Duplicate node: " + name);
        }
        return result;
    }

    static JsonObject validate(JsonElement value) {
        JsonObject graph = object(value);
        exact(graph, "version", "revision", "focus", "nodes", "edges");
        if (integer(graph, "version") != 1) throw invalid("Unsupported workflow version");
        integer(graph, "revision");
        Map<String, JsonObject> nodes = nodes(graph);
        Map<String, Set<String>> dependencies = adjacency(nodes.keySet());
        Map<String, Set<String>> containment = adjacency(nodes.keySet());
        Set<String> candidates = new HashSet<>();
        for (var entry : nodes.entrySet()) {
            String name = entry.getKey();
            JsonObject node = entry.getValue();
            fields(node, Set.of("id", "text", "status", "owner"), NODE_FIELDS);
            text(node, "text");
            if (!STATUSES.contains(text(node, "status"))) throw invalid("Unknown node status");
            if (!OWNERS.contains(text(node, "owner"))) throw invalid("Unknown node owner");
            for (String field : List.of("blocked_reason", "completion_note"))
                if (node.has(field)) text(node, field);
            if (node.has("parent")) {
                String parent = id(node.get("parent"));
                requireNode(nodes, parent);
                containment.get(parent).add(name);
            }
            if (node.has("alternatives") != node.has("selected"))
                throw invalid("Decision nodes require both alternatives and selected");
            if (node.has("alternatives")) {
                List<String> alternatives = ids(array(node, "alternatives"));
                if (alternatives.isEmpty()) throw invalid("Decision alternatives must be nonempty");
                for (String candidate : alternatives) {
                    requireNode(nodes, candidate);
                    if (!candidates.add(candidate)) throw invalid("An alternative may belong to only one decision");
                    containment.get(name).add(candidate);
                }
                if (!node.get("selected").isJsonNull() && !alternatives.contains(id(node.get("selected"))))
                    throw invalid("Selected node is not an alternative");
            }
        }
        for (String name : ids(array(graph, "focus"))) requireNode(nodes, name);
        for (JsonElement valueEdge : array(graph, "edges")) {
            JsonObject edge = object(valueEdge);
            exact(edge, "from", "to", "kind");
            String from = id(edge.get("from"));
            String to = id(edge.get("to"));
            requireNode(nodes, from);
            requireNode(nodes, to);
            if (!text(edge, "kind").equals("requires")) throw invalid("Unknown edge kind");
            if (!dependencies.get(from).add(to)) throw invalid("Duplicate dependency edge");
        }
        acyclic(dependencies, "dependency");
        acyclic(containment, "parent/decision");
        return graph.deepCopy();
    }

    private static Map<String, Set<String>> adjacency(Set<String> names) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        names.forEach(name -> result.put(name, new LinkedHashSet<>()));
        return result;
    }

    private static void requireNode(Map<String, JsonObject> nodes, String name) {
        if (!nodes.containsKey(name)) throw invalid("Unknown node: " + name);
    }

    /** Iterative traversal avoids stack overflow for long valid plans. */
    private static void acyclic(Map<String, Set<String>> edges, String kind) {
        Map<String, Integer> degree = new LinkedHashMap<>();
        edges.keySet().forEach(name -> degree.put(name, 0));
        edges.values().forEach(targets -> targets.forEach(name -> degree.put(name, degree.get(name) + 1)));
        ArrayDeque<String> queue = new ArrayDeque<>();
        degree.forEach((name, count) -> { if (count == 0) queue.add(name); });
        int visited = 0;
        while (!queue.isEmpty()) {
            String name = queue.remove();
            visited++;
            for (String target : edges.get(name)) {
                int count = degree.get(target) - 1;
                degree.put(target, count);
                if (count == 0) queue.add(target);
            }
        }
        if (visited != edges.size()) throw invalid("Cycle in " + kind + " graph");
    }

    static Result update(JsonObject original, JsonObject operation) {
        validate(original);
        exact(operation, "op", "expected_revision", "actions");
        if (!text(operation, "op").equals("workflow_update")) throw invalid("Wrong workflow operation");
        long revision = integer(original, "revision");
        if (integer(operation, "expected_revision") != revision) throw invalid("Stale workflow revision");
        JsonObject result = original.deepCopy();
        Map<String, JsonObject> nodes = nodes(result);
        JsonArray edges = array(result, "edges");
        boolean archived = false;
        for (JsonElement value : array(operation, "actions")) {
            JsonObject action = object(value);
            switch (text(action, "action")) {
                case "add_node" -> {
                    exact(action, "action", "node");
                    JsonObject node = object(action.get("node")).deepCopy();
                    String name = id(node.get("id"));
                    if (nodes.putIfAbsent(name, node) != null) throw invalid("Node already exists: " + name);
                }
                case "edit_node" -> {
                    exact(action, "action", "id", "set");
                    String name = id(action.get("id"));
                    requireNode(nodes, name);
                    JsonObject changes = object(action.get("set"));
                    for (var change : changes.entrySet()) {
                        String field = change.getKey();
                        if (field.equals("id") || !NODE_FIELDS.contains(field)) throw invalid("Unknown or immutable node field: " + field);
                        if (change.getValue().isJsonNull() && !field.equals("selected")) {
                            if (Set.of("text", "status", "owner").contains(field)) throw invalid("Cannot remove required node field");
                            nodes.get(name).remove(field);
                        } else nodes.get(name).add(field, change.getValue().deepCopy());
                    }
                }
                case "remove_node" -> {
                    exact(action, "action", "id");
                    if (nodes.remove(id(action.get("id"))) == null) throw invalid("Cannot remove unknown node");
                }
                case "add_edge", "remove_edge" -> {
                    exact(action, "action", "from", "to", "kind");
                    JsonObject edge = action.deepCopy();
                    edge.remove("action");
                    if (text(action, "action").equals("add_edge")) edges.add(edge);
                    else if (!edges.remove(edge)) throw invalid("Cannot remove unknown edge");
                }
                case "select" -> {
                    exact(action, "action", "id", "selected");
                    String name = id(action.get("id"));
                    requireNode(nodes, name);
                    if (!nodes.get(name).has("alternatives")) throw invalid("Selection requires a decision node");
                    nodes.get(name).add("selected", action.get("selected").deepCopy());
                }
                case "set_focus" -> {
                    exact(action, "action", "ids");
                    ids(array(action, "ids"));
                    result.add("focus", action.get("ids").deepCopy());
                }
                case "archive_component" -> {
                    exact(action, "action", "id");
                    syncNodes(result, nodes);
                    validate(result);
                    String seed = id(action.get("id"));
                    requireNode(nodes, seed);
                    Set<String> component = component(result, seed);
                    for (String name : component)
                        if (!TERMINAL.contains(text(nodes.get(name), "status")))
                            throw invalid("Only completely terminal components may be archived");
                    component.forEach(nodes::remove);
                    for (int index = edges.size() - 1; index >= 0; index--)
                        if (component.contains(text(object(edges.get(index)), "from"))) edges.remove(index);
                    JsonArray focus = array(result, "focus");
                    for (int index = focus.size() - 1; index >= 0; index--)
                        if (component.contains(string(focus.get(index)))) focus.remove(index);
                    archived = true;
                }
                default -> throw invalid("Unknown workflow action");
            }
        }
        syncNodes(result, nodes);
        validate(result);
        if (!result.equals(original)) {
            if (revision == Long.MAX_VALUE) throw invalid("Workflow revision exhausted");
            result.addProperty("revision", revision + 1);
        }
        return new Result(result, report(original, result, archived), archived);
    }

    private static void syncNodes(JsonObject graph, Map<String, JsonObject> nodes) {
        JsonArray array = new JsonArray();
        nodes.values().forEach(array::add);
        graph.add("nodes", array);
    }

    private static Map<String, Set<String>> containment(JsonObject graph) {
        Map<String, JsonObject> nodes = nodes(graph);
        Map<String, Set<String>> edges = adjacency(nodes.keySet());
        nodes.forEach((name, node) -> {
            if (node.has("parent")) edges.get(text(node, "parent")).add(name);
            if (node.has("alternatives")) edges.get(name).addAll(ids(array(node, "alternatives")));
        });
        return edges;
    }

    private static Set<String> component(JsonObject graph, String seed) {
        Map<String, Set<String>> links = containment(graph);
        for (JsonElement value : array(graph, "edges")) {
            JsonObject edge = object(value);
            links.get(text(edge, "from")).add(text(edge, "to"));
        }
        Map<String, Set<String>> undirected = adjacency(links.keySet());
        links.forEach((from, targets) -> targets.forEach(to -> {
            undirected.get(from).add(to);
            undirected.get(to).add(from);
        }));
        Set<String> result = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(seed);
        while (!queue.isEmpty()) {
            String name = queue.remove();
            if (result.add(name)) queue.addAll(undirected.get(name));
        }
        return result;
    }

    private static Set<String> inactive(JsonObject graph) {
        Set<String> result = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (JsonObject node : nodes(graph).values()) {
            if (!node.has("alternatives")) continue;
            for (String name : ids(array(node, "alternatives")))
                if (!new JsonPrimitive(name).equals(node.get("selected"))) queue.add(name);
        }
        Map<String, Set<String>> children = containment(graph);
        while (!queue.isEmpty()) {
            String name = queue.remove();
            if (result.add(name)) queue.addAll(children.get(name));
        }
        return result;
    }

    static boolean ready(JsonObject graph, String name) {
        Map<String, JsonObject> nodes = nodes(graph);
        requireNode(nodes, name);
        if (!text(nodes.get(name), "status").equals("pending") || inactive(graph).contains(name)) return false;
        for (JsonElement value : array(graph, "edges")) {
            JsonObject edge = object(value);
            if (text(edge, "to").equals(name) && !text(nodes.get(text(edge, "from")), "status").equals("done")) return false;
        }
        return true;
    }

    private static String quote(String value) { return new JsonPrimitive(value).toString(); }

    private static String report(JsonObject before, JsonObject after, boolean archived) {
        if (before.equals(after)) return "Workflow unchanged.";
        Map<String, JsonObject> oldNodes = nodes(before);
        Map<String, JsonObject> newNodes = nodes(after);
        List<String> changes = new ArrayList<>();
        newNodes.forEach((name, node) -> {
            JsonObject previous = oldNodes.get(name);
            if (previous == null) changes.add("Added node " + quote(name));
            else if (!previous.equals(node)) {
                String oldStatus = text(previous, "status"), newStatus = text(node, "status");
                changes.add("Changed node " + quote(name) + (oldStatus.equals(newStatus) ? "" : ": " + oldStatus + " -> " + newStatus));
            }
        });
        oldNodes.keySet().stream().filter(name -> !newNodes.containsKey(name))
                .forEach(name -> changes.add("Removed node " + quote(name)));
        if (!before.get("edges").equals(after.get("edges"))) changes.add("Dependencies updated");
        if (!before.get("focus").equals(after.get("focus"))) changes.add("Focus updated");
        if (archived) changes.add("Completed component archived to history");
        StringBuilder result = new StringBuilder("# workflow: Workflow revision ").append(after.get("revision")).append(":");
        for (String change : changes.stream().limit(8).toList())
            result.append("\n# workflow: ").append(change.length() > 200 ? change.substring(0, 200) + "..." : change);
        if (changes.size() > 8) result.append("\n# workflow: (+").append(changes.size() - 8).append(" more changes)");
        return result.toString();
    }

    static String render(JsonObject graph) {
        if (graph == null) return "Workflow tracking is not enabled.\n";
        validate(graph);
        Map<String, JsonObject> nodes = nodes(graph);
        List<String> focus = ids(array(graph, "focus"));
        Set<String> order = new LinkedHashSet<>(focus);
        order.addAll(nodes.keySet());
        Set<String> inactive = inactive(graph);
        StringBuilder result = new StringBuilder("Workflow revision ").append(graph.get("revision")).append('\n');
        for (String name : order) {
            JsonObject node = nodes.get(name);
            String status = text(node, "status");
            if (TERMINAL.contains(status) && !focus.contains(name)) continue;
            result.append(focus.contains(name) ? "* " : "  ").append(quote(name)).append(" [")
                    .append(status).append("; ").append(text(node, "owner"));
            if (inactive.contains(name)) result.append("; unselected alternative");
            else if (ready(graph, name)) result.append("; ready");
            result.append("] ").append(quote(text(node, "text"))).append('\n');
            if (node.has("blocked_reason")) result.append("    Blocker: ").append(quote(text(node, "blocked_reason"))).append('\n');
            if (node.has("alternatives")) result.append("    Alternatives: ").append(node.get("alternatives"))
                    .append("; selected: ").append(node.get("selected")).append('\n');
            for (JsonElement value : array(graph, "edges")) {
                JsonObject edge = object(value);
                if (text(edge, "to").equals(name) && !text(nodes.get(text(edge, "from")), "status").equals("done"))
                    result.append("    Waiting for: ").append(quote(text(edge, "from"))).append('\n');
            }
        }
        for (String status : List.of("done", "skipped", "cancelled"))
            result.append(status).append(": ").append(nodes.values().stream().filter(node -> text(node, "status").equals(status)).count()).append('\n');
        return result.toString();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid workflow: " + message);
    }
}
