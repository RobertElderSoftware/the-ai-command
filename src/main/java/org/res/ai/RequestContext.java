package org.res.ai;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Ordered request files; resolution is repeated for every request. */
public final class RequestContext {
    private final List<ContextFile> files;

    public RequestContext(List<ContextFile> files) {
        this.files = List.copyOf(Objects.requireNonNull(files, "files"));
    }

    public static RequestContext empty() { return new RequestContext(List.of()); }
    public List<ContextFile> files() { return files; }

    public static RequestContext load(Path directory, String filename) throws IOException {
        WorkingDirectoryPaths paths = new WorkingDirectoryPaths(directory);
        Path file = paths.contextFile(new ContextFile(filename, FileAccess.READ));
        String json = StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(WorkingDirectoryPaths.read(file))).toString();
        List<ContextFile> entries = new ArrayList<>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            if (!reader.hasNext() || !"files".equals(reader.nextName()))
                throw new IllegalArgumentException("Context must contain only a 'files' array");
            reader.beginArray();
            while (reader.hasNext()) {
                reader.beginObject();
                if (!reader.hasNext())
                    throw new IllegalArgumentException("Context entry must contain exactly one filename");
                String path = reader.nextName();
                if (reader.peek() != JsonToken.STRING)
                    throw new IllegalArgumentException("Access must be READ or READ_WRITE: " + path);
                FileAccess access = FileAccess.valueOf(reader.nextString());
                if (reader.hasNext())
                    throw new IllegalArgumentException("Context entry must contain exactly one filename: " + path);
                reader.endObject();
                entries.add(new ContextFile(path, access));
            }
            reader.endArray();
            if (reader.hasNext())
                throw new IllegalArgumentException("Duplicate or unknown context field: " + reader.nextName());
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException("Unexpected content after context object");
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid context " + filename + ": " + exception.getMessage(), exception);
        }
        RequestContext context = new RequestContext(entries);
        context.merge(paths, List.of());
        return context;
    }

    /** Context duplicates are errors; argument duplicates retain the first entry's policy. */
    public List<ContextFile> merge(WorkingDirectoryPaths paths, List<String> arguments) throws IOException {
        Objects.requireNonNull(paths, "paths");
        List<ContextFile> merged = new ArrayList<>();
        List<Path> identities = new ArrayList<>();
        for (ContextFile entry : files) add(paths, entry, true, merged, identities);
        for (String argument : arguments == null ? List.<String>of() : arguments)
            add(paths, new ContextFile(argument, FileAccess.READ_WRITE), false, merged, identities);
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
