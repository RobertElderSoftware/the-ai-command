package org.res.ai;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Boundary cases for optional reset metadata and diagnostic publication. */
class RateLimitBoundaryTest {
    private static ProviderLimitException limit() {
        return new ProviderLimitException("codex", ProviderLimitException.Kind.USAGE_LIMIT,
                "usage_limit_reached", null, null, "5:19 PM");
    }

    @Test void invalidPrimaryMetadataDoesNotDiscardValidSecondaryWindow() {
        for (String primary : List.of(
                "null", "[]", "{}",
                "{\"usedPercent\":99.99,\"resetsAt\":1800000000}",
                "{\"usedPercent\":\"100\",\"resetsAt\":1800000000}",
                "{\"usedPercent\":true,\"resetsAt\":1800000000}",
                "{\"usedPercent\":100,\"resetsAt\":\"1800000000\"}",
                "{\"usedPercent\":100,\"resetsAt\":null}",
                "{\"usedPercent\":100,\"resetsAt\":-1}",
                "{\"usedPercent\":100,\"resetsAt\":1800000000.5}",
                "{\"usedPercent\":100,\"resetsAt\":9223372036854775808}",
                "{\"usedPercent\":100,\"resetsAt\":9223372036854775807}")) {
            var result = StrictJson.parse("{\"rateLimits\":{\"limitId\":\"codex\",\"primary\":"
                    + primary + ",\"secondary\":{\"usedPercent\":100,\"resetsAt\":1800000100}}}")
                    .getAsJsonObject();
            var windows = CodexRateLimits.windows(result, "codex");
            // A valid secondary alone cannot establish a safe retry deadline
            // when the primary window contains unknown or invalid metadata.
            boolean usable = primary.equals("null") || primary.contains("99.99");
            assertEquals(usable ? 1 : 0, windows.size(), primary);
            if (!usable) continue;
            assertEquals(1, windows.size(), primary);
            assertEquals("secondary", windows.get(0).window(), primary);
            assertEquals(Instant.ofEpochSecond(1800000100), windows.get(0).resetsAt(), primary);
        }
    }

    @Test void explicitBucketMapPreventsFallbackToLegacyMetadata() {
        String legacy = "\"rateLimits\":{\"limitId\":\"codex\","
                + "\"primary\":{\"usedPercent\":100,\"resetsAt\":1800000000}}";
        for (String buckets : List.of("{}", "{\"other\":{\"limitId\":\"other\"}}",
                "{\"codex\":{\"limitId\":\"other\","
                        + "\"primary\":{\"usedPercent\":100,\"resetsAt\":1}}}")) {
            var result = StrictJson.parse("{" + legacy + ",\"rateLimitsByLimitId\":"
                    + buckets + "}").getAsJsonObject();
            assertTrue(CodexRateLimits.windows(result, "codex").isEmpty(), buckets);
        }
        var legacyOnly = StrictJson.parse("{" + legacy + "}").getAsJsonObject();
        assertEquals(1, CodexRateLimits.windows(legacyOnly, "codex").size());
    }

    @Test void enrichedHistoryRetainsBothWindowsWithoutAuthorizingReplay() {
        var enriched = limit().withWindows(List.of(
                new ProviderLimitException.ResetWindow("codex", "primary",
                        Instant.ofEpochSecond(1800000000)),
                new ProviderLimitException.ResetWindow("codex", "secondary",
                        Instant.ofEpochSecond(1800000100))));
        var history = enriched.history(true);
        assertEquals("usage_limit", history.get("kind").getAsString());
        assertEquals("usage_limit_reached", history.get("providerCode").getAsString());
        assertEquals("5:19 PM", history.get("resetHint").getAsString());
        assertTrue(history.get("contextCommitted").getAsBoolean());
        assertFalse(history.get("automaticReplayAuthorized").getAsBoolean());
        assertFalse(history.has("retryAt"));
        assertFalse(history.has("retryAfter"));
        var windows = history.getAsJsonArray("resetWindows");
        assertEquals(2, windows.size());
        for (int index = 0; index < windows.size(); index++) {
            var window = windows.get(index).getAsJsonObject();
            assertEquals(enriched.resetWindows().get(index).window(), window.get("window").getAsString());
            assertEquals(enriched.resetWindows().get(index).resetsAt(),
                    Instant.parse(window.get("resetsAt").getAsString()));
        }
    }

    @Test void suppressedFailureOnCommitWrapperRetainsFullDiagnostics() {
        var failure = new ContextCommittedException(limit());
        failure.addSuppressed(new IOException("history publication failed"));
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        assertNull(FailureReporting.limit(failure));
        assertEquals(1, FailureReporting.report(failure, new PrintStream(out), new PrintStream(err)));
        assertEquals(0, out.size());
        String diagnostic = err.toString(StandardCharsets.UTF_8);
        assertTrue(diagnostic.contains("Context changes were committed"));
        assertTrue(diagnostic.contains("Suppressed: java.io.IOException: history publication failed"));
        assertTrue(diagnostic.contains("Caused by: org.res.ai.ProviderLimitException"));
    }

    @Test void brokenStdoutCannotReportSuccessfulLimitDiagnosticPublication() {
        var err = new ByteArrayOutputStream();
        try (var broken = new PrintStream(new OutputStream() {
            @Override public void write(int value) throws IOException {
                throw new IOException("output unavailable");
            }
        })) {
            assertEquals(1, FailureReporting.report(limit(), broken, new PrintStream(err)));
        }
        String diagnostic = err.toString(StandardCharsets.UTF_8);
        assertTrue(diagnostic.contains("Could not publish the provider-limit diagnostic."));
        assertTrue(diagnostic.contains("The AI command failed:"));
        assertTrue(diagnostic.contains("org.res.ai.ProviderLimitException"));
    }
}
