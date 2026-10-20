package org.res.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/** Streaming extraction and compact CIOP/1.1 encoding, independent of permissions. */
final class SubsetEnvelope {
    private SubsetEnvelope() { }

    record Result(byte[] envelope, long sourceBytes, String sourceSha256) {
        String section(ContextFile file) {
            String text = Encoding.strictUtf8(envelope);
            boolean literal = text != null && safe(text);
            String payload = literal ? text : Base64.getEncoder().encodeToString(envelope);
            return "---SECTION FILE parts-" + (literal ? "utf-8" : "base64") + " "
                    + envelope.length + " " + sourceSha256 + " source_bytes=" + sourceBytes
                    + " access=" + file.access() + " path=" + file.path() + "---\n"
                    + payload + "\n---END SECTION---\n";
        }
    }

    static Result read(WorkingDirectoryPaths paths, ContextFile file) throws IOException {
        if (file.selection().isWhole()) throw new IllegalArgumentException("Subset required: " + file.path());
        Path source = paths.contextFile(file);
        return read(file, Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS));
    }

    static Result read(ContextFile file, byte[] bytes) throws IOException {
        if (file.selection().isWhole()) throw new IllegalArgumentException("Subset required: " + file.path());
        return read(file, new java.io.ByteArrayInputStream(bytes));
    }

    private static Result read(ContextFile file, java.io.InputStream source) throws IOException {
        try (source) {
            return extract(file, source);
        }
    }

    private static Result extract(ContextFile file, java.io.InputStream source) throws IOException {
        ContextSelection selection = file.selection();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        boolean lines = selection.kind() == ContextSelection.Kind.LINES;
        Utf8Validator validator = lines ? new Utf8Validator() : null;
        long[] lengths = new long[selection.ranges().size()];
        ByteArrayOutputStream selected = new ByteArrayOutputStream();
        long size = 0;
        long line = 1;
        long lineCount = 0;
        int rangeIndex = 0;
        byte[] block = new byte[8192];
        try (var input = source) {
            int count;
            while ((count = input.read(block)) != -1) {
                digest.update(block, 0, count);
                if (validator != null) validator.accept(block, count, false);
                for (int index = 0; index < count; index++) {
                    long coordinate = lines ? line : size;
                    while (rangeIndex < lengths.length) {
                        var range = selection.ranges().get(rangeIndex);
                        long end = lines ? range.second() : range.first() + range.second() - 1;
                        if (coordinate <= end) break;
                        rangeIndex++;
                    }
                    if (rangeIndex < lengths.length) {
                        var range = selection.ranges().get(rangeIndex);
                        if (coordinate >= range.first()) {
                            if (selected.size() == Integer.MAX_VALUE - 8)
                                throw invalid(file, rangeIndex, "selected payload exceeds representation limit");
                            selected.write(block[index]);
                            lengths[rangeIndex]++;
                        }
                    }
                    size = Math.addExact(size, 1);
                    if (lines) {
                        lineCount = line;
                        if (block[index] == '\n') line = Math.addExact(line, 1);
                    }
                }
            }
            if (validator != null) validator.accept(block, 0, true);
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw invalid(file, Math.min(rangeIndex, lengths.length - 1),
                    "entire source must be valid UTF-8, including unselected content");
        } catch (ArithmeticException exception) {
            throw invalid(file, Math.min(rangeIndex, lengths.length - 1), "source coordinates overflow");
        }
        StringBuilder directory = new StringBuilder(lines ? "L" : "B");
        for (int index = 0; index < lengths.length; index++) {
            var range = selection.ranges().get(index);
            if (lines ? range.second() > lineCount : range.first() + range.second() > size)
                throw invalid(file, index, "range exceeds source bounds");
            String item = ";" + range.first() + "," + range.second()
                    + (lines ? "," + lengths[index] : "");
            if ((long) directory.length() + item.length() + 1 + selected.size() > Integer.MAX_VALUE - 8)
                throw invalid(file, index, "envelope exceeds representation limit");
            directory.append(item);
        }
        directory.append('\n');
        ByteArrayOutputStream envelope = new ByteArrayOutputStream(directory.length() + selected.size());
        envelope.writeBytes(directory.toString().getBytes(StandardCharsets.US_ASCII));
        selected.writeTo(envelope);
        return new Result(envelope.toByteArray(), size, HexFormat.of().formatHex(digest.digest()));
    }

    private static IllegalArgumentException invalid(ContextFile file, int index, String reason) {
        var range = file.selection().ranges().get(index);
        return new IllegalArgumentException(file.path() + ": " + file.selection().kind()
                + " range [" + range.first() + "," + range.second() + "]: " + reason);
    }

    private static boolean safe(String text) {
        if (text.chars().anyMatch(ch -> Character.isISOControl(ch)
                && ch != '\t' && ch != '\r' && ch != '\n')) return false;
        return text.lines().map(String::strip).noneMatch(line ->
                line.startsWith("---BEGIN CIOP/") || line.startsWith("---END CIOP/")
                        || line.equals("---END SECTION---") || line.startsWith("---SECTION "));
    }

    /** Retains only an incomplete UTF-8 sequence between source blocks. */
    private static final class Utf8Validator {
        private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        private final ByteBuffer input = ByteBuffer.allocate(8196);
        private final CharBuffer output = CharBuffer.allocate(8192);

        void accept(byte[] bytes, int count, boolean end) throws java.nio.charset.CharacterCodingException {
            input.put(bytes, 0, count);
            input.flip();
            while (true) {
                var result = decoder.decode(input, output, end);
                output.clear();
                if (result.isError()) result.throwException();
                if (result.isUnderflow()) break;
            }
            input.compact();
            if (end) {
                var result = decoder.flush(output);
                if (result.isError()) result.throwException();
            }
        }
    }
}
