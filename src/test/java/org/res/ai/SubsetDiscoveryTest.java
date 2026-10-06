package org.res.ai;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SubsetDiscoveryTest {
    @Test
    void discoveryReportsWholeFileMetadataAndPreservesSelections(@TempDir Path directory)
            throws Exception {
        byte[] source = "one\r\ntwo\n日本語".getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("source.txt"), source);
        Files.writeString(directory.resolve("policy.json"),
                "{\"contexts\":[{\"path\":\"source.txt\",\"lines\":[[2,2]]}],"
                + "\"permissions\":[{\"source.txt\":\"READ_WRITE\"}]}");
        RequestContext configured = RequestContext.load(directory, "policy.json");
        ContextFile selection = configured.files().get(0);
        var expectedEntry = selection.contextEntry();
        for (RequestContext policy : List.of(configured, RequestContext.empty())) {
            for (boolean protectedFile : List.of(false, true)) {
                var report = JsonParser.parseString(AvailableFiles.report(directory, policy,
                        List.of(selection), protectedFile ? Set.of("source.txt") : Set.of()))
                        .getAsJsonObject();
                assertTrue(report.get("complete").getAsBoolean());
                assertTrue(report.getAsJsonArray("errors").isEmpty());
                var entries = report.getAsJsonArray("entries");
                assertEquals(1, entries.size());
                var entry = entries.get(0).getAsJsonObject();
                assertEquals("source.txt", entry.get("path").getAsString());
                assertEquals(source.length, entry.get("size_bytes").getAsLong());
                assertEquals(3, entry.get("line_count").getAsLong());
                assertEquals(protectedFile ? "READ" : "READ_WRITE",
                        entry.get("access").getAsString());
                assertEquals(expectedEntry, selection.contextEntry());
            }
        }
        assertEquals(expectedEntry, RequestContext.load(directory, "policy.json")
                .files().get(0).contextEntry());
        assertArrayEquals(source, Files.readAllBytes(directory.resolve("source.txt")));
    }
}
