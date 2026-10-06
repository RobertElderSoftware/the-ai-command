package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.res.ai.TestJson.*;

class SubsetContextChangesTest {
    @TempDir Path directory;

    private RequestContext setup() throws Exception {
        Files.writeString(directory.resolve("source.txt"), "one\r\ntwo\nthree");
        Files.writeString(directory.resolve("other.txt"), "other");
        Files.writeString(directory.resolve("policy.json"), "{\"contexts\":[\"./source.txt\",\"other.txt\"],"
                + "\"permissions\":[{\"regex:.*\":\"READ_WRITE\"}]}");
        Files.createLink(directory.resolve("alias.txt"), directory.resolve("source.txt"));
        return RequestContext.load(directory, "policy.json");
    }

    private String request(String add, String remove, String update) {
        return "{\"op\":\"change_contexts\",\"add\":" + add + ",\"remove\":" + remove
                + ",\"update\":" + update + "}";
    }

    private ChangeContexts.Result apply(RequestContext context, String update) throws Exception {
        return ChangeContexts.apply(directory, context, List.of(), request("[]", "[]", update));
    }

    @Test void updatesPreserveSpellingOrderPermissionsAndIdempotence() throws Exception {
        RequestContext original = setup();
        var result = apply(original, "[{\"path\":\"alias.txt\",\"lines\":[[2,2]]}]");
        assertEquals(List.of("./source.txt", "other.txt"), result.context().files().stream()
                .map(ContextFile::path).toList());
        assertEquals(original.permissionsDocument(), result.context().permissionsDocument());
        assertEquals(ContextSelection.Kind.LINES, result.files().get(0).selection().kind());
        assertTrue(result.report().contains("Updated context selection: \"./source.txt\" lines=[2,2]"));
        byte[] before = Files.readAllBytes(directory.resolve("policy.json"));
        var noop = apply(result.context(), "[{\"path\":\"source.txt\",\"lines\":[[2,2]]}]");
        assertTrue(noop.report().contains("Unchanged context selection:"));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("policy.json")));
        var addition = ChangeContexts.apply(directory, noop.context(), List.of(),
                request("[\"alias.txt\"]", "[]", "[]"));
        assertEquals(result.files().get(0).selection(), addition.files().get(0).selection());
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("policy.json")));
        var whole = apply(addition.context(), "[\"alias.txt\"]");
        assertTrue(whole.files().get(0).selection().isWhole());
        assertEquals("./source.txt", whole.files().get(0).path());
    }

    @Test void addsByteSelectionsAndKeepsExplicitDuplicatesAsSubsets() throws Exception {
        RequestContext original = setup();
        Files.write(directory.resolve("binary"), new byte[] {0, (byte) 255, 7});
        var result = ChangeContexts.apply(directory, original, List.of("binary"),
                request("[{\"path\":\"binary\",\"bytes\":[[1,1]]}]", "[]", "[]"));
        assertEquals(3, result.files().size());
        assertEquals(ContextSelection.Kind.BYTES, result.files().get(2).selection().kind());
        var provider = new CapturingLLMProvider("[]");
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory, false)) {
            app.run(new byte[0], List.of("binary"), result.context());
        }
        assertTrue(provider.prompt().contains("---SECTION FILE parts-base64 "));
        assertTrue(provider.prompt().contains("source_bytes=3 access=READ_WRITE path=binary---"));
    }

    @Test void invalidChangesLeaveConfigurationUntouched() throws Exception {
        RequestContext original = setup();
        Files.createSymbolicLink(directory.resolve("link.txt"), directory.resolve("source.txt"));
        Files.write(directory.resolve("invalid.txt"), new byte[] {'a', '\n', (byte) 255});
        byte[] before = Files.readAllBytes(directory.resolve("policy.json"));
        for (String invalid : List.of(
                request("[]", "[]", "[\"missing.txt\"]"),
                request("[]", "[]", "[\"source.txt\",\"alias.txt\"]"),
                request("[\"alias.txt\"]", "[]", "[\"source.txt\"]"),
                request("[]", "[\"source.txt\"]", "[\"alias.txt\"]"),
                request("[]", "[]", "[{\"path\":\"source.txt\",\"lines\":[[4,4]]}]"),
                request("[]", "[]", "[{\"path\":\"source.txt\",\"bytes\":[[999,1]]}]"),
                request("[]", "[]", "[{\"path\":\"source.txt\",\"lines\":[[1.0,2]]}]"),
                request("[]", "[]", "[{\"path\":\"source.txt\",\"lines\":[[1,1]],\"extra\":0}]"),
                request("[]", "[]", "[{\"path\":\"source.txt\",\"lines\":[[1,1]],\"lines\":[[2,2]]}]"),
                request("[{\"path\":\"invalid.txt\",\"lines\":[[1,1]]}]", "[]", "[]"),
                request("[{\"path\":\"missing.txt\",\"bytes\":[[0,1]]}]", "[]", "[]"),
                request("[\"link.txt\"]", "[]", "[]"),
                request("[\"../outside\"]", "[]", "[]"),
                request("[]", "[]", "null"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> ChangeContexts.apply(directory, original, List.of(), invalid), invalid);
            assertArrayEquals(before, Files.readAllBytes(directory.resolve("policy.json")), invalid);
        }
        Files.delete(directory.resolve("other.txt"));
        Files.createDirectory(directory.resolve("other.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> apply(original, "[{\"path\":\"source.txt\",\"bytes\":[[0,1]]}]"));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("policy.json")));
    }

    @Test void providerCanSelectExcerptThenRequestWholeSourceAndPatch() throws Exception {
        RequestContext original = setup();
        String source = Files.readString(directory.resolve("source.txt"));
        int[] calls = {0};
        var provider = new CapturingLLMProvider(prompt -> {
            int call = calls[0]++;
            assertTrue(prompt.startsWith("---BEGIN CIOP/1.1---\n"));
            if (call == 0) return "[" + request("[]", "[]",
                    "[{\"path\":\"alias.txt\",\"lines\":[[2,2]]}]") + "]";
            if (call == 1) {
                assertTrue(prompt.contains("L;2,2,4\ntwo\n\n---END SECTION---"));
                assertFalse(prompt.contains("\none\r\ntwo\nthree\n"));
                assertTrue(prompt.contains(AICommandApplication.sha256(source.getBytes(StandardCharsets.UTF_8))
                        + " source_bytes=14 access=READ_WRITE path=./source.txt---"));
                return "[" + request("[]", "[]", "[\"source.txt\"]") + "]";
            }
            assertEquals(2, call);
            assertTrue(prompt.contains("\none\r\ntwo\nthree\n---END SECTION---"));
            return batch(replaceFirstLine("source.txt", source, "one", "changed"));
        });
        try (var app = new AICommandApplication(provider, new ByteArrayOutputStream(), directory, false)) {
            app.run(new byte[0], List.of("alias.txt"), original);
        }
        assertEquals(3, provider.calls());
        assertEquals("changed\ntwo\nthree", Files.readString(directory.resolve("source.txt")));
        assertTrue(RequestContext.load(directory, "policy.json").files().get(0).selection().isWhole());
    }

    @Test void duplicateFieldsInProviderResponsesAreRejectedBeforeCommit() throws Exception {
        RequestContext original = setup();
        byte[] before = Files.readAllBytes(directory.resolve("policy.json"));
        var output = new ByteArrayOutputStream();
        String response = "[" + request("[]", "[]",
                "[{\"path\":\"source.txt\",\"lines\":[[1,1]],\"lines\":[[2,2]]}]") + "]";
        var provider = new CapturingLLMProvider(response);
        try (var app = new AICommandApplication(provider, output, directory, false)) {
            assertThrows(IllegalArgumentException.class, () -> app.run(new byte[0], List.of(), original));
        }
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("policy.json")));
        assertEquals(0, output.size());
        assertEquals(1, provider.calls());
    }
}
