package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.res.ai.TestJson.*;

class ChangeContextsTest {
    private static final String POLICY = "{\"contexts\":[\"old.txt\"],\"permissions\":["
            + "{\"old.txt\":\"READ\"},{\"new.txt\":\"READ\"},{\"extra.txt\":\"READ\"},"
            + "{\"created.txt\":\"READ_WRITE\"}]}";

    private static RequestContext setup(Path directory) throws Exception {
        Files.writeString(directory.resolve("old.txt"), "old context");
        Files.writeString(directory.resolve("new.txt"), "new context");
        Files.writeString(directory.resolve("extra.txt"), "explicit context");
        Files.writeString(directory.resolve("custom.json"), POLICY);
        return RequestContext.load(directory, "custom.json");
    }

    private static String change(List<String> add, List<String> remove) {
        return batch(object("op", "change_contexts", "add", add, "remove", remove));
    }

    static void followUp(Path directory) throws Exception {
        RequestContext context = setup(directory);
        var permissions = context.permissionsDocument();
        int[] calls = {0};
        CapturingLLMProvider provider = new CapturingLLMProvider(prompt -> {
            int call = calls[0]++;
            if (call == 0) return change(List.of("new.txt", "created.txt"), List.of("old.txt"));
            assertTrue(prompt.contains("access=READ path=new.txt---"));
            assertTrue(prompt.contains("access=READ path=extra.txt---"));
            assertTrue(prompt.contains("access=READ_WRITE path=created.txt---"));
            assertFalse(prompt.contains("path=old.txt---"));
            if (call == 1) {
                assertTrue(prompt.contains("processed your change_contexts request"));
                return "[{\"op\":\"list_files\"}]";
            }
            return batch(stdout("done"));
        });
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var app = new AICommandApplication(provider, output, directory)) {
            app.run(new byte[0], List.of("extra.txt"), context);
        }
        assertEquals(3, provider.calls());
        RequestContext updated = RequestContext.load(directory, "custom.json");
        assertEquals(List.of("new.txt", "created.txt"), updated.files().stream().map(ContextFile::path).toList());
        assertEquals(permissions, updated.permissionsDocument());
        assertFalse(Files.exists(directory.resolve("context.json")));
        String printed = output.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("\u001b[32;4m"));
        assertTrue(printed.contains("Removed from context: \"old.txt\"\n"));
        assertTrue(printed.contains("Added to context: \"new.txt\"\n"));
        assertTrue(printed.contains("Sending the updated context back to the LLM."));
        assertTrue(printed.endsWith("done"));
    }

    @Test
    void updatesCustomConfigurationAndSupportsListingAfterward(@TempDir Path directory) throws Exception {
        followUp(directory);
    }

    @Test
    void invalidRequestsLeaveConfigurationAndStdoutUnchanged(@TempDir Path directory) throws Exception {
        RequestContext context = setup(directory);
        Files.createSymbolicLink(directory.resolve("link.txt"), directory.resolve("new.txt"));
        Files.createLink(directory.resolve("alias.txt"), directory.resolve("new.txt"));
        for (String response : List.of(
                change(List.of("denied.txt"), List.of("old.txt")),
                change(List.of("../outside.txt"), List.of()),
                change(List.of("link.txt"), List.of()),
                change(List.of("__ciop__/x"), List.of()),
                change(List.of("new.txt", "./new.txt"), List.of()),
                change(List.of("new.txt", "alias.txt"), List.of()),
                change(List.of("new.txt"), List.of("./new.txt")),
                change(List.of(), List.of("old.txt")),
                "[{\"op\":\"change_contexts\",\"add\":null,\"remove\":[]}]",
                "[{\"op\":\"change_contexts\",\"add\":[1],\"remove\":[]}]",
                "[{\"op\":\"change_contexts\",\"add\":[],\"remove\":[],\"path\":\"custom.json\"}]",
                batch(object("op", "change_contexts", "add", List.of("new.txt"), "remove", List.of()),
                        stdout("hidden")),
                batch(fileWrite("created.txt", "hidden"),
                        object("op", "change_contexts", "add", List.of("new.txt"), "remove", List.of())))) {
            var provider = new CapturingLLMProvider(response);
            var output = new ByteArrayOutputStream();
            try (var app = new AICommandApplication(provider, output, directory)) {
                assertThrows(IllegalArgumentException.class,
                        () -> app.run(new byte[0], List.of("old.txt"), context), response);
            }
            assertEquals(POLICY, Files.readString(directory.resolve("custom.json")));
            assertEquals(0, output.size());
            assertFalse(Files.exists(directory.resolve("created.txt")));
            assertEquals(1, provider.calls());
        }
    }

    @Test
    void addedReadFilesRemainProtectedAndConfigCannotBeWrittenAsAFile(@TempDir Path directory) throws Exception {
        for (String target : List.of("new.txt", "custom.json")) {
            RequestContext context = setup(directory);
            int[] calls = {0};
            var provider = new CapturingLLMProvider(prompt -> calls[0]++ == 0
                    ? change(List.of("new.txt"), List.of("old.txt"))
                    : batch(fileWrite(target, "forbidden")));
            try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
                assertThrows(IllegalArgumentException.class, () -> app.run(new byte[0], List.of(), context));
            }
            assertEquals("new context", Files.readString(directory.resolve("new.txt")));
            assertEquals(List.of("new.txt"), RequestContext.load(directory, "custom.json")
                    .files().stream().map(ContextFile::path).toList());
        }
    }

    @Test
    void missingAndIgnoredConfigurationsRejectChanges(@TempDir Path directory) throws Exception {
        for (boolean ignore : List.of(false, true)) {
            if (ignore) setup(directory);
            var provider = new CapturingLLMProvider(change(List.of("new.txt"), List.of()));
            try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
                assertThrows(IllegalArgumentException.class,
                        () -> app.runWithHistory(new byte[0], List.of(), "custom.json", true, ignore));
            }
            assertEquals(1, provider.calls());
            if (ignore) assertEquals(POLICY, Files.readString(directory.resolve("custom.json")));
            else assertFalse(Files.exists(directory.resolve("custom.json")));
        }
    }

    @Test
    void followUpFailuresKeepCommittedSelectionAndLimitTheLoop(@TempDir Path directory) throws Exception {
        for (boolean failProvider : List.of(false, true)) {
            RequestContext context = setup(directory);
            int[] calls = {0};
            var provider = new CapturingLLMProvider(prompt -> {
                if (calls[0]++ > 0 && failProvider) throw new IllegalStateException("unavailable");
                return change(List.of("new.txt"), List.of("old.txt"));
            });
            try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
                IOException failure = assertThrows(IOException.class,
                        () -> app.run(new byte[0], List.of(), context));
                assertTrue(failure.getMessage().contains("Context changes were committed"));
                assertTrue(failure.getMessage().contains("Do not retry"));
                if (!failProvider) assertTrue(failure.getCause().getMessage().contains("limit exceeded"));
            }
            assertEquals(failProvider ? 2 : 6, provider.calls());
            assertEquals(List.of("new.txt"), RequestContext.load(directory, "custom.json")
                    .files().stream().map(ContextFile::path).toList());
        }
    }

    @Test
    void historyRecordsChangesWithoutReAddingRemovedSelection(@TempDir Path directory) throws Exception {
        setup(directory);
        Files.createDirectory(directory.resolve("conversation_history"));
        Files.writeString(directory.resolve("custom.json"),
                contextJson(read("old.txt"), read("new.txt")));
        int[] calls = {0};
        var provider = new CapturingLLMProvider(prompt -> {
            if (calls[0]++ > 0) {
                assertFalse(prompt.contains("access=READ path=conversation_history/"));
                return "[]";
            }
            try {
                var history = RequestContext.load(directory, "custom.json").files().stream()
                        .map(ContextFile::path).filter(path -> path.startsWith("conversation_history/")).toList();
                assertEquals(1, history.size());
                return change(List.of(), history);
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        });
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            app.runWithHistory(new byte[0], List.of(), "custom.json");
        }
        assertEquals(List.of("old.txt", "new.txt"), RequestContext.load(directory, "custom.json")
                .files().stream().map(ContextFile::path).toList());
        try (var files = Files.list(directory.resolve("conversation_history"))) {
            String saved = Files.readString(files.findFirst().orElseThrow());
            assertTrue(saved.contains(" | contexts_changed | "));
            assertTrue(saved.contains("Removed from context:"));
            assertTrue(saved.contains(" | applied | "));
        }
    }
}
