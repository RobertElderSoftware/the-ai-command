package org.res.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Parse only JSONL event fields, never stderr, echoed prompts, or tool output. */
final class CodexEvents {
    private static final Pattern CLOCK_HINT = Pattern.compile(
            "try again at ((?:[1-9]|1[0-2]):[0-5][0-9] [AP]M)(?:\\.|$)");
    private CodexEvents() { }

    static String response(int exit, String stdout, String stderr) {
        boolean completed = false;
        boolean failed = false;
        boolean malformed = false;
        boolean unknownFailure = false;
        String lastMessage = null;
        List<ProviderLimitException> limits = new ArrayList<>();
        boolean networkFailure = false;
        try {
            for (String line : stdout.split("\\n")) {
                if (line.isBlank()) continue;
                JsonObject event = StrictJson.parseCodexEvent(line).getAsJsonObject();
                String type = string(event, "type");
                if (type == null) throw new IllegalArgumentException("Missing event type");
                if (completed || failed) {
                    // Duplicate failure diagnostics are permitted, but output after completion is not.
                    if (completed || !(type.equals("error") || type.equals("turn.failed")))
                        malformed = true;
                }
                switch (type) {
                    case "turn.completed" -> completed = true;
                    case "turn.failed", "error" -> {
                        if (type.equals("turn.failed")) failed = true;
                        JsonObject error = type.equals("error") ? event : object(event, "error");
                        ProviderLimitException limit = classify(error);
                        if (limit != null) limits.add(limit);
                        else if (ProviderNetworkException.codexTransport(error)) networkFailure = true;
                        else unknownFailure = true;
                    }
                    case "item.completed" -> {
                        JsonObject item = object(event, "item");
                        if (item == null) throw new IllegalArgumentException("Missing item");
                        if ("agent_message".equals(string(item, "type"))) {
                            String phase = string(item, "phase");
                            if (!"commentary".equals(phase)) lastMessage = string(item, "text");
                        }
                    }
                    default -> { /* Forward-compatible nonterminal events. */ }
                }
            }
        } catch (RuntimeException exception) {
            malformed = true;
        }
        if (!malformed && !unknownFailure && networkFailure && limits.isEmpty()
                && !completed)
            throw new ProviderNetworkException("codex", "configured Codex endpoint",
                    ProviderNetworkException.Phase.UNCERTAIN);
        if (!malformed && !unknownFailure && !networkFailure && !limits.isEmpty()) {
            ProviderLimitException first = limits.get(0);
            boolean same = limits.stream().allMatch(limit -> limit.kind() == first.kind());
            if (same) throw limits.stream().filter(limit -> limit.resetHint() != null)
                    .findFirst().orElse(first);
        }
        if (exit != 0 || malformed || failed || unknownFailure || !completed || lastMessage == null)
            throw new RuntimeException("codex exec failed or returned an incomplete event stream; exit code "
                    + exit + ". stderr: " + stderr + "\nstdout JSONL: " + stdout);
        return lastMessage;
    }

    static ProviderLimitException classify(JsonObject error) {
        if (error == null) return null;
        String code = string(error, "code");
        ProviderLimitException.Kind kind = switch (code == null ? "" : code) {
            case "usage_limit_reached" -> ProviderLimitException.Kind.USAGE_LIMIT;
            case "rate_limit_exceeded" -> ProviderLimitException.Kind.RATE_LIMIT;
            case "insufficient_quota", "quota_exhausted" -> ProviderLimitException.Kind.QUOTA_EXHAUSTED;
            default -> null;
        };
        String message = string(error, "message");
        // An unknown explicit code must not be overridden by incidental message text.
        if (kind == null && code == null && message != null
                && message.startsWith("You've hit your usage limit."))
            kind = ProviderLimitException.Kind.USAGE_LIMIT;
        if (kind == null) return null;
        String hint = null;
        if (message != null && message.length() <= 4096) {
            var match = CLOCK_HINT.matcher(message);
            if (match.find()) hint = match.group(1);
        }
        return new ProviderLimitException("codex", kind, code, null, null, hint);
    }

    static String string(JsonObject object, String field) {
        if (object == null) return null;
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    static JsonObject object(JsonObject object, String field) {
        if (object == null) return null;
        JsonElement value = object.get(field);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }
}
