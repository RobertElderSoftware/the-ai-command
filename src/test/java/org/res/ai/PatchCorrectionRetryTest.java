package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.res.ai.TestJson.*;

class PatchCorrectionRetryTest {
    @TempDir Path directory;

    private void setup() throws Exception {
        Files.writeString(directory.resolve("work.txt"), "original\n");
        Files.writeString(directory.resolve("context.json"), contextJson(readWrite("work.txt")));
    }

    private String bad() {
        return batch(fileWrite("created.txt", "discarded"), stdout("HIDDEN"),
                replaceFirstLine("work.txt", "original\n", "wrong", "changed"));
    }

    private String history() throws IOException {
        StringBuilder result = new StringBuilder();
        try (var files = Files.list(directory.resolve(ConversationHistory.DIRECTORY))) {
            for (Path file : files.sorted().toList()) result.append(Files.readString(file));
        }
        return result.toString();
    }

    @Test void recordsDiagnosticBeforeRetryAndAppliesOnlyCorrectedBatch() throws Exception {
        setup();
        Files.createDirectory(directory.resolve(ConversationHistory.DIRECTORY));
        int[] calls = {0};
        String[] original = {null};
        var provider = new CapturingLLMProvider(prompt -> {
            if (calls[0]++ == 0) { original[0] = prompt; return bad(); }
            try {
                assertEquals("original\n", Files.readString(directory.resolve("work.txt")));
                assertFalse(Files.exists(directory.resolve("created.txt")));
                String saved = history();
                assertTrue(saved.contains(" | patch_rejected | "));
                assertTrue(saved.contains("Operation 3/3"));
                assertTrue(saved.contains("source line 1"));
                assertTrue(saved.contains("patch text=\"wrong\""));
                assertTrue(saved.contains("source text=\"original\""));
                assertFalse(saved.contains("\u001b"));
                assertFalse(saved.contains(" | applied | "));
            } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            assertTrue(prompt.contains("access=READ path=__ciop__/PATCH_FAILURE.txt---"));
            String marker = "---SECTION STDIN ";
            assertEquals(original[0].substring(original[0].lastIndexOf(marker)),
                    prompt.substring(prompt.lastIndexOf(marker)));
            return batch(replaceFirstLine("work.txt", "original\n", "original", "changed"), stdout("done"));
        });
        var output = new ByteArrayOutputStream();
        try (var app = new AICommandApplication(provider, output, directory)) {
            app.runWithHistory("Fix this file: café".getBytes(StandardCharsets.UTF_8), List.of(), "context.json");
        }
        assertEquals(2, provider.calls());
        assertEquals("changed\n", Files.readString(directory.resolve("work.txt")));
        assertFalse(Files.exists(directory.resolve("created.txt")));
        String printed = output.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("\u001b[32;4m"));
        assertTrue(printed.contains("patch retry 1/3"));
        assertTrue(printed.endsWith("\u001b[0m\ndone"));
        assertFalse(printed.contains("HIDDEN"));
        assertEquals(1, history().split(" \\| applied \\| ", -1).length - 1);
    }

    @Test void boundsRetriesAndHonoursDisableFlag() throws Exception {
        setup();
        for (boolean enabled : List.of(false, true)) {
            var provider = new CapturingLLMProvider(bad());
            var output = new ByteArrayOutputStream();
            try (var app = new AICommandApplication(provider, output, directory)) {
                app.setAutomaticRetry(enabled);
                assertThrows(IllegalArgumentException.class, () -> app.run(new byte[0], List.of(),
                        RequestContext.load(directory, "context.json")));
            }
            assertEquals(enabled ? 4 : 1, provider.calls());
            assertEquals("original\n", Files.readString(directory.resolve("work.txt")));
            assertFalse(Files.exists(directory.resolve("created.txt")));
            if (!enabled) assertEquals(0, output.size());
            else assertTrue(output.toString(StandardCharsets.UTF_8).contains("patch retry 3/3"));
        }
    }

    @Test void disabledRetryStillRecordsPatchDetails() throws Exception {
        setup();
        Files.createDirectory(directory.resolve(ConversationHistory.DIRECTORY));
        var provider = new CapturingLLMProvider(bad());
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            app.setAutomaticRetry(false);
            assertThrows(IllegalArgumentException.class, () -> app.runWithHistory(new byte[0], List.of(), "context.json"));
        }
        assertEquals(1, provider.calls());
        assertTrue(history().contains(" | patch_rejected | "));
        assertTrue(history().contains("source_sha256 matched"));
        assertTrue(history().contains("No automatic patch retry"));
    }

    @Test void binaryHashFailuresAlsoRequestCorrection() throws Exception {
        setup();
        int[] calls = {0};
        var provider = new CapturingLLMProvider(prompt -> calls[0]++ == 0
                ? batch(object("op", "file_patch_binary", "path", "work.txt", "source_sha256", "0".repeat(64),
                        "hunks", lines(object("offset", 0, "remove_count", 0, "encoding", "base64", "data", ""))))
                : batch(fileWrite("work.txt", "corrected")));
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            app.run(new byte[0], List.of(), RequestContext.load(directory, "context.json"));
        }
        assertEquals(2, provider.calls());
        assertTrue(provider.prompt().contains("source_sha256 mismatch"));
        assertEquals("corrected", Files.readString(directory.resolve("work.txt")));
    }

    @Test void permissionParsingCommitAndOutputFailuresNeverRetry() throws Exception {
        setup();
        for (String response : List.of("not JSON", batch(replaceFirstLine("context.json", "original\n", "wrong", "changed")))) {
            var provider = new CapturingLLMProvider(response);
            try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
                assertThrows(IllegalArgumentException.class, () -> app.run(new byte[0], List.of(),
                        RequestContext.load(directory, "context.json")));
            }
            assertEquals(1, provider.calls());
        }
        var commitProvider = new CapturingLLMProvider(batch(fileWrite("work.txt", "changed")));
        try (var app = new AICommandApplication(commitProvider, new ByteArrayOutputStream(), directory) {
            @Override protected void commitFile(Path target, byte[] bytes) throws IOException {
                super.commitFile(target, bytes);
                throw new IOException("injected commit failure");
            }
        }) {
            assertThrows(IOException.class, () -> app.run(new byte[0], List.of(), RequestContext.load(directory, "context.json")));
        }
        assertEquals(1, commitProvider.calls());
        assertEquals("original\n", Files.readString(directory.resolve("work.txt")));
        OutputStream broken = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("broken output"); }
        };
        var outputProvider = new CapturingLLMProvider(batch(fileWrite("work.txt", "committed"), stdout("visible")));
        try (var app = new AICommandApplication(outputProvider, broken, directory)) {
            assertThrows(IOException.class, () -> app.run(new byte[0], List.of(), RequestContext.load(directory, "context.json")));
        }
        assertEquals(1, outputProvider.calls());
        assertEquals("committed", Files.readString(directory.resolve("work.txt")));
    }

    @Test void failedRetryNoticeStopsBeforeResubmission() throws Exception {
        setup();
        var provider = new CapturingLLMProvider(bad());
        OutputStream broken = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("broken notice"); }
        };
        try (var app = new AICommandApplication(provider, broken, directory)) {
            assertThrows(IOException.class, () -> app.run(new byte[0], List.of(), RequestContext.load(directory, "context.json")));
        }
        assertEquals(1, provider.calls());
        assertEquals("original\n", Files.readString(directory.resolve("work.txt")));
        assertFalse(Files.exists(directory.resolve("created.txt")));
    }

    @Test void contextChangesAreNotReplayedAndReadProtectionSurvives() throws Exception {
        setup();
        Files.writeString(directory.resolve("reference.txt"), "protected\n");
        Files.writeString(directory.resolve("context.json"), contextJson(readWrite("work.txt"), read("reference.txt")));
        int[] calls = {0};
        var provider = new CapturingLLMProvider(prompt -> switch (calls[0]++) {
            case 0 -> batch(object("op", "change_contexts", "add", List.of(), "remove", List.of("reference.txt")));
            case 1 -> bad();
            default -> batch(fileWrite("reference.txt", "forbidden"));
        });
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory)) {
            assertThrows(IllegalArgumentException.class, () -> app.run(new byte[0], List.of(), RequestContext.load(directory, "context.json")));
        }
        assertEquals(3, provider.calls());
        assertEquals(List.of("work.txt"), RequestContext.load(directory, "context.json").files().stream().map(ContextFile::path).toList());
        assertEquals("protected\n", Files.readString(directory.resolve("reference.txt")));
        assertEquals("original\n", Files.readString(directory.resolve("work.txt")));
    }
}
