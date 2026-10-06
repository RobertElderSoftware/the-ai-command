package org.res.ai;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

/** Validates and commits selection changes without granting file permissions. */
final class ChangeContexts {
    record Result(RequestContext context, List<ContextFile> files, String report) { }
    private record Changes(List<ContextFile> add, List<String> remove, List<ContextFile> update) { }

    private ChangeContexts() { }

    static Operation operation(JsonObject object) {
        changes(object);
        return new Operation(OperationType.CHANGE_CONTEXTS, null, "utf-8",
                object.toString(), null, null);
    }

    private static Changes changes(JsonObject object) {
        if (!object.keySet().containsAll(Set.of("op", "add", "remove"))
                || !Set.of("op", "add", "remove", "update").containsAll(object.keySet())
                || !new JsonPrimitive("change_contexts").equals(object.get("op")))
            throw new IllegalArgumentException("change_contexts requires op, add, remove, and optional update");
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
            if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()
                    || name.getAsString().isBlank())
                throw new IllegalArgumentException("change_contexts filenames must be nonblank strings");
            result.add(name.getAsString());
        }
        return result;
    }

    static Result apply(Path directory, RequestContext original, List<String> arguments,
            String data) throws IOException {
        if (!original.hasPermissions() || original.sourceFilename() == null)
            throw new IllegalArgumentException("change_contexts requires a loaded context configuration; "
                    + "it is unavailable with --ignore-context or a missing configuration");
        WorkingDirectoryPaths paths = new WorkingDirectoryPaths(directory);
        Path target = paths.contextFile(new ContextFile(original.sourceFilename(), FileAccess.READ));
        byte[] snapshot = WorkingDirectoryPaths.read(target);
        Object identity = identity(target);
        RequestContext current = RequestContext.load(directory, original.sourceFilename());
        if (!current.permissionsDocument().equals(original.permissionsDocument()))
            throw new IllegalArgumentException("Context permissions changed during the request");
        final JsonElement parsed;
        try {
            parsed = StrictJson.parse(data);
        } catch (com.google.gson.JsonParseException exception) {
            throw new IllegalArgumentException("Invalid change_contexts JSON", exception);
        }
        if (!parsed.isJsonObject())
            throw new IllegalArgumentException("change_contexts must be an object");
        Changes request = changes(parsed.getAsJsonObject());
        List<String> allNames = new ArrayList<>(request.remove());
        request.add().forEach(file -> allNames.add(file.path()));
        request.update().forEach(file -> allNames.add(file.path()));
        validateNames(paths, allNames);
        List<String> explicit = arguments == null ? List.of() : arguments;
        for (String name : request.remove())
            if (indexOf(paths, explicit, name) >= 0)
                throw new IllegalArgumentException("Cannot remove an explicitly supplied command-line file: " + name);

        List<ContextFile> selected = new ArrayList<>(current.files());
        StringBuilder report = new StringBuilder("Context configuration: ")
                .append(quote(original.sourceFilename())).append('\n');
        for (String name : request.remove()) {
            int index = selectionIndex(paths, selected, name);
            report.append(index < 0 ? "Not in context: " : "Removed from context: ")
                    .append(quote(index < 0 ? name : selected.remove(index).path())).append('\n');
        }
        for (ContextFile addition : request.add()) {
            // Even an idempotent addition must be permitted and contain valid ranges.
            ContextFile checked = checked(directory, paths, current, addition);
            int index = selectionIndex(paths, selected, addition.path());
            if (index < 0) selected.add(checked);
            report.append(index < 0 ? "Added to context: " : "Already in context: ")
                    .append(describe(index < 0 ? checked : selected.get(index))).append('\n');
        }
        for (ContextFile update : request.update()) {
            int index = selectionIndex(paths, selected, update.path());
            if (index < 0)
                throw new IllegalArgumentException("Cannot update a file absent from configured contexts: " + update.path());
            checked(directory, paths, current, update);
            ContextFile previous = selected.get(index);
            ContextFile replacement = new ContextFile(previous.path(), previous.access(), update.selection());
            boolean same = previous.selection().equals(update.selection());
            selected.set(index, replacement);
            report.append(same ? "Unchanged context selection: " : "Updated context selection: ")
                    .append(describe(replacement)).append('\n');
        }
        if (allNames.isEmpty()) report.append("No context changes requested.\n");
        RequestContext updated = current.selectEntries(directory, selected);
        List<ContextFile> files = updated.merge(paths, explicit);
        // Validate retained entries and explicit files as well as requested changes.
        for (ContextFile file : files) validateInput(paths, file);
        JsonObject document = JsonParser.parseString(new String(snapshot, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray contexts = new JsonArray();
        updated.files().forEach(file -> contexts.add(file.contextEntry()));
        boolean changed = !contexts.equals(document.get("contexts"));
        document.add("contexts", contexts);
        byte[] replacement = (new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .create().toJson(document) + "\n").getBytes(StandardCharsets.UTF_8);
        AtomicFileReplacement.Validation validation = () -> {
            paths.contextFile(new ContextFile(original.sourceFilename(), FileAccess.READ));
            if (!Objects.equals(identity, identity(target))
                    || !Arrays.equals(snapshot, WorkingDirectoryPaths.read(target)))
                throw new IOException("Context configuration changed before commit");
        };
        validation.validate();
        if (changed) AtomicFileReplacement.replace(target, replacement, ".ciop-context-", true, validation);
        return new Result(updated, files, report.toString());
    }

    private static ContextFile checked(Path directory, WorkingDirectoryPaths paths,
            RequestContext context, ContextFile file) throws IOException {
        FileAccess access = context.accessFor(directory, file.path());
        if (access == null) throw new IllegalArgumentException("Read permission denied: " + file.path());
        ContextFile checked = file.withAccess(access);
        validateInput(paths, checked);
        return checked;
    }

    private static void validateInput(WorkingDirectoryPaths paths, ContextFile file) throws IOException {
        Path input = paths.contextFile(file);
        if (!file.selection().isWhole()) SubsetEnvelope.read(paths, file).section(file);
        else if (Files.exists(input, LinkOption.NOFOLLOW_LINKS)) WorkingDirectoryPaths.read(input);
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

    private static int selectionIndex(WorkingDirectoryPaths paths, List<ContextFile> files,
            String name) throws IOException {
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
