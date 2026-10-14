package org.res.ai;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Remaining offline reset-lookup cases: malformed metadata and cancellation. */
class CodexRateLimitsEdgeCasesTest {
    private static JsonObject json(String text) {
        return StrictJson.parse(text).getAsJsonObject();
    }

    @Test void malformedPrimaryDoesNotDiscardValidSecondary() {
        for (String primary : List.of(
                "null", "[]", "{}",
                "{\"usedPercent\":\"100\",\"resetsAt\":1800000000}",
                "{\"usedPercent\":null,\"resetsAt\":1800000000}",
                "{\"usedPercent\":99.999,\"resetsAt\":1800000000}",
                "{\"usedPercent\":100,\"resetsAt\":\"1800000000\"}",
                "{\"usedPercent\":100,\"resetsAt\":null}",
                "{\"usedPercent\":100,\"resetsAt\":-1}",
                "{\"usedPercent\":100,\"resetsAt\":1800000000.5}",
                "{\"usedPercent\":100,\"resetsAt\":9223372036854775808}",
                "{\"usedPercent\":100,\"resetsAt\":9223372036854775807}")) {
            JsonObject result = json("{\"rateLimits\":{\"limitId\":\"codex\",\"primary\":"
                    + primary + ",\"secondary\":{\"usedPercent\":100,\"resetsAt\":1800000100}}}");
            var windows = CodexRateLimits.windows(result, "codex");
            // Absent or unexhausted primary windows permit using the secondary.
            // Malformed metadata must block retries: its reset may be later.
            boolean usable = primary.equals("null") || primary.contains("99.999");
            assertEquals(usable ? 1 : 0, windows.size(), primary);
            if (!usable) continue;
            assertEquals(1, windows.size(), primary);
            assertEquals("secondary", windows.get(0).window(), primary);
            assertEquals(Instant.ofEpochSecond(1800000100), windows.get(0).resetsAt(), primary);
        }
    }

    @Test void bucketMapTakesPrecedenceOverLegacySnapshot() {
        JsonObject result = json("{\"rateLimits\":{\"limitId\":\"codex\","
                + "\"primary\":{\"usedPercent\":100,\"resetsAt\":1800000000}},"
                + "\"rateLimitsByLimitId\":{}}");
        assertTrue(CodexRateLimits.windows(result, "codex").isEmpty());
        JsonObject buckets = result.getAsJsonObject("rateLimitsByLimitId");
        buckets.add("codex", json("{\"limitId\":\"other\","
                + "\"primary\":{\"usedPercent\":100,\"resetsAt\":1800000000}}"));
        assertTrue(CodexRateLimits.windows(result, "codex").isEmpty());
        buckets.getAsJsonObject("codex").addProperty("limitId", "codex");
        assertEquals(1, CodexRateLimits.windows(result, "codex").size());
        result.remove("rateLimitsByLimitId");
        assertEquals(1, CodexRateLimits.windows(result, "codex").size());
    }

    @Test void stalledQueryAfterInitializationRemainsTimeBounded() {
        String script = "IFS= read -r init; printf '%s\\n' '{\"id\":1,\"result\":{}}'; "
                + "IFS= read -r initialized; IFS= read -r query; sleep 30";
        assertTimeoutPreemptively(Duration.ofSeconds(6), () ->
                assertTrue(CodexRateLimits.lookup(List.of("sh", "-c", script), "codex").isEmpty()));
    }

    @Test void interruptionPreservesFlagAndReturnsWithoutResetMetadata() {
        assertTimeoutPreemptively(Duration.ofSeconds(6), () -> {
            Thread.currentThread().interrupt();
            try {
                assertTrue(CodexRateLimits.lookup(List.of("sh", "-c", "sleep 30"), "codex").isEmpty());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                // Do not leak the intentional interrupt into the test executor.
                Thread.interrupted();
            }
        });
    }
}
