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
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            validate(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT)
                throw new JsonParseException("Unexpected content after JSON value");
        } catch (IOException | IllegalStateException exception) {
            throw new JsonParseException("Invalid JSON", exception);
        }
        return JsonParser.parseString(text);
    }

    private static void validate(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                Set<String> fields = new HashSet<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!fields.add(name))
                        throw new JsonParseException("Duplicate JSON field: " + name);
                    validate(reader);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) validate(reader);
                reader.endArray();
            }
            case STRING, NUMBER, BOOLEAN, NULL -> reader.skipValue();
            default -> throw new JsonParseException("Expected JSON value");
        }
    }
}
