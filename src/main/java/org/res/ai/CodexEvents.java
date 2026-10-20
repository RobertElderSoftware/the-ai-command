package org.res.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Incremental JSONL validation; tool output never participates in error classification. */
final class CodexEvents {
    private static final Pattern CLOCK_HINT = Pattern.compile(
            "try again at ((?:[1-9]|1[0-2]):[0-5][0-9] [AP]M)(?:\\.|$)");
    static final int MAX_RECORD_BYTES = 16 * 1024 * 1024;
    private static final int DIAGNOSTIC_BYTES = 65536;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final ByteArrayOutputStream diagnostic = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    private final CodexCommandActivity activity;
    private boolean completed;
    private boolean failed;
    private boolean malformed;
    private boolean unknownFailure;
    private boolean networkFailure;
    private boolean mixedLimits;
    private boolean ended;
    private boolean diagnosticTruncated;
    private boolean errorsTruncated;
    private String lastMessage;
    private ProviderLimitException limit;

    CodexEvents(CodexCommandActivity activity) { this.activity = activity; }

    static String response(int exit, String stdout, String stderr) {
        CodexEvents parser = new CodexEvents(null);
        parser.stdout(stdout.getBytes(StandardCharsets.UTF_8));
        parser.stderr(stderr.getBytes(StandardCharsets.UTF_8));
        return parser.result(exit);
    }

    void stdout(byte[] bytes) {
        if (ended) throw new IllegalStateException("Codex stream already ended");
        diagnosticTruncated |= retain(diagnostic, bytes);
        int start = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\n') {
                append(bytes, start, index - start);
                line();
                start = index + 1;
            }
        }
        append(bytes, start, bytes.length - start);
    }

    void stderr(byte[] bytes) { errorsTruncated |= retain(errors, bytes); }

    private static boolean retain(ByteArrayOutputStream target, byte[] bytes) {
        int count = Math.min(bytes.length, DIAGNOSTIC_BYTES - target.size());
        target.write(bytes, 0, count);
        return count != bytes.length;
    }

    private void append(byte[] bytes, int offset, int count) {
        if (count > MAX_RECORD_BYTES - pending.size())
            throw new IllegalArgumentException("Codex JSONL record exceeds 16777216 bytes; protocol data was not truncated");
        pending.write(bytes, offset, count);
    }

    private void line() {
        String text = Encoding.strictUtf8(pending.toByteArray());
        pending.reset();
        if (malformed) return;
        JsonObject event;
        boolean terminalBefore = completed || failed;
        try {
            if (text == null) throw new IllegalArgumentException("Invalid event UTF-8");
            if (text.isBlank()) return;
            event = StrictJson.parseCodexEvent(text).getAsJsonObject();
            String type = string(event, "type");
            if (type == null) throw new IllegalArgumentException("Missing event type");
            if (terminalBefore && (completed || !(type.equals("error") || type.equals("turn.failed"))))
                malformed = true;
            switch (type) {
                case "turn.completed" -> completed = true;
                case "turn.failed", "error" -> {
                    if (type.equals("turn.failed")) failed = true;
                    JsonObject error = type.equals("error") ? event : object(event, "error");
                    ProviderLimitException next = classify(error);
                    if (next != null) {
                        if (limit == null) limit = next;
                        else {
                            if (limit.kind() != next.kind()) mixedLimits = true;
                            if (limit.resetHint() == null && next.resetHint() != null) limit = next;
                        }
                    } else if (ProviderNetworkException.codexTransport(error)) networkFailure = true;
                    else unknownFailure = true;
                }
                case "item.completed" -> {
                    JsonObject item = object(event, "item");
                    if (item == null) throw new IllegalArgumentException("Missing item");
                    if ("agent_message".equals(string(item, "type"))
                            && !"commentary".equals(string(item, "phase")))
                        lastMessage = string(item, "text");
                }
                default -> { }
            }
        } catch (RuntimeException invalid) {
            malformed = true;
            return;
        }
        // Reporting failures deliberately escape parsing and provider classification.
        if (!malformed && !terminalBefore && activity != null) activity.accept(event);
    }

    String result(int exit) {
        if (ended) throw new IllegalStateException("Codex stream already ended");
        if (pending.size() > 0) line();
        ended = true;
        if (activity != null) activity.finish();
        if (!malformed && !unknownFailure && networkFailure && limit == null && !completed)
            throw new ProviderNetworkException("codex", "configured Codex endpoint",
                    ProviderNetworkException.Phase.UNCERTAIN);
        if (!malformed && !unknownFailure && !networkFailure && limit != null && !mixedLimits)
            throw limit;
        if (exit != 0 || malformed || failed || unknownFailure || !completed || lastMessage == null)
            throw new RuntimeException("codex exec failed or returned an incomplete event stream; exit code "
                    + exit + ". stderr: " + errors.toString(StandardCharsets.UTF_8)
                    + (errorsTruncated ? " [diagnostic truncated]" : "")
                    + "\nstdout JSONL: " + diagnostic.toString(StandardCharsets.UTF_8)
                    + (diagnosticTruncated ? " [diagnostic truncated]" : ""));
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
