package org.res.ai;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Optional read-only App Server query; no prompts, retries, or reset-credit consumption. */
final class CodexRateLimits {
    private CodexRateLimits() { }

    static List<ProviderLimitException.ResetWindow> lookup(List<String> command, String bucket) {
        try (ShellProcessRunner runner = new ShellProcessRunner(command, null, null, true,
                Duration.ofSeconds(3), 262144, 262144)) {
            OutputStream input = runner.getOutputStreamForStdin();
            send(input, "{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"the_ai_command\",\"version\":\"1.0\"}}}");
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            boolean initialized = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline) {
                // At EOF the readers transfer remaining bytes into their futures.
                // Consume those final bytes once completion is observed; a partial
                // read alone cannot retrieve them. Preserve pending fragments.
                boolean finished = runner.isFinished();
                byte[] bytes = finished
                        ? runner.getFinalResult().getOutput().getStdoutOutput()
                        : runner.getPartialResult().getStdoutOutput();
                for (byte value : bytes) {
                    if (value != '\n') {
                        pending.write(value);
                        continue;
                    }
                    String line = pending.toString(StandardCharsets.UTF_8);
                    pending.reset();
                    if (line.isBlank()) continue;
                    JsonObject message = StrictJson.parse(line).getAsJsonObject();
                    if (!message.has("id") || !message.get("id").isJsonPrimitive()) continue;
                    String id = message.get("id").getAsString();
                    if (!id.equals(initialized ? "2" : "1")) continue;
                    if (message.has("error")) return List.of();
                    JsonObject result = CodexEvents.object(message, "result");
                    if (result == null) return List.of();
                    if (!initialized) {
                        send(input, "{\"method\":\"initialized\",\"params\":{}}");
                        send(input, "{\"id\":2,\"method\":\"account/rateLimits/read\"}");
                        initialized = true;
                    } else return windows(result, bucket);
                }
                if (finished) return List.of();
                Thread.sleep(10);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Optional enrichment never replaces the original limit failure.
        }
        return List.of();
    }

    private static void send(OutputStream output, String json) throws java.io.IOException {
        output.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    static List<ProviderLimitException.ResetWindow> windows(JsonObject result, String bucket) {
        List<ProviderLimitException.ResetWindow> windows = new ArrayList<>();
        JsonObject all = CodexEvents.object(result, "rateLimitsByLimitId");
        JsonObject selected = all == null ? CodexEvents.object(result, "rateLimits")
                : CodexEvents.object(all, bucket);
        if (selected == null || !bucket.equals(CodexEvents.string(selected, "limitId"))) return List.of();
        for (String name : List.of("primary", "secondary")) {
            JsonObject window = CodexEvents.object(selected, name);
            if (window == null) {
                if (selected.has(name) && !selected.get(name).isJsonNull()) return List.of();
                continue;
            }
            try {
                if (!window.get("usedPercent").getAsJsonPrimitive().isNumber()) return List.of();
                if (window.get("usedPercent").getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(100)) < 0) continue;
                if (!window.get("resetsAt").getAsJsonPrimitive().isNumber()) return List.of();
                long seconds = window.get("resetsAt").getAsBigDecimal().longValueExact();
                if (seconds < 0) return List.of();
                windows.add(new ProviderLimitException.ResetWindow(bucket, name, Instant.ofEpochSecond(seconds)));
            } catch (RuntimeException ignored) { return List.of(); }
        }
        return List.copyOf(windows);
    }
}
