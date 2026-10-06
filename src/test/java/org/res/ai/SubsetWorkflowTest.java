package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.res.ai.TestJson.*;

/** Final workflow, wire-size, and packaged protocol checks for CIOP/1.1. */
class SubsetWorkflowTest {
    @TempDir Path directory;

    @Test
    void excerptExpandsToWholeFileBeforeHashCheckedPatch() throws Exception {
        String original = "first\r\nsecond\nthird";
        Files.writeString(directory.resolve("source.txt"), original);
        Files.writeString(directory.resolve("policy.json"),
                "{\"contexts\":[{\"path\":\"./source.txt\",\"lines\":[[2,2]]}],"
                        + "\"permissions\":[{\"source.txt\":\"READ_WRITE\"}]}");
        RequestContext context = RequestContext.load(directory, "policy.json");
        var permissions = context.permissionsDocument();
        String hash = AICommandApplication.sha256(original.getBytes(StandardCharsets.UTF_8));
        int[] calls = {0};
        var provider = new CapturingLLMProvider(prompt -> {
            assertTrue(prompt.startsWith("---BEGIN CIOP/1.1---\n"));
            if (calls[0]++ == 0) {
                assertTrue(prompt.contains(" " + hash + " source_bytes=19 access=READ_WRITE path=./source.txt---\n"));
                assertTrue(prompt.contains("L;2,2,7\nsecond\n\n---END SECTION---"));
                assertFalse(prompt.contains(original));
                return "[{\"op\":\"change_contexts\",\"add\":[],\"remove\":[],"
                        + "\"update\":[\"source.txt\"]}]";
            }
            assertEquals(2, calls[0], "Exactly one expansion follow-up is expected");
            assertTrue(prompt.contains("---SECTION FILE utf-8 19 " + hash
                    + " access=READ_WRITE path=./source.txt---\n" + original
                    + "\n---END SECTION---"));
            var patch = replaceFirstLine("source.txt", original, "first", "changed");
            patch.addProperty("result_sha256", AICommandApplication.sha256(
                    "changed\nsecond\nthird".getBytes(StandardCharsets.UTF_8)));
            return batch(patch, stdout("patched\n"));
        });
        var output = new ByteArrayOutputStream();
        try (var app = new AICommandApplication(provider, output, directory, false)) {
            app.run("Expand and patch".getBytes(StandardCharsets.UTF_8),
                    List.of("source.txt"), context);
        }
        assertEquals(2, provider.calls());
        assertEquals("changed\nsecond\nthird", Files.readString(directory.resolve("source.txt")));
        RequestContext updated = RequestContext.load(directory, "policy.json");
        assertEquals(1, updated.files().size());
        assertEquals("./source.txt", updated.files().get(0).path());
        assertTrue(updated.files().get(0).selection().isWhole());
        assertEquals(permissions, updated.permissionsDocument());
        assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("patched\n"));
    }

    @Test
    void sparseBinaryWireSizeIsSmallerThanRepeatedFullHeaders() throws Exception {
        byte[] source = new byte[10000];
        new Random(42).nextBytes(source);
        Files.write(directory.resolve("source.bin"), source);
        List<ContextSelection.Range> ranges = new ArrayList<>();
        for (int offset = 0; offset < source.length; offset += 2)
            ranges.add(new ContextSelection.Range(offset, 1));
        ContextFile file = new ContextFile("source.bin", FileAccess.READ,
                new ContextSelection(ContextSelection.Kind.BYTES, ranges));
        var result = SubsetEnvelope.read(new WorkingDirectoryPaths(directory), file);
        String section = result.section(file);
        assertTrue(section.startsWith("---SECTION FILE parts-base64 "));
        int compactBytes = section.getBytes(StandardCharsets.UTF_8).length;
        long repeatedBytes = 0;
        // Comparison baseline: a full header and separately padded base64 per part.
        for (var range : ranges) {
            String part = "---SECTION FILE base64 1 " + result.sourceSha256()
                    + " source_bytes=" + source.length + " offset=" + range.first()
                    + " access=READ path=source.bin---\n"
                    + Base64.getEncoder().encodeToString(new byte[] {source[(int) range.first()]})
                    + "\n---END SECTION---\n";
            repeatedBytes += part.getBytes(StandardCharsets.UTF_8).length;
        }
        assertTrue(compactBytes < repeatedBytes / 5,
                "Compact encoding should use less than 20% of repeated-header bytes");
        System.out.println("Sparse subset: parts=" + ranges.size()
                + ", compact wire bytes=" + compactBytes
                + ", repeated-header baseline bytes=" + repeatedBytes);
    }

    @Test
    void embeddedProtocolMatchesSourceSpecificationAndActiveEnvelope() throws Exception {
        byte[] embedded = ProtocolDocument.bytes();
        assertArrayEquals(Files.readAllBytes(Path.of("specification.txt")), embedded,
                "The classpath protocol resource must match the source specification");
        String protocol = new String(embedded, StandardCharsets.UTF_8);
        assertTrue(protocol.contains("Version 1.1"));
        assertTrue(protocol.contains("parts-utf-8|parts-base64"));
        var provider = new CapturingLLMProvider("[]");
        try (var app = new AICommandApplication(provider,
                new ByteArrayOutputStream(), directory, false)) {
            app.run(new byte[0], List.of());
        }
        String expected = "---BEGIN CIOP/1.1---\n---SECTION FILE utf-8 "
                + embedded.length + " " + AICommandApplication.sha256(embedded)
                + " access=READ path=" + ProtocolDocument.PATH + "---\n"
                + protocol + "\n---END SECTION---\n";
        assertTrue(provider.prompt().startsWith(expected));
        assertTrue(provider.prompt().endsWith("---END CIOP/1.1---\n"));
    }
}
