package org.res.ai;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Filesystem topology and common assertions for containment scenarios. */
public final class ContainmentTestFixture {
    public static final InputSpacePartitionStateKey<ContainmentTestFixture> KEY =
            InputSpacePartitionStateKey.of(
                    "containment fixture", ContainmentTestFixture.class);

    private final Path workingDirectory;
    private final Path outsideFile;
    private final Path outsideMissingTarget;
    private final byte[] originalData =
            "must remain unchanged".getBytes(StandardCharsets.UTF_8);

    public ContainmentTestFixture(InputSpacePartitionTestContext context)
            throws Exception {
        workingDirectory = Files.createDirectory(context.resolve("sandbox"));
        Path outside = Files.createDirectory(context.resolve("outside"));
        outsideFile = outside.resolve("secret.txt");
        outsideMissingTarget = outside.resolve("new-file.txt");
        Files.write(outsideFile, originalData);
        assertFalse(outsideFile.toRealPath().startsWith(
                workingDirectory.toRealPath()));
        Path link = workingDirectory.resolve("outside-link");
        Files.createSymbolicLink(link, outside);
        assertTrue(Files.isSymbolicLink(link));
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public List<String> traversalPaths() {
        return List.of("../outside/secret.txt",
                "nested/../../outside/secret.txt",
                "one/two/../../../outside/secret.txt", "foo/../..", "..",
                outsideFile.toAbsolutePath().toString());
    }

    public String existingSymlinkPath() {
        return "outside-link/secret.txt";
    }

    public String missingSymlinkPath() {
        return "outside-link/new-file.txt";
    }

    public void assertOutsideUnchanged() throws Exception {
        assertArrayEquals(originalData, Files.readAllBytes(outsideFile));
    }

    public void assertMissingTargetUncreated() {
        assertFalse(Files.exists(outsideMissingTarget));
    }
}
