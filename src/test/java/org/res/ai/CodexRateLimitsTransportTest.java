package org.res.ai;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Offline transport regressions: no live account or quota-consuming requests. */
class CodexRateLimitsTransportTest {
    private static final String HANDSHAKE =
            "IFS= read -r init; "
            + "[ \"$init\" = '{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"the_ai_command\",\"version\":\"1.0\"}}}' ] || exit 11; "
            + "printf '%s\\n' '{\"method\":\"notice\",\"params\":{}}' '{\"id\":99,\"result\":{}}' '{\"id\":1,\"result\":{}}'; "
            + "IFS= read -r initialized; "
            + "[ \"$initialized\" = '{\"method\":\"initialized\",\"params\":{}}' ] || exit 12; "
            + "IFS= read -r query; "
            + "[ \"$query\" = '{\"id\":2,\"method\":\"account/rateLimits/read\"}' ] || exit 13; ";
    private static final String REPLY = "{\"id\":2,\"result\":{\"rateLimitsByLimitId\":{\"codex\":{"
            + "\"limitId\":\"codex\",\"primary\":{\"usedPercent\":100,\"resetsAt\":1800000000},"
            + "\"secondary\":{\"usedPercent\":100,\"resetsAt\":1800000100}}}}}";

    @Test void immediateExitPreservesFinalReplyAndBothWindows() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            for (int attempt = 0; attempt < 20; attempt++) {
                var windows = CodexRateLimits.lookup(List.of("sh", "-c",
                        HANDSHAKE + "printf '%s\\n' '" + REPLY + "'"), "codex");
                assertEquals(2, windows.size(), "attempt " + attempt);
                assertEquals("primary", windows.get(0).window());
                assertEquals("secondary", windows.get(1).window());
                assertEquals(Instant.ofEpochSecond(1800000000), windows.get(0).resetsAt());
                assertEquals(Instant.ofEpochSecond(1800000100), windows.get(1).resetsAt());
            }
        });
    }

    @Test void fragmentedReplyIsReassembledAcrossPolls() {
        String first = REPLY.substring(0, 37);
        String rest = REPLY.substring(37);
        var windows = CodexRateLimits.lookup(List.of("sh", "-c", HANDSHAKE
                + "printf '%s' '" + first + "'; sleep 0.05; printf '%s\\n' '" + rest + "'"), "codex");
        assertEquals(2, windows.size());
    }

    @Test void rpcErrorsAndUnrelatedBucketsLeaveMetadataUnavailable() {
        for (String reply : List.of(
                "{\"id\":2,\"error\":{\"code\":-32601,\"message\":\"unsupported\"}}",
                "{\"id\":2,\"result\":{\"rateLimits\":{\"limitId\":\"other\","
                        + "\"primary\":{\"usedPercent\":100,\"resetsAt\":1800000000}}}}",
                "{\"id\":2,\"result\":null}")) {
            assertTrue(CodexRateLimits.lookup(List.of("sh", "-c", HANDSHAKE
                    + "printf '%s\\n' '" + reply + "'"), "codex").isEmpty());
        }
    }
}
