package org.res.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Human-readable history rendering of the structured discovery report. */
final class ListingHistoryFormat {
    private ListingHistoryFormat() { }

    static String format(String json) {
        JsonObject report = JsonParser.parseString(json).getAsJsonObject();
        StringBuilder result = new StringBuilder("Complete: ")
                .append(report.get("complete").getAsBoolean() ? "yes" : "no")
                .append("\nPermissions: 4=READ, 6=READ_WRITE (application access)\n");
        if (report.getAsJsonArray("entries").isEmpty()) result.append("Entries: none\n");
        for (var value : report.getAsJsonArray("entries")) {
            JsonObject entry = value.getAsJsonObject();
            String access = entry.get("access").getAsString();
            int permission = switch (access) {
                case "READ" -> 4;
                case "READ_WRITE" -> 6;
                default -> throw new IllegalArgumentException("Unknown listing access: " + access);
            };
            result.append(entry.get("type").getAsString()).append(": ")
                    .append(quoted(entry.get("path").getAsString()))
                    .append(" | permission: ").append(permission);
            if (entry.has("size_bytes"))
                result.append(" | bytes: ").append(entry.get("size_bytes").getAsLong());
            if (entry.has("line_count"))
                result.append(" | lines: ").append(entry.get("line_count").getAsLong());
            result.append('\n');
        }
        result.append("Errors: ").append(report.getAsJsonArray("errors").size()).append('\n');
        for (var value : report.getAsJsonArray("errors")) {
            JsonObject error = value.getAsJsonObject();
            result.append("error: ").append(quoted(error.get("path").getAsString()))
                    .append(" | ").append(quoted(error.get("error").getAsString())).append('\n');
        }
        return result.toString();
    }

    /** Quoting preserves unusual names and keeps every entry on one physical line. */
    private static String quoted(String text) {
        return new JsonPrimitive(text).toString();
    }
}
