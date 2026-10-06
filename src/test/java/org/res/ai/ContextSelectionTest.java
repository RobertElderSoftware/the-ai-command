package org.res.ai;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextSelectionTest {
    @TempDir Path directory;

    private RequestContext load(String entries) throws Exception {
        Files.writeString(directory.resolve("policy.json"), "{\"contexts\":[" + entries
                + "],\"permissions\":[{\"regex:.*\":\"READ_WRITE\"}]}");
        return RequestContext.load(directory, "policy.json");
    }

    @Test
    void selectionsRoundTripAndSurviveAliasesAndPermissionChanges() throws Exception {
        Files.writeString(directory.resolve("source.txt"), "one\r\ntwo\nthree");
        Files.createLink(directory.resolve("alias.txt"), directory.resolve("source.txt"));
        String entry = "{\"path\":\"./source.txt\",\"lines\":[[1,1],[2,3]]}";
        RequestContext context = load(entry);
        ContextFile file = context.files().get(0);
        assertEquals(JsonParser.parseString(entry), file.contextEntry());
        assertEquals(ContextSelection.Kind.LINES, file.selection().kind());
        assertEquals(FileAccess.READ_WRITE, file.access());
        assertEquals(file.selection(), file.withAccess(FileAccess.READ).selection());
        assertTrue(context.canWrite(directory, "source.txt"));
        List<ContextFile> merged = context.merge(new WorkingDirectoryPaths(directory),
                List.of("alias.txt", "source.txt"));
        assertEquals(1, merged.size());
        assertEquals(file.contextEntry(), merged.get(0).contextEntry());
        RequestContext selected = context.select(directory, List.of("alias.txt"));
        assertEquals(file.contextEntry(), selected.files().get(0).contextEntry());
        assertThrows(IllegalArgumentException.class, () -> load(entry + ",\"alias.txt\""));
    }

    @Test
    void byteSelectionsAndWholeFileCompatibility() throws Exception {
        Files.write(directory.resolve("source.txt"), new byte[] {0, (byte) 255, 10});
        String entry = "{\"bytes\":[[0,1],[1,2]],\"path\":\"source.txt\"}";
        ContextFile file = load(entry).files().get(0);
        assertEquals(JsonParser.parseString(entry), file.contextEntry());
        assertEquals(ContextSelection.Kind.BYTES, file.selection().kind());
        assertTrue(load("\"missing.txt\"").files().get(0).selection().isWhole());
        assertEquals(JsonParser.parseString("\"source.txt\""),
                new ContextFile("source.txt", FileAccess.READ).contextEntry());
        assertThrows(IllegalArgumentException.class,
                () -> load("{\"path\":\"missing.txt\",\"bytes\":[[0,1]]}"));
        Files.createDirectory(directory.resolve("folder"));
        assertThrows(IllegalArgumentException.class,
                () -> load("{\"path\":\"folder\",\"lines\":[[1,1]]}"));
    }

    @Test
    void rejectsMalformedSelectionsWithoutCoercingNumbers() throws Exception {
        Files.writeString(directory.resolve("source.txt"), "one\ntwo\n");
        for (String ranges : List.of("[]", "null", "[[1]]", "[[1,1,1]]", "[[0,1]]",
                "[[-1,1]]", "[[2,1]]", "[[1,2],[2,3]]", "[[2,2],[1,1]]",
                "[[1.0,2]]", "[[1e0,2]]", "[[\"1\",2]]", "[[true,2]]",
                "[[1,9223372036854775808]]", "[[1,null]]")) {
            assertThrows(Exception.class, () -> load("{\"path\":\"source.txt\",\"lines\":"
                    + ranges + "}"), ranges);
        }
        for (String ranges : List.of("[[0,0]]", "[[-1,1]]", "[[0,-1]]",
                "[[0,2],[1,1]]", "[[9223372036854775807,1]]")) {
            assertThrows(Exception.class, () -> load("{\"path\":\"source.txt\",\"bytes\":"
                    + ranges + "}"), ranges);
        }
        for (String entry : List.of("{}", "null", "42", "{\"path\":\"source.txt\"}",
                "{\"lines\":[[1,1]]}", "{\"path\":null,\"lines\":[[1,1]]}",
                "{\"path\":\"source.txt\",\"path\":\"source.txt\",\"lines\":[[1,1]]}",
                "{\"path\":\"source.txt\",\"lines\":[[1,1]],\"lines\":[[2,2]]}",
                "{\"path\":\"source.txt\",\"lines\":[[1,1]],\"bytes\":[[0,1]]}",
                "{\"path\":\"source.txt\",\"lines\":[[1,1]],\"extra\":0}")) {
            assertThrows(Exception.class, () -> load(entry), entry);
        }
    }

    @Test
    void rangesAreImmutableAndUseLongCoordinates() {
        List<ContextSelection.Range> ranges = new ArrayList<>();
        ranges.add(new ContextSelection.Range(1, Long.MAX_VALUE));
        ContextSelection selection = new ContextSelection(ContextSelection.Kind.LINES, ranges);
        ranges.clear();
        assertEquals(1, selection.ranges().size());
        assertThrows(UnsupportedOperationException.class, () -> selection.ranges().clear());
        assertDoesNotThrow(() -> new ContextSelection(ContextSelection.Kind.BYTES,
                List.of(new ContextSelection.Range(Long.MAX_VALUE - 1, 1))));
        assertThrows(IllegalArgumentException.class, () -> new ContextSelection(
                ContextSelection.Kind.WHOLE, selection.ranges()));
    }

    @Test
    void historyRegistrationPreservesExistingExcerptsAndProtectsAccess() throws Exception {
        Files.createDirectory(directory.resolve(ConversationHistory.DIRECTORY));
        String name = "conversation_history/2026-01-01.txt";
        Files.writeString(directory.resolve(name), "existing history\n");
        Files.writeString(directory.resolve("source.txt"), "one\ntwo\n");
        String historyEntry = "{\"path\":\"" + name + "\",\"lines\":[[1,1]]}";
        String sourceEntry = "{\"path\":\"source.txt\",\"bytes\":[[0,1]]}";
        load(historyEntry + "," + sourceEntry);
        ConversationHistory history = new ConversationHistory(directory, "policy.json",
                Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC));
        history.input("next".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<ContextFile> files = RequestContext.load(directory, "policy.json").files();
        assertEquals(2, files.size());
        assertEquals(JsonParser.parseString(historyEntry), files.get(0).contextEntry());
        assertEquals(FileAccess.READ, files.get(0).access());
        assertEquals(JsonParser.parseString(sourceEntry), files.get(1).contextEntry());
        assertEquals(FileAccess.READ_WRITE, files.get(1).access());
    }

    @Test
    void encoderSendsOnlySelectedContentAndRejectsInvalidChanges() throws Exception {
        Files.writeString(directory.resolve("source.txt"), "selected\nunselected\n");
        RequestContext context = load("{\"path\":\"source.txt\",\"lines\":[[1,1]]}");
        CapturingLLMProvider provider = new CapturingLLMProvider("[]");
        try (AICommandApplication app = new AICommandApplication(provider,
                new ByteArrayOutputStream(), directory, false)) {
            app.run(new byte[0], List.of(), context);
        }
        assertEquals(1, provider.calls());
        assertTrue(provider.prompt().startsWith("---BEGIN CIOP/1.1---\n"));
        assertTrue(provider.prompt().contains("L;1,1,9\nselected\n\n---END SECTION---"));
        assertFalse(provider.prompt().contains("\nunselected\n"));
        byte[] before = Files.readAllBytes(directory.resolve("policy.json"));
        assertThrows(IllegalArgumentException.class, () -> ChangeContexts.apply(directory, context,
                List.of(), "{\"op\":\"change_contexts\",\"add\":[{\"path\":\"new.txt\",\"bytes\":[[0,1]]}],\"remove\":[]}"));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("policy.json")));
    }
}
