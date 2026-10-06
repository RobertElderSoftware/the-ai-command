package org.res.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A filename, its selection, and its independent access policy. */
public final class ContextFile {
    private final String path;
    private final FileAccess access;
    private final ContextSelection selection;

    public ContextFile(String path, FileAccess access) {
        this(path, access, ContextSelection.WHOLE);
    }

    public ContextFile(String path, FileAccess access, ContextSelection selection) {
        this.path = Objects.requireNonNull(path, "path");
        this.access = Objects.requireNonNull(access, "access");
        this.selection = Objects.requireNonNull(selection, "selection");
        if (path.isBlank()) throw new IllegalArgumentException("File path must not be blank");
    }

    public String path() { return path; }
    public FileAccess access() { return access; }
    public ContextSelection selection() { return selection; }

    public ContextFile withAccess(FileAccess value) {
        return new ContextFile(path, value, selection);
    }

    /** Configuration serialization never includes or grants an access policy. */
    public JsonElement contextEntry() {
        if (selection.isWhole()) return new JsonPrimitive(path);
        JsonObject result = new JsonObject();
        result.addProperty("path", path);
        result.add(selection.kind() == ContextSelection.Kind.LINES ? "lines" : "bytes",
                selection.jsonRanges());
        return result;
    }

    /** Streaming parsing retains duplicate-member detection before JSON normalization. */
    static ContextFile readEntry(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.STRING)
            return new ContextFile(reader.nextString(), FileAccess.READ);
        if (reader.peek() != JsonToken.BEGIN_OBJECT)
            throw new IllegalArgumentException("Context entry must be a filename or subset object");
        String path = null;
        ContextSelection.Kind kind = null;
        List<ContextSelection.Range> ranges = null;
        Set<String> fields = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!fields.add(field)) throw new IllegalArgumentException("Duplicate selection field: " + field);
            switch (field) {
                case "path" -> {
                    if (reader.peek() != JsonToken.STRING)
                        throw new IllegalArgumentException("Selection path must be a string");
                    path = reader.nextString();
                }
                case "lines", "bytes" -> {
                    if (kind != null) throw new IllegalArgumentException("Cannot mix lines and bytes");
                    kind = field.equals("lines") ? ContextSelection.Kind.LINES : ContextSelection.Kind.BYTES;
                    ranges = readRanges(reader);
                }
                default -> throw new IllegalArgumentException("Unknown selection field: " + field);
            }
        }
        reader.endObject();
        if (path == null || kind == null)
            throw new IllegalArgumentException("Subset requires path and exactly one of lines or bytes");
        try {
            return new ContextFile(path, FileAccess.READ, new ContextSelection(kind, ranges));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid selection for " + path + ": "
                    + exception.getMessage(), exception);
        }
    }

    private static List<ContextSelection.Range> readRanges(JsonReader reader) throws IOException {
        List<ContextSelection.Range> result = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            reader.beginArray();
            long first = coordinate(reader);
            long second = coordinate(reader);
            if (reader.hasNext()) throw new IllegalArgumentException("Range requires exactly two integers");
            reader.endArray();
            result.add(new ContextSelection.Range(first, second));
        }
        reader.endArray();
        return result;
    }

    private static long coordinate(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER)
            throw new IllegalArgumentException("Range requires JSON integers");
        String token = reader.nextString();
        if (!token.matches("-?(0|[1-9][0-9]*)"))
            throw new IllegalArgumentException("Range coordinate must be an integer: " + token);
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Range coordinate exceeds signed 64-bit bounds: " + token, exception);
        }
    }
}
