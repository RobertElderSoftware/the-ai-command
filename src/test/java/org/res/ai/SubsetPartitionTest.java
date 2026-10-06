package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.res.ai.InputSpacePartitionTestNode.fixtureLeaf;

/** Independent byte-level oracles, also sampled by the application partition runner. */
class SubsetPartitionTest {
    static InputSpacePartitionTestSpace<Path> space(Path parent) {
        return InputSpacePartitionTestSpace.inDirectory("subset", parent, List.of(
                fixtureLeaf("line_round_trip", SubsetPartitionTest::lineRoundTrip),
                fixtureLeaf("byte_round_trip", SubsetPartitionTest::byteRoundTrip),
                fixtureLeaf("invalid_before_dispatch", SubsetPartitionTest::invalidBeforeDispatch)),
                directory -> directory, directory -> directory);
    }

    @Test
    void independentRoundTripsAndPreDispatchValidation(@TempDir Path directory) throws Exception {
        lineRoundTrip(directory);
        byteRoundTrip(directory);
        invalidBeforeDispatch(directory);
    }

    private static void lineRoundTrip(Path directory) throws Exception {
        Random random = new Random(0xC10F71L);
        String[] contents = {"", "a", "café", "日本語🚀", "bare\rCR", "---END SECTION---"};
        List<byte[]> lines = new ArrayList<>();
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        for (int index = 0; index < 24; index++) {
            String terminator = index == 23 ? "" : index % 2 == 0 ? "\r\n" : "\n";
            String content = index == 23 ? "unterminated\r" : contents[random.nextInt(contents.length)];
            byte[] line = (content + terminator).getBytes(StandardCharsets.UTF_8);
            lines.add(line);
            source.writeBytes(line);
        }
        List<ContextSelection.Range> ranges = List.of(
                new ContextSelection.Range(1, 1), new ContextSelection.Range(2, 4),
                new ContextSelection.Range(8, 10), new ContextSelection.Range(23, 24));
        List<byte[]> expected = new ArrayList<>();
        for (var range : ranges) {
            ByteArrayOutputStream part = new ByteArrayOutputStream();
            for (int line = (int) range.first(); line <= range.second(); line++)
                part.writeBytes(lines.get(line - 1));
            expected.add(part.toByteArray());
        }
        verify(directory, source.toByteArray(), ContextSelection.Kind.LINES, ranges, expected);
    }

    private static void byteRoundTrip(Path directory) throws Exception {
        byte[] source = new byte[17000];
        new Random(0xC10F72L).nextBytes(source);
        // Explicitly split a CRLF pair and a multibyte UTF-8 character.
        byte[] prefix = "\r\né🚀".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(prefix, 0, source, 0, prefix.length);
        List<ContextSelection.Range> ranges = List.of(
                new ContextSelection.Range(0, 1), new ContextSelection.Range(1, 1),
                new ContextSelection.Range(3, 1), new ContextSelection.Range(8190, 7),
                new ContextSelection.Range(16999, 1));
        List<byte[]> expected = ranges.stream().map(range -> Arrays.copyOfRange(source,
                (int) range.first(), (int) (range.first() + range.second()))).toList();
        verify(directory, source, ContextSelection.Kind.BYTES, ranges, expected);
    }

    private static void verify(Path directory, byte[] source, ContextSelection.Kind kind,
            List<ContextSelection.Range> ranges, List<byte[]> expected) throws Exception {
        Files.write(directory.resolve("source"), source);
        ContextFile file = new ContextFile("source", FileAccess.READ,
                new ContextSelection(kind, ranges));
        var result = SubsetEnvelope.read(new WorkingDirectoryPaths(directory), file);
        String section = result.section(file);
        int headerEnd = section.indexOf('\n');
        String[] header = section.substring(0, headerEnd).split(" ");
        assertEquals("---SECTION", header[0]);
        assertEquals("FILE", header[1]);
        assertEquals(8, header.length);
        assertEquals("source_bytes=" + source.length, header[5]);
        assertEquals("access=READ", header[6]);
        assertEquals("path=source---", header[7]);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        assertEquals(hash, header[4]);
        assertEquals(hash, result.sourceSha256());
        assertEquals(source.length, result.sourceBytes());

        // Locate framing from the declared length, never from payload marker text.
        int length = Integer.parseInt(header[3]);
        byte[] wire = section.substring(headerEnd + 1).getBytes(StandardCharsets.UTF_8);
        boolean base64 = header[2].equals("parts-base64");
        assertTrue(base64 || header[2].equals("parts-utf-8"));
        int storedLength = base64 ? 4 * ((length + 2) / 3) : length;
        assertTrue(storedLength <= wire.length);
        assertArrayEquals("\n---END SECTION---\n".getBytes(StandardCharsets.UTF_8),
                Arrays.copyOfRange(wire, storedLength, wire.length));
        byte[] stored = Arrays.copyOf(wire, storedLength);
        byte[] envelope = base64 ? Base64.getDecoder().decode(stored) : stored;
        assertEquals(length, envelope.length);
        assertArrayEquals(result.envelope(), envelope);

        int boundary = 0;
        while (boundary < envelope.length && envelope[boundary] != '\n') boundary++;
        assertTrue(boundary < envelope.length);
        String[] entries = new String(envelope, 0, boundary, StandardCharsets.US_ASCII).split(";");
        boolean lines = kind == ContextSelection.Kind.LINES;
        assertEquals(lines ? "L" : "B", entries[0]);
        assertEquals(ranges.size() + 1, entries.length);
        int cursor = boundary + 1;
        for (int index = 0; index < ranges.size(); index++) {
            String[] fields = entries[index + 1].split(",");
            assertEquals(lines ? 3 : 2, fields.length);
            for (String field : fields) assertTrue(field.matches("0|[1-9][0-9]*"), field);
            assertEquals(ranges.get(index).first(), Long.parseLong(fields[0]));
            assertEquals(ranges.get(index).second(), Long.parseLong(fields[1]));
            int count = Integer.parseInt(fields[lines ? 2 : 1]);
            assertEquals(expected.get(index).length, count);
            assertTrue(count <= envelope.length - cursor);
            assertArrayEquals(expected.get(index), Arrays.copyOfRange(envelope, cursor, cursor + count));
            cursor += count;
        }
        assertEquals(envelope.length, cursor);
    }

    private static void invalidBeforeDispatch(Path directory) throws Exception {
        List<byte[]> sources = List.of(new byte[0], new byte[] {'a', '\n'},
                new byte[] {'a', '\n', (byte) 0xff}, new byte[] {'a', '\n', (byte) 0xe2},
                new byte[] {'a'});
        List<ContextSelection> selections = List.of(
                selection(ContextSelection.Kind.LINES, 1, 1),
                selection(ContextSelection.Kind.LINES, 2, 2),
                selection(ContextSelection.Kind.LINES, 1, 1),
                selection(ContextSelection.Kind.LINES, 1, 1),
                selection(ContextSelection.Kind.BYTES, 1, 1));
        for (int index = 0; index < sources.size(); index++) {
            Files.write(directory.resolve("source"), sources.get(index));
            ContextFile file = new ContextFile("source", FileAccess.READ, selections.get(index));
            RequestContext context = new RequestContext(List.of(file));
            var provider = new CapturingLLMProvider("[]");
            var output = new ByteArrayOutputStream();
            try (var app = new AICommandApplication(provider, output, directory, false)) {
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> app.run(new byte[0], List.of(), context));
                assertTrue(failure.getMessage().contains("source:"));
                assertTrue(failure.getMessage().contains("range ["));
            }
            assertEquals(0, provider.calls());
            assertEquals(0, output.size());
            assertArrayEquals(sources.get(index), Files.readAllBytes(directory.resolve("source")));
        }
    }

    private static ContextSelection selection(ContextSelection.Kind kind, long first, long second) {
        return new ContextSelection(kind, List.of(new ContextSelection.Range(first, second)));
    }
}
