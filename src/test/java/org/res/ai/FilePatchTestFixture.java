package org.res.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

import static org.res.ai.AICommandApplication.sha256;
import static org.res.ai.TestJson.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Creates and verifies valid and rejected file_patch scenarios. */
public final class FilePatchTestFixture {
    private final InputSpacePartitionTestContext context;

    public FilePatchTestFixture(InputSpacePartitionTestContext context) {
        this.context = context;
    }

    public void replacement() throws Exception {
        test("patch-replace-", "one\ntwo\nthree\n", 2, 1, 2, 1,
                lines(line("remove", "two"), line("add", "TWO")),
                "one\nTWO\nthree\n");
    }

    public void insertion() throws Exception {
        test("patch-insert-", "one\nthree\n", 2, 0, 2, 1,
                lines(line("add", "two")), "one\ntwo\nthree\n");
    }

    public void deletion() throws Exception {
        test("patch-delete-", "one\ntwo\nthree\n", 2, 1, 2, 0,
                lines(line("remove", "two")), "one\nthree\n");
    }

    public void stalePatchIsRejected() throws Exception {
        String path = "patch-stale-" + context.iteration() + ".txt";
        byte[] original = "original\n".getBytes(StandardCharsets.UTF_8);
        Files.write(context.resolve(path), original);
        JsonObject operation = operation(path, "0".repeat(64), null, 1, 1, 1, 1,
                lines(line("remove", "original"), line("add", "changed")));
        assertRejected(operation, path, original, "file_patch source_sha256 mismatch");
    }

    /** Critical combinations run deterministically rather than depending on random sampling. */
    public void validationRegressions() throws Exception {
        for (String type : new String[] {"context", "remove"}) {
            String path = "patch-mismatch-" + type + ".txt";
            byte[] source = "one\ntwo\nthree\n".getBytes(StandardCharsets.UTF_8);
            Files.write(context.resolve(path), source);
            JsonObject patch = operation(path, sha256(source), null, 1, 1, 1, 1,
                    lines(line("remove", "one"), line("add", "ONE")));
            // A valid first hunk must never be committed if a later hunk fails.
            patch.getAsJsonArray("hunks").add(hunk(2, 1, 2,
                    type.equals("context") ? 1 : 0, lines(line(type, "wrong\ttext"))));
            assertRejected(patch, path, source, "hunk 2", "hunk line 1", "source line 2",
                    "(" + type + ")", "patch text=\"wrong\\ttext\"", "source text=\"two\"",
                    "source_sha256 matched", "target was not modified");
        }
        String eofPath = "patch-eof.txt";
        byte[] original = "one\n".getBytes(StandardCharsets.UTF_8);
        Files.write(context.resolve(eofPath), original);
        assertRejected(operation(eofPath, sha256(original), null, 2, 1, 2, 0,
                lines(line("remove", "absent"))), eofPath, original,
                "source line 2", "source text=<EOF>");

        test("patch-blank-lf-", "\n", 1, 1, 1, 1,
                lines(line("remove", ""), line("add", "filled")), "filled\n");
        test("patch-blank-crlf-", "\r\n", 1, 1, 1, 1,
                lines(line("remove", ""), line("add", "filled")), "filled\n");
        test("patch-empty-", "", 1, 0, 1, 1,
                lines(line("add", "filled")), "filled");
        test("patch-crlf-", "one\r\ntwo\r\n", 2, 1, 2, 1,
                lines(line("remove", "two"), line("add", "TWO")), "one\nTWO\n");
        test("patch-no-final-newline-", "one\ntwo", 2, 1, 2, 1,
                lines(line("remove", "two"), line("add", "TWO")), "one\nTWO");
        test("patch-preserve-bare-cr-", "one\nlast\r", 1, 1, 1, 1,
                lines(line("remove", "one"), line("add", "ONE")), "ONE\nlast\r");

        String crPath = "patch-bare-cr-mismatch.txt";
        byte[] crSource = "last\r".getBytes(StandardCharsets.UTF_8);
        Files.write(context.resolve(crPath), crSource);
        assertRejected(operation(crPath, sha256(crSource), null, 1, 1, 1, 0,
                lines(line("remove", "last"))), crPath, crSource,
                "source text=\"last\\r\"");
    }

    private void assertRejected(JsonObject patch, String path, byte[] source,
            String... details) throws Exception {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> context.execute(patch, Set.of(path)));
        assertArrayEquals(source, Files.readAllBytes(context.resolve(path)));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
        assertTrue(failure.getMessage().contains("Operation 1/1"), failure.getMessage());
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        try (var files = Files.list(context.resolve("."))) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith(".ciop-patch-")),
                    "Rejected patch must not leave a temporary file");
        }
    }

    private void test(String prefix, String source, int oldStart, int oldCount,
            int newStart, int newCount, JsonArray lines, String expected) throws Exception {
        String path = prefix + context.iteration() + ".txt";
        byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        Files.write(context.resolve(path), sourceBytes);
        context.execute(operation(path, sha256(sourceBytes), sha256(expectedBytes), oldStart,
                oldCount, newStart, newCount, lines), Set.of(path));
        assertArrayEquals(expectedBytes, Files.readAllBytes(context.resolve(path)));
    }

}
