package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.res.ai.TestJson.*;

class AICommandApplicationTest {
    private static final int ITERATIONS = 20_000;
    private static final long SEED = 0xC10F2026L;

    @TempDir
    Path temporaryDirectory;

    static InputSpacePartitionTestSpace<Path> loggingSpace(Path parent) {
        return InputSpacePartitionTestSpace.inDirectory("logging", parent, List.of(
                InputSpacePartitionTestNode.<Path>fixtureLeaf("enabled",
                        (directory, execution) -> assertLogging(directory, true)),
                InputSpacePartitionTestNode.<Path>fixtureLeaf("disabled",
                        (directory, execution) -> assertLogging(directory, false))),
                directory -> directory, directory -> directory);
    }

    static InputSpacePartitionTestSpace<Path> patchRegressionSpace(Path parent) {
        return InputSpacePartitionTestSpace.inDirectory("patch_regressions", parent, List.of(
                InputSpacePartitionTestNode.<Path>fixtureLeaf("validation", (directory, execution) ->
                        new FilePatchTestFixture(new InputSpacePartitionTestContext(
                                directory, execution)).validationRegressions())),
                directory -> directory, directory -> directory);
    }

    @Test
    void registeredApplicationScenariosAreProcessedCorrectly() {
        var spaces = InputSpacePartitionTestRegistry.applicationSpaces(temporaryDirectory).spaces();
        var coverage = spaces.stream().map(InputSpacePartitionTestSpace::newCoverage).toList();
        Random random = new Random(SEED);
        int[] executions = new int[spaces.size()];
        long[] leaves = new long[spaces.size()];
        System.out.println("Seed: " + SEED + "; random sampling budget: " + ITERATIONS);
        try {
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                int index = random.nextInt(spaces.size());
                InputSpacePartitionTestSpace<?> space = spaces.get(index);
                int scenario = random.nextInt(space.scenarios().size());
                InputSpacePartitionExecution execution = new InputSpacePartitionExecution(
                        random, coverage.get(index), executions[index]);
                try {
                    space.execute(scenario, execution);
                } catch (Exception | AssertionError failure) {
                    throw new AssertionError("seed=" + SEED + ", space=" + space.name()
                            + ", iteration=" + iteration + ", component execution=" + executions[index]
                            + ", path=" + execution.selectedPath(), failure);
                }
                executions[index]++;
                leaves[index] += execution.completedPaths().size();
            }
        } finally {
            System.out.println("Successful fixture executions: " + Arrays.stream(executions).sum()
                    + "; successful leaf executions: " + Arrays.stream(leaves).sum());
            spaces.forEach(space -> {
                int index = spaces.indexOf(space);
                System.out.println(space.name() + ": " + executions[index]
                        + " fixture executions, " + leaves[index] + " successful leaves");
                coverage.get(index).printPaths(System.out);
            });
        }
        // Print every tree before checking coverage so all zero counts remain visible on failure.
        assertAll("Every declared leaf path must be sampled; seed=" + SEED,
                coverage.stream().map(tree -> (org.junit.jupiter.api.function.Executable)
                        tree::assertAllLeavesRecorded));
        assertEquals(ITERATIONS, Arrays.stream(executions).sum());
        // These application declarations each execute one leaf; generic sequences may execute more.
        assertEquals((long) ITERATIONS, Arrays.stream(leaves).sum());
    }

    private static void assertLogging(Path directory, boolean enabled) throws Exception {
        String identifier = UUID.randomUUID().toString();
        String message = "Logging check: café, 日本語, 🚀 " + identifier + "\n";
        String prompt = batch(stdout(message));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            try (AICommandApplication application = new AICommandApplication(
                    new LoopbackLLMProvider(enabled), output, directory)) {
                application.executePrompt(prompt, Set.of());
            }
            assertEquals(message, output.toString(StandardCharsets.UTF_8));
            List<Path> logs = matchingLogs(identifier);
            assertEquals(enabled ? 2 : 0, logs.size(),
                    "Unexpected number of prompt/response logs; logging=" + enabled);
            for (String suffix : List.of("-prompt.txt", "-response.txt")) {
                assertEquals(enabled ? 1L : 0L, logs.stream()
                        .filter(path -> path.getFileName().toString().endsWith(suffix))
                        .count(), "Unexpected log count for " + suffix);
            }
            for (Path log : logs) {
                assertEquals(prompt, Files.readString(log, StandardCharsets.UTF_8),
                        "Loopback must log the exact request and echoed response");
            }
        } finally {
            // Remove only this scenario's uniquely identified logs, even on failure.
            for (Path log : matchingLogs(identifier)) Files.deleteIfExists(log);
        }
    }

    private static List<Path> matchingLogs(String identifier) throws Exception {
        String pid = "-" + ProcessHandle.current().pid() + "-";
        List<Path> matches = new ArrayList<>();
        try (var paths = Files.list(Path.of("/tmp"))) {
            for (Path path : paths.filter(candidate -> {
                String name = candidate.getFileName().toString();
                return name.startsWith("ai-") && name.contains(pid)
                        && (name.endsWith("-prompt.txt") || name.endsWith("-response.txt"));
            }).toList()) {
                if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        && Files.readString(path, StandardCharsets.UTF_8).contains(identifier)) {
                    matches.add(path);
                }
            }
        }
        return matches;
    }
}
