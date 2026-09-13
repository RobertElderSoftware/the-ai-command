package org.res.ai;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Operation fixture and typed state; execution facilities belong to the scheduler. */
public final class InputSpacePartitionTestContext {
    public static final InputSpacePartitionStateKey<Encoding> ENCODING =
            InputSpacePartitionStateKey.of("encoding", Encoding.class);
    public static final InputSpacePartitionStateKey<TestVerificationMode> VERIFICATION =
            InputSpacePartitionStateKey.of("verification", TestVerificationMode.class);
    public static final InputSpacePartitionStateKey<Integer> PATH_DEPTH =
            InputSpacePartitionStateKey.of("path depth", Integer.class);
    public static final InputSpacePartitionStateKey<Boolean> TARGET_EXISTS =
            InputSpacePartitionStateKey.of("target exists", Boolean.class);

    private static final List<String> TEXT = List.of("", "plain ASCII text",
            "line one\nline two\n", "Unicode: café, 日本語, 🚀",
            "tabs\tand carriage returns\r\n");
    private static final int[] BINARY_LENGTHS = {0, 1, 16, 255};
    private final InputSpacePartitionExecution execution;
    private final Path directory;
    private final Map<InputSpacePartitionStateKey<?>, Object> state = new IdentityHashMap<>();

    public InputSpacePartitionTestContext(Path directory, InputSpacePartitionExecution execution) {
        this.execution = Objects.requireNonNull(execution, "execution");
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (!Files.isDirectory(this.directory)) throw new IllegalArgumentException("Invalid test directory");
    }

    public int iteration() { return execution.iteration(); }

    public Path resolve(String path) {
        Path result = directory.resolve(path).normalize();
        if (!result.startsWith(directory)) {
            throw new IllegalArgumentException("Test path escapes directory: " + path);
        }
        return result;
    }

    public <T> void put(InputSpacePartitionStateKey<T> key, T value) {
        state.put(key, key.validateValue(Objects.requireNonNull(value, "value")));
    }

    public <T> T require(InputSpacePartitionStateKey<T> key) {
        Object value = state.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing scenario state: " + key.name());
        }
        return key.validateValue(value);
    }

    public byte[] randomPayload(Encoding encoding) {
        Random random = execution.random();
        if (encoding == Encoding.UTF_8) {
            return TEXT.get(random.nextInt(TEXT.size())).getBytes(StandardCharsets.UTF_8);
        }
        int choice = random.nextInt(BINARY_LENGTHS.length + 1);
        int length = choice < BINARY_LENGTHS.length
                ? BINARY_LENGTHS[choice] : random.nextInt(1024);
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }

    public byte[] execute(JsonObject operation, Set<String> allowed) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (AICommandApplication application = application(output, directory)) {
            application.executePrompt(TestJson.batch(operation), allowed);
        }
        return output.toByteArray();
    }

    public void testStdout() throws Exception {
        Encoding encoding = require(ENCODING);
        byte[] expected = randomPayload(encoding);
        JsonObject operation = TestJson.operation(OperationType.STDOUT, null, encoding, expected);
        require(VERIFICATION).apply(operation, expected);
        assertArrayEquals(expected, execute(operation, Set.of()));
    }

    public void testFileWrite() throws Exception {
        String path = switch (require(PATH_DEPTH)) {
            case 0 -> "result-" + iteration() + ".dat";
            case 1 -> "nested/result-" + iteration() + ".dat";
            case 2 -> "deeply/nested/result-" + iteration() + ".dat";
            default -> throw new IllegalStateException("Unexpected path depth");
        };
        Path target = resolve(path);
        if (require(TARGET_EXISTS)) {
            Files.createDirectories(target.getParent());
            Files.write(target, randomPayload(Encoding.BASE64));
        } else {
            assertFalse(Files.exists(target));
        }
        Encoding encoding = require(ENCODING);
        byte[] expected = randomPayload(encoding);
        JsonObject operation = TestJson.operation(OperationType.FILE_WRITE, path, encoding, expected);
        require(VERIFICATION).apply(operation, expected);
        execute(operation, Set.of(path));
        assertArrayEquals(expected, Files.readAllBytes(target));
    }

    public void assertInputRejected(Path workingDirectory, String path) {
        try (AICommandApplication application = application(
                new ByteArrayOutputStream(), workingDirectory)) {
            assertThrows(IllegalArgumentException.class,
                    () -> application.run(new byte[0], List.of(path)));
        }
    }

    public void assertWriteRejected(Path workingDirectory, String path, Encoding encoding) throws Exception {
        JsonObject operation = TestJson.operation(OperationType.FILE_WRITE, path,
                encoding, "replacement".getBytes(StandardCharsets.UTF_8));
        try (AICommandApplication application = application(
                new ByteArrayOutputStream(), workingDirectory)) {
            assertThrows(IllegalArgumentException.class,
                    () -> application.executePrompt(TestJson.batch(operation), Set.of(path)));
        }
    }

    private static AICommandApplication application(OutputStream output, Path directory) {
        return new AICommandApplication(new LoopbackLLMProvider(), output, directory);
    }
}
