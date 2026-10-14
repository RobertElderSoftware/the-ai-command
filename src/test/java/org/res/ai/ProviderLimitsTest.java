package org.res.ai;

import com.google.gson.JsonObject;
import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import com.openai.models.ErrorObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProviderLimitsTest {
    private static final String MESSAGE = "You've hit your usage limit. Upgrade to Pro or try again at 5:19 PM.";

    private static String error(String type, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("message", message);
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        if (type.equals("error")) event.addProperty("message", message);
        else event.add("error", error);
        return event + "\n";
    }

    private static String item(String type, String text) {
        JsonObject item = new JsonObject();
        item.addProperty("type", type);
        item.addProperty("text", text);
        JsonObject event = new JsonObject();
        event.addProperty("type", "item.completed");
        event.add("item", item);
        return event + "\n";
    }

    private static ProviderLimitException limit() {
        return new ProviderLimitException("codex", ProviderLimitException.Kind.USAGE_LIMIT,
                null, null, null, "5:19 PM");
    }

    @Test void duplicatedLimitAndFinalResponseExtraction() {
        ProviderLimitException failure = assertThrows(ProviderLimitException.class,
                () -> CodexEvents.response(1, error("error", MESSAGE) + error("turn.failed", MESSAGE), "secret context"));
        assertEquals("5:19 PM", failure.resetHint());
        assertNull(failure.retryAt());
        assertNull(failure.getCause());
        assertFalse(failure.display().contains("secret"));
        assertEquals("[]", CodexEvents.response(0,
                "{\"type\":\"turn.started\"}\n" + item("agent_message", "progress")
                        + item("command_execution", MESSAGE) + item("agent_message", "[]")
                        + "{\"type\":\"turn.completed\"}\n", MESSAGE));
    }

    @Test void unknownMalformedAndIncompleteRunsStayVerbose() {
        for (String stream : List.of(
                item("command_execution", MESSAGE),
                item("agent_message", MESSAGE),
                error("error", "authentication failed"),
                error("error", MESSAGE) + "not JSON\n",
                error("error", MESSAGE) + error("turn.failed", "disk failed"),
                item("agent_message", "[]"),
                "{\"type\":\"turn.completed\",\"type\":\"error\"}\n")) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> CodexEvents.response(1, stream, "ordinary diagnostic"));
            assertFalse(failure instanceof ProviderLimitException, stream);
            assertTrue(failure.getMessage().contains("ordinary diagnostic"));
            assertTrue(failure.getMessage().contains(stream));
        }
        assertThrows(RuntimeException.class, () -> CodexEvents.response(0,
                item("agent_message", "[]"), ""));
        assertThrows(RuntimeException.class, () -> CodexEvents.response(0,
                item("agent_message", "[]") + "{\"type\":\"turn.completed\"}\n"
                        + item("agent_message", "late"), ""));
    }

    @Test void messageMatchingAndHintsAreConservative() {
        JsonObject error = new JsonObject();
        error.addProperty("message", "Quoted: " + MESSAGE);
        assertNull(CodexEvents.classify(error));
        error.addProperty("message", MESSAGE);
        error.addProperty("code", "authentication_failed");
        assertNull(CodexEvents.classify(error));
        error.remove("code");
        error.addProperty("message", "You've hit your usage limit. Try later.");
        assertNull(CodexEvents.classify(error).resetHint());
        error.addProperty("code", "rate_limit_exceeded");
        assertEquals(ProviderLimitException.Kind.RATE_LIMIT, CodexEvents.classify(error).kind());
    }

    @Test void onlyKnownWrapperIsConciseAndSecondaryFailuresRemainVisible() {
        for (boolean committed : List.of(false, true)) {
            Throwable failure = committed ? new ContextCommittedException(limit()) : limit();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertEquals(3, FailureReporting.report(failure, new PrintStream(out), new PrintStream(err)));
            assertEquals(0, err.size());
            assertTrue(out.toString().contains("5:19 PM"));
            assertEquals(committed, out.toString().contains("already saved"));
        }
        for (Throwable failure : List.of(new RuntimeException("unrelated wrapper", limit()),
                new ContextCommittedException(new IllegalStateException("ordinary failure")), limit())) {
            if (failure instanceof ProviderLimitException) failure.addSuppressed(new IllegalStateException("cleanup failed"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertEquals(1, FailureReporting.report(failure, new PrintStream(out), new PrintStream(err)));
            assertEquals(0, out.size());
            assertTrue(err.toString().contains(failure.getClass().getName()));
            assertTrue(err.toString().contains("ProviderLimitsTest"));
        }
        ProviderLimitException nested = limit();
        nested.addSuppressed(new IllegalStateException("history failed"));
        assertNull(FailureReporting.limit(new ContextCommittedException(nested)));
    }

    @Test void openaiCodesAndRetryHeaders() {
        assertEquals(ProviderLimitException.Kind.QUOTA_EXHAUSTED,
                OpenAILimits.kind(429, "insufficient_quota", null));
        assertEquals(ProviderLimitException.Kind.RATE_LIMIT,
                OpenAILimits.kind(429, "rate_limit_exceeded", null));
        assertNull(OpenAILimits.kind(429, null, null));
        assertNull(OpenAILimits.kind(401, "rate_limit_exceeded", null));
        assertNull(OpenAILimits.kind(500, "insufficient_quota", null));
        for (String header : List.of("60", "Wed, 21 Oct 2015 07:28:00 GMT", "nonsense", "-1")) {
            RateLimitException sdk = RateLimitException.builder()
                    .headers(Headers.builder().put("retry-after", header).build())
                    .error(ErrorObject.builder().message("secret provider response")
                            .type("rate_limit_exceeded").code("rate_limit_exceeded").param((String) null).build())
                    .build();
            ProviderLimitException normalized = assertInstanceOf(ProviderLimitException.class, OpenAILimits.normalize(sdk));
            assertEquals(header.equals("60") ? Duration.ofSeconds(60) : null, normalized.retryAfter());
            assertEquals(header.startsWith("Wed") ? Instant.parse("2015-10-21T07:28:00Z") : null, normalized.retryAt());
            assertFalse(normalized.display().contains("secret"));
            sdk.addSuppressed(new IllegalStateException("separate failure"));
            assertSame(sdk, OpenAILimits.normalize(sdk));
        }
    }

    @Test void resetLookupKeepsBothMatchingExhaustedWindows() {
        JsonObject result = StrictJson.parse("{\"rateLimitsByLimitId\":{\"codex\":{\"limitId\":\"codex\","
                + "\"primary\":{\"usedPercent\":100,\"resetsAt\":1800000000},"
                + "\"secondary\":{\"usedPercent\":101,\"resetsAt\":1800000100}},"
                + "\"other\":{\"limitId\":\"other\",\"primary\":{\"usedPercent\":100,\"resetsAt\":1}}}}")
                .getAsJsonObject();
        var windows = CodexRateLimits.windows(result, "codex");
        assertEquals(2, windows.size());
        assertEquals(Instant.ofEpochSecond(1800000100), windows.get(1).resetsAt());
        assertTrue(CodexRateLimits.windows(result, "missing").isEmpty());
        result.getAsJsonObject("rateLimitsByLimitId").getAsJsonObject("codex")
                .getAsJsonObject("primary").addProperty("usedPercent", 20);
        assertEquals(1, CodexRateLimits.windows(result, "codex").size());
    }

    @Test void limitHistoryIsCompactAndContextCommitIsPreserved(@TempDir Path directory) throws Exception {
        Files.createDirectory(directory.resolve("conversation_history"));
        Files.writeString(directory.resolve("context.json"),
                "{\"contexts\":[],\"permissions\":[{\"new.txt\":\"READ_WRITE\"}]}");
        int[] calls = {0};
        CapturingLLMProvider provider = new CapturingLLMProvider(prompt -> {
            if (calls[0]++ == 0) return "[{\"op\":\"change_contexts\",\"add\":[\"new.txt\"],\"remove\":[]}]";
            throw limit();
        });
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            ContextCommittedException failure = assertThrows(ContextCommittedException.class,
                    () -> app.runWithHistory("user input".getBytes(StandardCharsets.UTF_8), List.of(), null));
            assertNotNull(FailureReporting.limit(failure));
        }
        assertEquals(2, calls[0]);
        assertTrue(RequestContext.load(directory, "context.json").files().stream()
                .anyMatch(file -> file.path().equals("new.txt")));
        String saved;
        try (var files = Files.list(directory.resolve("conversation_history"))) {
            saved = Files.readString(files.findFirst().orElseThrow());
        }
        assertEquals(1, saved.split(" \\| provider_limit \\| ", -1).length - 1);
        assertTrue(saved.contains("\"contextCommitted\":true"));
        assertFalse(saved.contains(" | applied | "));
        assertFalse(saved.contains("ProviderLimitException"));
        assertFalse(Files.exists(directory.resolve("new.txt")));
    }

    @Test void ordinaryHistoryNeverCopiesExceptionMessages(@TempDir Path directory) throws Exception {
        Files.createDirectory(directory.resolve("conversation_history"));
        ConversationHistory history = new ConversationHistory(directory, null);
        history.failed(new RuntimeException("SECRET CONTEXT".repeat(10000)));
        try (var files = Files.list(directory.resolve("conversation_history"))) {
            String saved = Files.readString(files.findFirst().orElseThrow());
            assertTrue(saved.length() < 500);
            assertTrue(saved.contains(" | failed | "));
            assertFalse(saved.contains("SECRET"));
        }
    }

    @Test void historyWriteFailurePreventsConciseSuppression(@TempDir Path directory) throws Exception {
        Path history = Files.createDirectory(directory.resolve("conversation_history"));
        CapturingLLMProvider provider = new CapturingLLMProvider(prompt -> {
            try (var files = Files.list(history)) {
                Path file = files.findFirst().orElseThrow();
                Files.delete(file);
                Files.createDirectory(file);
            } catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
            throw limit();
        });
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            ProviderLimitException failure = assertThrows(ProviderLimitException.class,
                    () -> app.runWithHistory(new byte[0], List.of(), null));
            assertEquals(1, failure.getSuppressed().length);
            assertNull(FailureReporting.limit(failure));
        }
    }
}
