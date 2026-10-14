package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextInitializationTest {
    @TempDir Path directory;

    private static Map<String, String> options(String... args) {
        return TheAICommand.parseOptions(args, new ArrayList<>());
    }

    @Test
    void createsDefaultAndCustomConfigurationsAndPreservesExistingBytes() throws Exception {
        for (String[] args : List.of(
                new String[] {"--enable-context"},
                new String[] {"--enable-context=custom.json"},
                new String[] {"--enable-context", "nested/config.json"},
                new String[] {"--enable-context=first.json", "--context=last.json"},
                new String[] {"--context=unused.json", "--enable-context"})) {
            var parsed = options(args);
            ContextInitialization.prepare(directory, parsed);
            Path target = directory.resolve(parsed.get("--context"));
            RequestContext context = RequestContext.load(directory, parsed.get("--context"));
            assertTrue(context.hasPermissions());
            assertTrue(context.files().isEmpty());
            assertFalse(context.canWrite(directory, "unlisted.txt"));
            String original = "{\n  \"contexts\": [], \"permissions\": []\n}\n";
            Files.writeString(target, original);
            ContextInitialization.prepare(directory, parsed);
            assertEquals(original, Files.readString(target));
        }
        assertFalse(Files.exists(directory.resolve("first.json")));
        assertFalse(Files.exists(directory.resolve("unused.json")));
    }

    @Test
    void implicitContextIgnoreAndHelpDoNotCreateFiles() throws Exception {
        for (String[] args : List.of(new String[0],
                new String[] {"--context=custom.json"},
                new String[] {"--enable-context", "--ignore-context"},
                new String[] {"--enable-context", "--help"}))
            ContextInitialization.prepare(directory, options(args));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
        List<String> files = new ArrayList<>();
        var parsed = TheAICommand.parseOptions(
                new String[] {"--enable-context", "--", "input.txt"}, files);
        assertEquals("context.json", parsed.get("--context"));
        assertEquals(List.of("input.txt"), files);
        assertThrows(IllegalArgumentException.class, () -> options("--enable-context="));
    }

    @Test
    void validatesPathsAndDoesNotReplaceMalformedConfigurations() throws Exception {
        Path existing = Files.writeString(directory.resolve("existing.json"), "not JSON");
        ContextInitialization.prepare(directory, options("--enable-context=existing.json"));
        assertEquals("not JSON", Files.readString(existing));
        Files.createSymbolicLink(directory.resolve("link.json"), existing);
        Files.createDirectory(directory.resolve("folder"));
        for (String name : List.of("../escaped.json", "__ciop__/config.json",
                "conversation_history/config.json", "link.json", "folder",
                existing.toAbsolutePath().toString())) {
            assertThrows(IllegalArgumentException.class,
                    () -> ContextInitialization.prepare(directory, options("--enable-context=" + name)));
        }
        assertEquals("not JSON", Files.readString(existing));
    }

    @Test
    void createdConfigurationIsLoadedAndHistoryIsRegisteredBeforeDispatch() throws Exception {
        ContextInitialization.prepare(directory, options("--enable-context"));
        CapturingLLMProvider provider = new CapturingLLMProvider("[]");
        try (AICommandApplication application = new AICommandApplication(
                provider, new ByteArrayOutputStream(), directory)) {
            application.runWithHistory(new byte[0], List.of(), "context.json", false, false, true);
        }
        assertEquals(1, provider.calls());
        assertTrue(provider.prompt().contains("path=" + RequestContext.PERMISSIONS_PATH));
        assertTrue(provider.prompt().contains("access=READ path=conversation_history/"));
        RequestContext context = RequestContext.load(directory, "context.json");
        assertFalse(context.files().isEmpty());
        assertTrue(context.files().stream().allMatch(file -> file.access() == FileAccess.READ));
    }
}
