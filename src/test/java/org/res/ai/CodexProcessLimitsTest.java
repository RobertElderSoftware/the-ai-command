package org.res.ai;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Fake subprocesses exercise transport without contacting any provider. */
class CodexProcessLimitsTest {
    @Test void fakeExecSuccessAndLimit() {
        try (var provider = new CodexExecLLMProvider(List.of("sh", "-c",
                "cat >/dev/null; printf '%s\\n' '{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"[]\"}}' '{\"type\":\"turn.completed\"}'"))) {
            assertEquals("[]", provider.complete("prompt"));
        }
        try (var provider = new CodexExecLLMProvider(List.of("sh", "-c",
                "cat >/dev/null; printf '%s\\n' '{\"type\":\"turn.failed\",\"error\":{\"code\":\"usage_limit_reached\"}}'; exit 1"))) {
            assertThrows(ProviderLimitException.class, () -> provider.complete("prompt"));
        }
    }

    @Test void fakeAppServerRequiresHandshakeAndReturnsWindows() {
        String script = "read init; printf '%s\\n' '{\"id\":1,\"result\":{}}'; "
                + "read initialized; read query; printf '%s\\n' "
                + "'{\"id\":2,\"result\":{\"rateLimits\":{\"limitId\":\"codex\",\"primary\":{\"usedPercent\":100,\"resetsAt\":1800000000}}}}'; sleep 1";
        assertEquals(1, CodexRateLimits.lookup(List.of("sh", "-c", script), "codex").size());
    }

    @Test void lookupTimeoutAndMalformedResponsesAreOptional() {
        assertTimeoutPreemptively(Duration.ofSeconds(6), () ->
                assertTrue(CodexRateLimits.lookup(List.of("sh", "-c", "sleep 30"), "codex").isEmpty()));
        assertTrue(CodexRateLimits.lookup(List.of("sh", "-c", "printf 'not JSON\\n'"), "codex").isEmpty());
        assertTrue(CodexRateLimits.lookup(List.of("sh", "-c", "exit 1"), "codex").isEmpty());
    }
}
