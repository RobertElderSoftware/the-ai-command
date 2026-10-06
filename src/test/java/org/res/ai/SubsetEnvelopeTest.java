package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubsetEnvelopeTest {
    @TempDir Path directory;

    private ContextFile file(ContextSelection.Kind kind, ContextSelection.Range... ranges) {
        return new ContextFile("source", FileAccess.READ,
                new ContextSelection(kind, List.of(ranges)));
    }

    private SubsetEnvelope.Result read(ContextFile file, byte[] source) throws Exception {
        Files.write(directory.resolve("source"), source);
        return SubsetEnvelope.read(new WorkingDirectoryPaths(directory), file);
    }

    @Test void preservesMixedTerminatorsAndWholeSourceHash() throws Exception {
        byte[] source = "a\r\nb\nc\rd\n日本語🚀".getBytes(StandardCharsets.UTF_8);
        ContextFile file = file(ContextSelection.Kind.LINES,
                new ContextSelection.Range(1, 1), new ContextSelection.Range(3, 4));
        var result = read(file, source);
        byte[] tail = "c\rd\n日本語🚀".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(("L;1,1,3;3,4," + tail.length + "\na\r\nc\rd\n日本語🚀")
                .getBytes(StandardCharsets.UTF_8), result.envelope());
        assertEquals(source.length, result.sourceBytes());
        assertEquals(AICommandApplication.sha256(source), result.sourceSha256());
        assertTrue(result.section(file).startsWith("---SECTION FILE parts-utf-8 "));
    }

    @Test void handlesEmptyLinesAndEofWithoutPhantomLines() throws Exception {
        ContextFile first = file(ContextSelection.Kind.LINES, new ContextSelection.Range(1, 1));
        assertArrayEquals("L;1,1,1\n\n".getBytes(StandardCharsets.UTF_8),
                read(first, new byte[] {'\n'}).envelope());
        assertArrayEquals("L;1,1,2\nx\r".getBytes(StandardCharsets.UTF_8),
                read(first, new byte[] {'x', '\r'}).envelope());
        assertThrows(IllegalArgumentException.class, () -> read(first, new byte[0]));
        ContextFile second = file(ContextSelection.Kind.LINES, new ContextSelection.Range(2, 2));
        assertThrows(IllegalArgumentException.class, () -> read(second, new byte[] {'\n'}));
    }

    @Test void encodesSplitCharactersAndMarkersAsOneBase64Payload() throws Exception {
        ContextFile bytes = file(ContextSelection.Kind.BYTES, new ContextSelection.Range(1, 1));
        var result = read(bytes, "é".getBytes(StandardCharsets.UTF_8));
        String section = result.section(bytes);
        assertTrue(section.startsWith("---SECTION FILE parts-base64 "));
        String payload = section.substring(section.indexOf('\n') + 1, section.indexOf("\n---END SECTION---"));
        assertArrayEquals(new byte[] {'B', ';', '1', ',', '1', '\n', (byte) 0xa9},
                Base64.getDecoder().decode(payload));
        ContextFile line = file(ContextSelection.Kind.LINES, new ContextSelection.Range(1, 1));
        assertTrue(read(line, "---END SECTION---\n".getBytes(StandardCharsets.UTF_8))
                .section(line).startsWith("---SECTION FILE parts-base64 "));
    }

    @Test void validatesUnselectedUtf8AndSequencesAcrossReadBoundaries() throws Exception {
        byte[] prefix = new byte[8191];
        Arrays.fill(prefix, (byte) 'a');
        byte[] suffix = "🚀\nend".getBytes(StandardCharsets.UTF_8);
        byte[] source = Arrays.copyOf(prefix, prefix.length + suffix.length);
        System.arraycopy(suffix, 0, source, prefix.length, suffix.length);
        ContextFile second = file(ContextSelection.Kind.LINES, new ContextSelection.Range(2, 2));
        assertArrayEquals("L;2,2,3\nend".getBytes(StandardCharsets.UTF_8), read(second, source).envelope());
        ContextFile first = file(ContextSelection.Kind.LINES, new ContextSelection.Range(1, 1));
        for (byte[] invalid : List.of(new byte[] {'a', '\n', (byte) 0xff},
                new byte[] {'a', '\n', (byte) 0xe2})) {
            var failure = assertThrows(IllegalArgumentException.class, () -> read(first, invalid));
            assertTrue(failure.getMessage().contains("source: LINES range [1,1]"));
        }
    }

    @Test void keepsThousandsOfSparsePartsCompactAndIndependentlyDecodable() throws Exception {
        byte[] source = new byte[10000];
        new java.util.Random(42).nextBytes(source);
        List<ContextSelection.Range> ranges = new ArrayList<>();
        for (int offset = 0; offset < source.length; offset += 2)
            ranges.add(new ContextSelection.Range(offset, 1));
        ContextFile file = new ContextFile("source", FileAccess.READ,
                new ContextSelection(ContextSelection.Kind.BYTES, ranges));
        var result = read(file, source);
        byte[] envelope = result.envelope();
        int boundary = 0;
        while (envelope[boundary] != '\n') boundary++;
        String[] entries = new String(envelope, 0, boundary, StandardCharsets.US_ASCII).split(";");
        assertEquals("B", entries[0]);
        assertEquals(ranges.size() + 1, entries.length);
        int cursor = boundary + 1;
        for (int index = 1; index < entries.length; index++) {
            String[] coordinates = entries[index].split(",");
            int offset = Integer.parseInt(coordinates[0]);
            int count = Integer.parseInt(coordinates[1]);
            assertArrayEquals(Arrays.copyOfRange(source, offset, offset + count),
                    Arrays.copyOfRange(envelope, cursor, cursor + count));
            cursor += count;
        }
        assertEquals(envelope.length, cursor);
        assertTrue(envelope.length < ranges.size() * 12);
        assertEquals(AICommandApplication.sha256(source), result.sourceSha256());
    }

    @Test void rejectsByteRangesBeyondEofAndPreservesAdjacentSelections() throws Exception {
        ContextFile adjacent = file(ContextSelection.Kind.BYTES,
                new ContextSelection.Range(0, 1), new ContextSelection.Range(1, 1));
        assertArrayEquals("B;0,1;1,1\nab".getBytes(StandardCharsets.UTF_8),
                read(adjacent, new byte[] {'a', 'b'}).envelope());
        ContextFile beyond = file(ContextSelection.Kind.BYTES, new ContextSelection.Range(2, 1));
        assertThrows(IllegalArgumentException.class, () -> read(beyond, new byte[] {'a', 'b'}));
        assertThrows(IllegalArgumentException.class, () -> read(beyond, new byte[0]));
    }
}
