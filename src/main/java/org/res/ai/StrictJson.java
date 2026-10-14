package org.res.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/** Reject duplicate members before Gson's tree parser can discard them. */
final class StrictJson {
    private StrictJson() { }

    static JsonElement parse(String text) {
        return parse(text, false);
    }

    /** Codex can emit two item IDs; these IDs are not used by response extraction. */
    static JsonElement parseCodexEvent(String text) {
        return parse(text, true);
    }

    private static JsonElement parse(String text, boolean codexEvent) {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            validate(reader, codexEvent, false);
            if (reader.peek() != JsonToken.END_DOCUMENT)
                throw new JsonParseException("Unexpected content after JSON value");
        } catch (IOException | IllegalStateException exception) {
            throw new JsonParseException("Invalid JSON", exception);
        }
        return JsonParser.parseString(text);
    }

    private static void validate(JsonReader reader, boolean eventRoot, boolean itemMetadata)
            throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                Set<String> fields = new HashSet<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!fields.add(name) && !(itemMetadata && name.equals("id")))
                        throw new JsonParseException("Duplicate JSON field: " + name);
                    validate(reader, false, eventRoot && name.equals("item"));
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) validate(reader, false, false);
                reader.endArray();
            }
            case STRING, NUMBER, BOOLEAN, NULL -> reader.skipValue();
            default -> throw new JsonParseException("Expected JSON value");
        }
    }
}
