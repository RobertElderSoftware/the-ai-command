package org.res.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;

/** Stateless builders; preserve explicit invalid values and ordered duplicate context entries. */
public final class TestJson {
    private static final Gson JSON = new Gson();
    private TestJson() { }

    public static JsonObject object(Object... fields) {
        if (fields.length % 2 != 0) throw new IllegalArgumentException("Expected name/value pairs");
        JsonObject result = new JsonObject();
        for (int index = 0; index < fields.length; index += 2)
            result.add((String) fields[index], JSON.toJsonTree(fields[index + 1]));
        return result;
    }

    public static JsonArray lines(JsonObject... values) {
        JsonArray result = new JsonArray();
        for (JsonObject value : values) result.add(value);
        return result;
    }

    public static String batch(JsonObject... operations) { return lines(operations).toString(); }
    public static JsonObject read(String path) { return object(path, FileAccess.READ.name()); }
    public static JsonObject readWrite(String path) { return object(path, FileAccess.READ_WRITE.name()); }
    public static String contextJson(JsonObject... files) { return object("files", lines(files)).toString(); }

    public static JsonObject operation(OperationType type, String path, Encoding encoding, byte[] data) {
        JsonObject result = object("op", type.opcode(), "encoding", encoding.token(), "data", encoding.encode(data));
        if (path != null) result.addProperty("path", path);
        return result;
    }

    public static JsonObject stdout(String text) { return object("op", OperationType.STDOUT.opcode(), "encoding", Encoding.UTF_8.token(), "data", text); }
    public static JsonObject fileWrite(String path, String text) {
        JsonObject result = stdout(text);
        result.addProperty("op", OperationType.FILE_WRITE.opcode());
        result.addProperty("path", path);
        return result;
    }

    public static JsonObject line(String type, String text) { return object("type", type, "text", text); }

    public static JsonObject hunk(int oldStart, int oldCount, int newStart, int newCount, JsonArray lines) {
        return object("old_start", oldStart, "old_count", oldCount,
                "new_start", newStart, "new_count", newCount, "lines", lines);
    }

    public static JsonObject operation(String path, String sourceHash, String resultHash,
            int oldStart, int oldCount, int newStart, int newCount, JsonArray lines) {
        JsonObject result = object("op", OperationType.FILE_PATCH.opcode(), "path", path,
                "source_sha256", sourceHash, "hunks", lines(hunk(oldStart, oldCount, newStart, newCount, lines)));
        if (resultHash != null) result.addProperty("result_sha256", resultHash);
        return result;
    }

    public static JsonObject replaceFirstLine(String path, String source, String removed, String added) {
        return operation(path, AICommandApplication.sha256(source.getBytes(StandardCharsets.UTF_8)),
                null, 1, 1, 1, 1, lines(line("remove", removed), line("add", added)));
    }
}
