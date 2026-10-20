package org.res.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Observes Codex command items; this is not an OS process/descriptor audit. */
final class CodexCommandActivity {
    static final String MEMORY_MARKER = "CIOP_MEMORY_FILE ";
    private final ProviderActivity.Listener listener;
    private final boolean enabled;
    private final boolean output;
    private final boolean memory;
    private final LongSupplier clock;
    private final String attempt = UUID.randomUUID().toString();
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private boolean ended;

    private static final class Command {
        Long started;
        boolean completed;
    }

    CodexCommandActivity(ProviderActivity.Listener listener) {
        this(listener, Boolean.parseBoolean(System.getProperty("ai.codex.exec.status", "true")),
                Boolean.parseBoolean(System.getProperty("ai.codex.exec.output", "false")),
                Boolean.parseBoolean(System.getProperty("ai.codex.exec.memory", "false")), System::nanoTime);
    }

    CodexCommandActivity(ProviderActivity.Listener listener, boolean enabled,
            boolean output, boolean memory, LongSupplier clock) {
        this.listener = listener;
        this.enabled = enabled;
        this.output = output;
        this.memory = memory;
        this.clock = clock;
    }

    void accept(JsonObject event) {
        if (!enabled || ended) return;
        String type = CodexEvents.string(event, "type");
        if (!"item.started".equals(type) && !"item.updated".equals(type)
                && !"item.completed".equals(type)) return;
        JsonObject item = CodexEvents.object(event, "item");
        if (!"command_execution".equals(CodexEvents.string(item, "type"))) return;
        String id = CodexEvents.string(item, "id");
        if (id == null || id.isBlank() || id.length() > 1024)
            throw new IllegalArgumentException("Command event requires a bounded item ID");
        Command command = commands.get(id);
        if (command == null) {
            if (commands.size() >= 10000)
                throw new IllegalArgumentException("Codex command tracking limit exceeded (10000 items)");
            command = new Command();
            commands.put(id, command);
        }
        if (command.completed) return;
        boolean done = "item.completed".equals(type);
        if (!done && command.started == null) {
            command.started = clock.getAsLong();
            emit("started", id, "started: " + ProviderActivity.safe(CodexEvents.string(item, "command"), 512));
        }
        if (done) {
            command.completed = true;
            String elapsed = command.started == null ? "unavailable"
                    : String.format(Locale.ROOT, "%.3fs", Math.max(0, clock.getAsLong() - command.started) / 1e9);
            emit("completed", id, "completed: exit " + exit(item)
                    + "; status " + ProviderActivity.safe(CodexEvents.string(item, "status"), 64)
                    + "; observed elapsed " + elapsed);
            String reported = CodexEvents.string(item, "aggregated_output");
            if (reported != null) {
                if (output && !reported.isEmpty())
                    emit("output", id, "output: " + ProviderActivity.safe(reported, 4096));
                if (memory) memory(id, reported);
            }
        }
    }

    private static String exit(JsonObject item) {
        JsonElement value = item.get("exit_code");
        if (value == null || value.isJsonNull()) return "unavailable";
        try {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return "unavailable";
            return Integer.toString(value.getAsBigDecimal().intValueExact());
        } catch (ArithmeticException | NumberFormatException failure) {
            return "unavailable";
        }
    }

    private void memory(String id, String output) {
        int end = Math.min(output.length(), 65536);
        int cursor = 0;
        int count = 0;
        while (cursor < end) {
            int newline = output.indexOf('\n', cursor);
            int stop = newline < 0 ? output.length() : newline;
            if (stop > end) break;
            String line = output.substring(cursor, stop);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.startsWith(MEMORY_MARKER)) {
                if (++count > 16) {
                    emit("memory", id, "program-reported memory-file notices truncated (16 reports)");
                    return;
                }
                String report = memoryReport(line.substring(MEMORY_MARKER.length()));
                emit("memory", id, report);
            }
            cursor = stop + 1;
        }
        if (output.length() > end)
            emit("memory", id, "memory-file marker scan truncated (65536 UTF-16 units)");
    }

    private static String memoryReport(String json) {
        try {
            JsonObject report = StrictJson.parse(json).getAsJsonObject();
            String label = CodexEvents.string(report, "label");
            String purpose = CodexEvents.string(report, "purpose");
            JsonElement size = report.get("bytes");
            if (report.size() != 3 || label == null || purpose == null || size == null
                    || !size.isJsonPrimitive() || !size.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException();
            long bytes = size.getAsBigDecimal().longValueExact();
            if (bytes < 0) throw new IllegalArgumentException();
            return "program-reported memory file (unverified): " + ProviderActivity.safe(label, 128)
                    + "; bytes " + bytes + "; purpose " + ProviderActivity.safe(purpose, 256);
        } catch (RuntimeException invalid) {
            return "ignored invalid program-reported memory-file marker";
        }
    }

    void finish() {
        if (ended) return;
        ended = true;
        for (var entry : commands.entrySet()) {
            if (!entry.getValue().completed)
                emit("incomplete", entry.getKey(), "completion not observed; exit status unknown");
        }
    }

    private void emit(String event, String id, String detail) {
        ProviderActivity.emit(listener, "execution_" + event,
                "# exec: attempt " + attempt + " [" + ProviderActivity.safe(id, 96) + "]: " + detail);
    }
}
