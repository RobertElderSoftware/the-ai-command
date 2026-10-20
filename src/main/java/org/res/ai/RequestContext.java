package org.res.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Independent context selection and ordered, first-match file permissions. */
public final class RequestContext {
    public static final String PERMISSIONS_PATH = "__ciop__/PERMISSIONS.json";
    private final List<ContextFile> files;
    private final List<Rule> rules;
    private final boolean configured;
    private String sourceFilename;
    private JsonObject workflow;
    ConfigurationStore store;

    JsonObject workflow() { return workflow == null ? null : workflow.deepCopy(); }

    String sourceFilename() { return sourceFilename; }

    RequestContext select(Path directory, List<String> names) throws IOException {
        WorkingDirectoryPaths paths = new WorkingDirectoryPaths(directory);
        List<ContextFile> selected = new ArrayList<>();
        for (String name : names) {
            ContextFile entry = new ContextFile(name, requireRead(paths, name));
            for (ContextFile existing : files) {
                if (WorkingDirectoryPaths.sameFile(paths.resolve(existing.path()), paths.resolve(name))) {
                    // Retained entries keep both their original spelling and selection.
                    entry = existing.withAccess(entry.access());
                    break;
                }
            }
            selected.add(entry);
        }

        RequestContext result = new RequestContext(selected, rules, configured);
        result.sourceFilename = sourceFilename;
        result.workflow = workflow();
        result.store = store;
        result.merge(paths, List.of());
        return result;
    }

    RequestContext selectEntries(Path directory, List<ContextFile> entries) throws IOException {
        WorkingDirectoryPaths paths = new WorkingDirectoryPaths(directory);
        List<ContextFile> selected = new ArrayList<>();
        for (ContextFile entry : entries)
            selected.add(entry.withAccess(requireRead(paths, entry.path())));
        RequestContext result = new RequestContext(selected, rules, configured);
        result.sourceFilename = sourceFilename;
        result.workflow = workflow();
        result.store = store;
        result.merge(paths, List.of());
        return result;
    }

    /** Retains the explicit-file policy of programmatic callers. */
    public RequestContext(List<ContextFile> files) {
        this(files, List.of(), false);
    }

    private RequestContext(List<ContextFile> files, List<Rule> rules, boolean configured) {
        this.files = List.copyOf(Objects.requireNonNull(files, "files"));
        this.rules = List.copyOf(rules);
        this.configured = configured;
    }

    public static RequestContext empty() { return new RequestContext(List.of()); }
    public List<ContextFile> files() { return files; }
    public boolean hasPermissions() { return configured; }

    private static final class Rule {
        private final String selector;
        private final FileAccess access;
        private final Pattern regex;

        private Rule(String selector, FileAccess access) {
            if (selector.isBlank()) throw new IllegalArgumentException("Empty permission selector");
            this.selector = selector;
            this.access = access;
            this.regex = selector.startsWith("regex:")
                    ? Pattern.compile(selector.substring("regex:".length())) : null;
        }

        private boolean matches(WorkingDirectoryPaths paths, Path target, String normalized)
                throws IOException {
            if (regex != null) return regex.matcher(normalized).matches();
            return WorkingDirectoryPaths.sameFile(paths.resolve(selector), target);
        }

        private JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty(selector, access.name());
            return result;
        }
    }

    public static RequestContext load(Path directory, String filename) throws IOException {
        return new ConfigurationStore(directory, filename).context();
    }

    static RequestContext parse(Path directory, String filename, byte[] bytes) throws IOException {
        WorkingDirectoryPaths paths = new WorkingDirectoryPaths(directory);
        String json = Encoding.strictUtf8(bytes);
        if (json == null) throw new IllegalArgumentException("Context is not UTF-8: " + filename);
        JsonObject graph;
        try {
            JsonObject document = StrictJson.parse(json).getAsJsonObject();
            ConfigurationStore.counter(document, "revision", true);
            graph = document.has("workflow") ? Workflow.validate(document.get("workflow")) : null;
        } catch (com.google.gson.JsonParseException | IllegalStateException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid context " + filename + ": "
                    + exception.getMessage(), exception);
        }
        List<ContextFile> selections = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        Set<String> fields = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field))
                    throw new IllegalArgumentException("Duplicate context field: " + field);
                switch (field) {
                    case "contexts" -> {
                        reader.beginArray();
                        while (reader.hasNext()) selections.add(ContextFile.readEntry(reader));
                        reader.endArray();
                    }
                    case "permissions" -> {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            reader.beginObject();
                            if (!reader.hasNext())
                                throw new IllegalArgumentException("Empty permission rule");
                            String selector = reader.nextName();
                            if (reader.peek() != JsonToken.STRING)
                                throw new IllegalArgumentException("Access must be READ or READ_WRITE");
                            Rule rule = new Rule(selector, FileAccess.valueOf(reader.nextString()));
                            if (reader.hasNext())
                                throw new IllegalArgumentException("Permission rules require exactly one selector");
                            reader.endObject();
                            if (rule.regex == null) {
                                Path literal = paths.resolve(selector);
                                WorkingDirectoryPaths.rejectReserved(paths.resolve("."), literal);
                            }
                            rules.add(rule);
                        }
                        reader.endArray();
                    }
                    case "workflow", "revision" -> reader.skipValue();
                    default -> throw new IllegalArgumentException("Unknown context field: " + field
                            + "; expected contexts and permissions");
                }
            }
            reader.endObject();
            if (!fields.containsAll(Set.of("contexts", "permissions")))
                throw new IllegalArgumentException("Both contexts and permissions arrays are required");
            if (reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException("Unexpected content after context object");
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid context " + filename + ": "
                    + exception.getMessage(), exception);
        }
        RequestContext policy = new RequestContext(List.of(), rules, true);
        List<ContextFile> entries = new ArrayList<>();
        for (ContextFile entry : selections)
            entries.add(entry.withAccess(policy.requireRead(paths, entry.path())));
        RequestContext result = new RequestContext(entries, rules, true);
        result.sourceFilename = filename;
        result.workflow = graph;
        result.merge(paths, List.of());
        return result;
    }

    /** Resolve and validate the path before matching any permission rule. */
    private FileAccess access(WorkingDirectoryPaths paths, String name) throws IOException {
        Path target = paths.resolve(name);
        Path root = paths.resolve(".");
        WorkingDirectoryPaths.rejectReserved(root, target);
        String normalized = root.relativize(target).toString()
                .replace(java.io.File.separatorChar, '/');
        for (Rule rule : rules)
            if (rule.matches(paths, target, normalized)) return rule.access;
        return null;
    }

    private FileAccess requireRead(WorkingDirectoryPaths paths, String name) throws IOException {
        FileAccess access = access(paths, name);
        if (access == null) throw new IllegalArgumentException("Read permission denied: " + name);
        return access;
    }

    /** Ordered permission lookup for discovery; null means denied. */
    public FileAccess accessFor(Path directory, String name) throws IOException {
        return access(new WorkingDirectoryPaths(directory), name);
    }

    public boolean canWrite(Path directory, String name) {
        try {
            return access(new WorkingDirectoryPaths(directory), name) == FileAccess.READ_WRITE;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public JsonObject permissionsDocument() {
        JsonArray permissions = new JsonArray();
        rules.forEach(rule -> permissions.add(rule.json()));
        JsonObject result = new JsonObject();
        result.add("permissions", permissions);
        return result;
    }

    /** Preserve selectors and ordering while registering a protected history file. */
    JsonObject withHistory(WorkingDirectoryPaths paths, String filename) throws IOException {
        JsonArray contexts = new JsonArray();
        boolean found = false;
        for (ContextFile file : files) {
            contexts.add(file.contextEntry());
            found |= WorkingDirectoryPaths.sameFile(paths.resolve(file.path()), paths.resolve(filename));
        }
        if (!found) contexts.add(filename);
        JsonArray permissions = new JsonArray();
        // Prepend only when necessary so a broader writable rule cannot win.
        if (access(paths, filename) != FileAccess.READ)
            permissions.add(new Rule(filename, FileAccess.READ).json());
        rules.forEach(rule -> permissions.add(rule.json()));
        JsonObject result = new JsonObject();
        result.add("contexts", contexts);
        if (workflow != null) result.add("workflow", workflow.deepCopy());
        result.add("permissions", permissions);
        return result;
    }

    /** Argument files use the same policy and cannot grant additional access. */
    public List<ContextFile> merge(WorkingDirectoryPaths paths, List<String> arguments) throws IOException {
        Objects.requireNonNull(paths, "paths");
        List<ContextFile> merged = new ArrayList<>();
        List<Path> identities = new ArrayList<>();
        for (ContextFile entry : files) {
            ContextFile checked = configured
                    ? entry.withAccess(requireRead(paths, entry.path())) : entry;
            add(paths, checked, true, merged, identities);
        }
        for (String argument : arguments == null ? List.<String>of() : arguments) {
            FileAccess access = configured ? requireRead(paths, argument) : FileAccess.READ_WRITE;
            add(paths, new ContextFile(argument, access), false, merged, identities);
        }
        return List.copyOf(merged);
    }

    private static void add(WorkingDirectoryPaths paths, ContextFile entry, boolean rejectDuplicate,
            List<ContextFile> merged, List<Path> identities) throws IOException {
        Path file = paths.contextFile(entry);
        for (int index = 0; index < identities.size(); index++) {
            if (WorkingDirectoryPaths.sameFile(file, identities.get(index))) {
                if (rejectDuplicate)
                    throw new IllegalArgumentException("Duplicate context file: " + entry.path()
                            + " aliases " + merged.get(index).path());
                return;
            }
        }
        identities.add(file);
        merged.add(entry);
    }
}
