package org.res.ai;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Plans selection changes independently of persistence and input contents. */
final class ChangeContexts {
    record Result(RequestContext context, List<ContextFile> files, String report) { }
    private record Changes(List<ContextFile> add, List<String> remove, List<ContextFile> update) { }

    record Plan(List<ContextFile> selections, List<ContextFile> validationInputs, String report) {
        JsonArray entries() {
            JsonArray result = new JsonArray();
            selections.forEach(file -> result.add(file.contextEntry()));
            return result;
        }

        boolean changed(RequestContext original) {
            JsonArray previous = new JsonArray();
            original.files().forEach(file -> previous.add(file.contextEntry()));
            return !entries().equals(previous);
        }

        String historyReport() {
            return report.lines().map(line -> "# context: " + line + "\n")
                    .collect(java.util.stream.Collectors.joining());
        }

        String notice() {
            List<String> details = historyReport().lines().toList();
            String result = details.stream().limit(8)
                    .map(line -> line.length() > 240 ? line.substring(0, 240) + "..." : line)
                    .collect(java.util.stream.Collectors.joining("\n"));
            return details.size() <= 8 ? result
                    : result + "\n# context: (+" + (details.size() - 8) + " more lines)";
        }
    }

    private ChangeContexts() { }

    private static JsonObject parseRequest(String data) {
        try {
            return StrictJson.parse(data).getAsJsonObject();
        } catch (com.google.gson.JsonParseException | IllegalStateException failure) {
            throw new IllegalArgumentException("Invalid change_contexts JSON: "
                    + failure.getMessage(), failure);
        }
    }

    static boolean followUp(JsonObject object) {
        if (!object.has("follow_up")) return true;
        JsonElement value = object.get("follow_up");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("change_contexts follow_up must be a boolean");
        return value.getAsBoolean();
    }

    static boolean followUp(Operation operation) {
        return followUp(parseRequest(operation.data));
    }

    static Operation operation(JsonObject object) {
        changes(object);
        return new Operation(OperationType.CHANGE_CONTEXTS, null, "utf-8", object.toString(), null, null);
    }

    private static Changes changes(JsonObject object) {
        if (!object.keySet().containsAll(Set.of("op", "add", "remove"))
                || !Set.of("op", "add", "remove", "update", "follow_up").containsAll(object.keySet())
                || !new JsonPrimitive("change_contexts").equals(object.get("op")))
            throw new IllegalArgumentException("change_contexts requires op, add, remove, and optional update and follow_up");
        followUp(object);
        return new Changes(entries(object, "add"), names(object, "remove"),
                object.has("update") ? entries(object, "update") : List.of());
    }

    private static JsonArray array(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray())
            throw new IllegalArgumentException("change_contexts " + field + " must be an array");
        return value.getAsJsonArray();
    }

    private static List<ContextFile> entries(JsonObject object, String field) {
        List<ContextFile> result = new ArrayList<>();
        for (JsonElement value : array(object, field)) {
            try (JsonReader reader = new JsonReader(new StringReader(value.toString()))) {
                reader.setStrictness(Strictness.STRICT);
                result.add(ContextFile.readEntry(reader));
            } catch (IOException | IllegalStateException exception) {
                throw new IllegalArgumentException("Invalid change_contexts " + field + " selection", exception);
            }
        }
        return result;
    }

    private static List<String> names(JsonObject object, String field) {
        List<String> result = new ArrayList<>();
        for (JsonElement name : array(object, field)) {
            if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString() || name.getAsString().isBlank())
                throw new IllegalArgumentException("change_contexts filenames must be nonblank strings");
            result.add(name.getAsString());
        }
        return result;
    }

    /** No filesystem mutation or content validation: callers supply the appropriate file view. */
    static Plan plan(Path directory, RequestContext current, List<String> arguments, String data) throws IOException {
        if (!current.hasPermissions() || current.sourceFilename() == null)
            throw new IllegalArgumentException("change_contexts requires a loaded context configuration");
        WorkingDirectoryPaths paths = new WorkingDirectoryPaths(directory);
        JsonObject operation = parseRequest(data);
        Changes request = changes(operation);
        boolean followUp = followUp(operation);
        List<String> allNames = new ArrayList<>(request.remove());
        request.add().forEach(file -> allNames.add(file.path()));
        request.update().forEach(file -> allNames.add(file.path()));
        validateNames(paths, allNames);
        List<String> explicit = arguments == null ? List.of() : arguments;
        for (String name : request.remove()) {
            if (followUp && indexOf(paths, explicit, name) >= 0)
                throw new IllegalArgumentException("Cannot remove an explicitly supplied command-line file: " + name);
            if (!followUp) protectHistory(paths, current.files(), name);
        }
        if (!followUp)
            for (ContextFile file : request.update()) protectHistory(paths, current.files(), file.path());

        List<ContextFile> selected = new ArrayList<>(current.files());
        List<ContextFile> inputs = new ArrayList<>();
        StringBuilder report = new StringBuilder("Context configuration: ")
                .append(quote(current.sourceFilename())).append('\n');
        for (String name : request.remove()) {
            int index = selectionIndex(paths, selected, name);
            report.append(index < 0 ? "Not in context: " : "Removed from context: ")
                    .append(quote(index < 0 ? name : selected.remove(index).path())).append('\n');
        }
        for (ContextFile addition : request.add()) {
            ContextFile checked = permitted(directory, current, addition);
            inputs.add(checked); // Validate even a no-op addition's requested excerpt.
            int index = selectionIndex(paths, selected, addition.path());
            if (index < 0) selected.add(checked);
            report.append(index < 0 ? "Added to context: " : "Already in context: ")
                    .append(describe(index < 0 ? checked : selected.get(index))).append('\n');
        }
        for (ContextFile update : request.update()) {
            int index = selectionIndex(paths, selected, update.path());
            if (index < 0)
                throw new IllegalArgumentException("Cannot update a file absent from configured contexts: " + update.path());
            ContextFile checked = permitted(directory, current, update);
            inputs.add(checked);
            ContextFile previous = selected.get(index);
            ContextFile replacement = new ContextFile(previous.path(), checked.access(), update.selection());
            selected.set(index, replacement);
            report.append(previous.selection().equals(update.selection())
                    ? "Unchanged context selection: " : "Updated context selection: ")
                    .append(describe(replacement)).append('\n');
        }
        if (allNames.isEmpty()) report.append("No context changes requested.\n");
        validateNames(paths, selected.stream().map(ContextFile::path).toList());
        for (ContextFile file : selected) inputs.add(permitted(directory, current, file));
        return new Plan(List.copyOf(selected), List.copyOf(inputs), report.toString());
    }

    private static void protectHistory(WorkingDirectoryPaths paths, List<ContextFile> selected, String name)
            throws IOException {
        Path target = paths.resolve(name);
        Path history = paths.resolve(ConversationHistory.DIRECTORY);
        if (target.startsWith(history))
            throw new IllegalArgumentException("Trailing context changes must preserve conversation history: " + name);
        for (ContextFile file : selected) {
            Path source = paths.resolve(file.path());
            if (source.startsWith(history) && WorkingDirectoryPaths.sameFile(source, target))
                throw new IllegalArgumentException("Trailing context changes must preserve conversation history: " + name);
        }
    }

    private static ContextFile permitted(Path directory, RequestContext context, ContextFile file) throws IOException {
        FileAccess access = context.accessFor(directory, file.path());
        if (access == null) throw new IllegalArgumentException("Read permission denied: " + file.path());
        return file.withAccess(access);
    }

    static Result apply(Path directory, RequestContext original, List<String> arguments, String data) throws IOException {
        if (!original.hasPermissions() || original.sourceFilename() == null)
            throw new IllegalArgumentException("change_contexts requires a loaded context configuration");
        if (!followUp(parseRequest(data)))
            throw new IllegalArgumentException("Non-follow-up context changes require batch staging");
        WorkingDirectoryPaths paths = new WorkingDirectoryPaths(directory);
        ConfigurationStore store = original.store;
        if (store == null) throw new IllegalArgumentException("Missing configuration store");
        store.verify();
        RequestContext current = store.context();
        Plan plan = plan(directory, current, arguments, data);
        for (ContextFile file : plan.validationInputs()) validateInput(paths, file);
        RequestContext updated = current.selectEntries(directory, plan.selections());
        List<ContextFile> files = updated.merge(paths, arguments == null ? List.of() : arguments);
        WorkflowSession.validateSelection(directory, updated, files);
        for (ContextFile file : files) validateInput(paths, file);
        store.commit(store.replacement(null, plan.selections()));
        return new Result(updated, files, plan.report());
    }

    static void validateInput(WorkingDirectoryPaths paths, ContextFile file) throws IOException {
        Path input = paths.contextFile(file);
        if (!file.selection().isWhole()) SubsetEnvelope.read(paths, file).section(file);
        else if (Files.exists(input, LinkOption.NOFOLLOW_LINKS)) WorkingDirectoryPaths.read(input);
    }

    static void validateBytes(ContextFile file, byte[] bytes) throws IOException {
        if (bytes == null) {
            if (file.access() != FileAccess.READ_WRITE || !file.selection().isWhole())
                throw new IllegalArgumentException("Selected file does not exist in staged result: " + file.path());
        } else if (!file.selection().isWhole()) {
            SubsetEnvelope.read(file, bytes).section(file);
        }
    }

    private static String describe(ContextFile file) {
        if (file.selection().isWhole()) return quote(file.path());
        var ranges = file.selection().ranges();
        StringBuilder result = new StringBuilder(quote(file.path())).append(' ')
                .append(file.selection().kind() == ContextSelection.Kind.LINES ? "lines=" : "bytes=");
        for (int index = 0; index < Math.min(4, ranges.size()); index++) {
            if (index > 0) result.append(',');
            var range = ranges.get(index);
            result.append('[').append(range.first()).append(',').append(range.second()).append(']');
        }
        if (ranges.size() > 4) result.append(" (+").append(ranges.size() - 4).append(" ranges)");
        return result.toString();
    }

    private static Object identity(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    private static void validateNames(WorkingDirectoryPaths paths, List<String> names) throws IOException {
        List<String> seen = new ArrayList<>();
        for (String name : names) {
            Path path = paths.resolve(name);
            WorkingDirectoryPaths.rejectReserved(paths.resolve("."), path);
            if (indexOf(paths, seen, name) >= 0)
                throw new IllegalArgumentException("Duplicate context change filename across actions: " + name);
            seen.add(name);
        }
    }

    private static int selectionIndex(WorkingDirectoryPaths paths, List<ContextFile> files, String name) throws IOException {
        return indexOf(paths, files.stream().map(ContextFile::path).toList(), name);
    }

    private static int indexOf(WorkingDirectoryPaths paths, List<String> names, String name) throws IOException {
        Path target = paths.resolve(name);
        for (int index = 0; index < names.size(); index++)
            if (WorkingDirectoryPaths.sameFile(target, paths.resolve(names.get(index)))) return index;
        return -1;
    }

    private static String quote(String name) { return new JsonPrimitive(name).toString(); }
}
