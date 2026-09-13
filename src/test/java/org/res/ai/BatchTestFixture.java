package org.res.ai;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.res.ai.TestJson.*;

/** Fresh filesystem state and failure injection for one executable batch scenario. */
public final class BatchTestFixture {
    private static final String CREATED = "new/deep/created.txt";
    private static final Set<String> ALLOWED = Set.of(
            "target.txt", "./target.txt", CREATED, "target.txt/child");
    private final Path directory;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final JsonObject write = fileWrite("target.txt", "new\n");
    private final JsonObject create = fileWrite(CREATED, "new\n");
    private final JsonObject outputOp = stdout("visible");
    private boolean failCommit;
    private boolean failRestore;
    private boolean failOutput;

    public BatchTestFixture(Path directory) throws IOException {
        this.directory = directory;
        Files.writeString(directory.resolve("target.txt"), "original\n");
    }

    /** Scenarios are sampled randomly; names are coverage labels, never dispatch keys. */
    public static InputSpacePartitionTestSpace<BatchTestFixture> space(Path parent) {
        return InputSpacePartitionTestSpace.inDirectory("batch", parent, scenarios(),
                BatchTestFixture::new, fixture -> fixture.directory);
    }

    private static java.util.List<InputSpacePartitionTestNode<BatchTestFixture>> scenarios() {
        return java.util.List.of(
                rejected("invalid_patch", f -> new JsonObject[] {f.outputOp, f.create, f.write,
                        replaceFirstLine("./target.txt", "new\n", "wrong", "patched")}),
                rejected("invalid_hash", f -> {
                    JsonObject wrongLength = f.outputOp.deepCopy();
                    wrongLength.addProperty("length", 999);
                    return new JsonObject[] {f.write, wrongLength};
                }),
                rejected("conflict", f -> new JsonObject[] {
                        f.write, fileWrite("target.txt/child", "new\n")}),
                leaf("chain", null, null, "patched\n", null, "visiblevisible", f ->
                        new JsonObject[] {f.write,
                                replaceFirstLine("./target.txt", "new\n", "new", "patched"),
                                f.outputOp, f.outputOp}),
                leaf("create_patch", null, null, "original\n", "patched\n", "", f ->
                        new JsonObject[] {f.create,
                                replaceFirstLine(CREATED, "new\n", "new", "patched")}),
                leaf("commit_failure", IOException.class, "rollback completed", "original\n", null, "", f -> {
                    f.failCommit = true;
                    return new JsonObject[] {f.write, f.create, f.outputOp};
                }),
                leaf("rollback_failure", IOException.class, "rollback incomplete", "original\n", "new\n", "", f -> {
                    f.failCommit = true;
                    f.failRestore = true;
                    return new JsonObject[] {f.write, f.create, f.outputOp};
                }),
                leaf("stdout_failure", IOException.class, "Files committed", "new\n", "new\n", "", f -> {
                    f.failOutput = true;
                    return new JsonObject[] {f.write, f.create, f.outputOp};
                }));
    }

    private static InputSpacePartitionTestNode<BatchTestFixture> rejected(String name,
            Function<BatchTestFixture, JsonObject[]> operations) {
        return leaf(name, IllegalArgumentException.class, "Batch validation failed",
                "original\n", null, "", operations);
    }

    /** Scenario declarations supply inputs and postconditions; execution remains shared. */
    private static InputSpacePartitionTestNode<BatchTestFixture> leaf(String name, Class<? extends Exception> failureType,
            String diagnostic, String targetContents, String createdContents, String stdoutContents,
            Function<BatchTestFixture, JsonObject[]> operations) {
        return InputSpacePartitionTestNode.fixtureLeaf(name, (fixture, execution) -> {
            Path directory = fixture.directory;
            fixture.execute(failureType, diagnostic, operations.apply(fixture));
            assertEquals(targetContents, Files.readString(directory.resolve("target.txt")), name);
            assertEquals(stdoutContents, fixture.output.toString(StandardCharsets.UTF_8), name);
            if (createdContents == null) assertFalse(Files.exists(directory.resolve("new")), name);
            else assertEquals(createdContents, Files.readString(directory.resolve(CREATED)), name);
        });
    }

    private void execute(Class<? extends Exception> failureType,
            String diagnostic, JsonObject... operations) throws Exception {
        OutputStream destination = failOutput ? new OutputStream() {
            @Override public void write(int value) throws IOException {
                throw new IOException("injected output failure");
            }
        } : output;
        try (AICommandApplication application = new AICommandApplication(
                new LoopbackLLMProvider(), destination, directory) {
            @Override protected void commitFile(Path path, byte[] bytes) throws IOException {
                super.commitFile(path, bytes);
                if (failCommit && path.equals(directory.resolve(CREATED)))
                    throw new IOException("injected failure after write");
            }
            @Override protected void restoreFile(Path path, byte[] bytes) throws IOException {
                if (failRestore && path.equals(directory.resolve(CREATED)))
                    throw new IOException("injected rollback failure");
                super.restoreFile(path, bytes);
            }
        }) {
            String prompt = batch(operations);
            if (failureType == null) application.executePrompt(prompt, ALLOWED);
            else {
                Exception failure = assertThrows(failureType,
                        () -> application.executePrompt(prompt, ALLOWED));
                assertTrue(failure.getMessage().contains(diagnostic), failure.getMessage());
                if (failRestore) assertTrue(failure.getSuppressed().length > 0);
            }
        }
    }
}
