package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Independent checks of tree traversal, full-path coverage, and fixture lifecycle. */
class InputSpacePartitionInfrastructureTest {
    @Test
    void choiceTotalsIncludeEveryCompletedSequenceLeaf() throws Exception {
        var sequence = InputSpacePartitionTestNode.<String>fixtureSequence(
                "sequence", (fixture, execution) -> { }, List.of(
                        InputSpacePartitionTestNode.fixtureLeaf("one", (fixture, execution) -> { }),
                        InputSpacePartitionTestNode.fixtureLeaf("two", (fixture, execution) -> { })));
        var root = InputSpacePartitionTestNode.fixtureChoice("root", List.of(sequence));
        var coverage = new InputSpacePartitionHierarchy(List.of(root.toPartitionNode()));
        root.execute("fixture", new InputSpacePartitionExecution(new Random(1), coverage, 0));
        assertEquals(1, coverage.count("root.sequence.one"));
        assertEquals(1, coverage.count("root.sequence.two"));
        assertEquals(2, coverage.total("root.sequence"));
        assertEquals(2, coverage.total("root"));
        assertEquals(2, coverage.total(""));
        assertEquals(0, coverage.count("root.sequence"));
        assertThrows(IllegalArgumentException.class, () -> coverage.record("root.sequence"));
        assertThrows(IllegalArgumentException.class, () -> coverage.record(""));
        assertEquals(2, coverage.total(""));
        coverage.assertAllLeavesRecorded();
    }

    @Test
    void reusedContinuationsRequireEveryPathAndCoverageInstancesAreIndependent() {
        InputSpacePartitionTestNode<String> shared = InputSpacePartitionTestNode.fixtureLeaf(
                "same", (fixture, execution) -> { });
        var root = InputSpacePartitionTestNode.fixtureChoice("root", List.of(
                InputSpacePartitionTestNode.fixtureSequence("a", (fixture, execution) -> { }, List.of(shared)),
                InputSpacePartitionTestNode.fixtureSequence("b", (fixture, execution) -> { }, List.of(shared)),
                InputSpacePartitionTestNode.fixtureSequence("c", (fixture, execution) -> { }, List.of(
                        InputSpacePartitionTestNode.<String>fixtureLeaf("same", (fixture, execution) -> { })))));
        var metadata = List.of(root.toPartitionNode());
        var coverage = new InputSpacePartitionHierarchy(metadata);
        coverage.record("root.a.same");
        AssertionError missing = assertThrows(AssertionError.class, coverage::assertAllLeavesRecorded);
        assertTrue(missing.getMessage().contains("root.b.same"));
        assertTrue(missing.getMessage().contains("root.c.same"));
        assertEquals(0, coverage.total("root.b"));
        coverage.record("root.b.same");
        assertThrows(AssertionError.class, coverage::assertAllLeavesRecorded);
        coverage.record("root.c.same");
        coverage.assertAllLeavesRecorded();
        assertThrows(AssertionError.class, new InputSpacePartitionHierarchy(metadata)::assertAllLeavesRecorded);
        assertThrows(IllegalArgumentException.class, () -> coverage.record("unknown"));
        assertThrows(IllegalArgumentException.class, () -> new InputSpacePartitionHierarchy(List.of(
                InputSpacePartitionNode.leaf("same"), InputSpacePartitionNode.leaf("same"))));
    }

    @Test
    void randomTraversalExecutesSetupAndReplaysWithoutCoverageGuidance() throws Exception {
        InputSpacePartitionTestNode<StringBuilder> shared = InputSpacePartitionTestNode.fixtureLeaf(
                "done", (fixture, execution) -> assertEquals("setup", fixture.toString()));
        var root = InputSpacePartitionTestNode.fixtureChoice("root", List.of(
                InputSpacePartitionTestNode.fixtureSequence("a", (fixture, execution) -> fixture.append("setup"), List.of(shared)),
                InputSpacePartitionTestNode.fixtureSequence("b", (fixture, execution) -> fixture.append("setup"), List.of(shared))));
        var metadata = List.of(root.toPartitionNode());
        var first = new InputSpacePartitionHierarchy(metadata);
        var second = new InputSpacePartitionHierarchy(metadata);
        // Deliberately different counts must not influence selection.
        second.record("root.a.done");
        Random firstRandom = new Random(12);
        Random secondRandom = new Random(12);
        Random expected = new Random(12);
        for (int iteration = 0; iteration < 100; iteration++) {
            var a = new InputSpacePartitionExecution(firstRandom, first, iteration);
            var b = new InputSpacePartitionExecution(secondRandom, second, iteration);
            root.execute(new StringBuilder(), a);
            root.execute(new StringBuilder(), b);
            assertEquals("root." + (expected.nextInt(2) == 0 ? "a" : "b") + ".done", a.selectedPath());
            assertEquals(a.completedPaths(), b.completedPaths());
            assertEquals(1, a.completedPaths().size());
        }
        first.assertAllLeavesRecorded();
        second.assertAllLeavesRecorded();
    }

    @Test
    void unrelatedFixturesAreFreshAndSequencesCountMultipleLeaves() throws Exception {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        InputSpacePartitionTestNode<List<String>> sequence = InputSpacePartitionTestNode.fixtureSequence(
                "component", (fixture, execution) -> {
                    assertTrue(fixture.isEmpty());
                    fixture.add("setup");
                }, List.of(
                        InputSpacePartitionTestNode.fixtureLeaf("first", (fixture, execution) -> {
                            assertEquals("component.first", execution.selectedPath());
                            fixture.add("first");
                        }),
                        InputSpacePartitionTestNode.fixtureLeaf("second", (fixture, execution) -> {
                            assertEquals(List.of("setup", "first"), fixture);
                            fixture.add("second");
                        })));
        var lists = new InputSpacePartitionTestSpace<>("lists", List.of(sequence),
                execution -> { created.incrementAndGet(); return new ArrayList<String>(); },
                fixture -> { assertEquals(List.of("setup", "first", "second"), fixture); closed.incrementAndGet(); });
        var strings = new InputSpacePartitionTestSpace<>("strings", List.of(
                InputSpacePartitionTestNode.<String>fixtureLeaf("same", (fixture, execution) -> assertEquals("text", fixture))),
                execution -> "text", fixture -> { });
        var registry = new InputSpacePartitionTestRegistry(List.of(lists, strings));
        assertEquals(2, registry.spaces().size());
        assertThrows(IllegalArgumentException.class, () -> new InputSpacePartitionTestRegistry(List.of(lists, lists)));
        assertThrows(IllegalArgumentException.class, () -> new InputSpacePartitionTestRegistry(List.of()));
        var coverage = lists.newCoverage();
        var otherCoverage = strings.newCoverage();
        for (int iteration = 0; iteration < 2; iteration++) {
            var execution = new InputSpacePartitionExecution(new Random(1), coverage, iteration);
            lists.execute(0, execution);
            assertEquals(iteration, execution.iteration());
            assertEquals(List.of("component.first", "component.second"), execution.completedPaths());
        }
        assertEquals(2, created.get());
        assertEquals(created.get(), closed.get());
        assertEquals(4, coverage.total("component"));
        coverage.assertAllLeavesRecorded();
        assertThrows(AssertionError.class, otherCoverage::assertAllLeavesRecorded);
        strings.execute(0, new InputSpacePartitionExecution(new Random(1), otherCoverage, 0));
        otherCoverage.assertAllLeavesRecorded();
    }

    @Test
    void directoryLifecycleCleansSiblingsLinksAndPartialAcquisition(@TempDir Path parent) throws Exception {
        Path sentinel = Files.writeString(parent.resolve("keep"), "outside");
        var leaf = InputSpacePartitionTestNode.<Path>fixtureLeaf("case", (directory, execution) -> {
            Files.createDirectory(directory.resolveSibling("outside"));
            Files.createSymbolicLink(directory.resolve("link"), sentinel);
        });
        var space = InputSpacePartitionTestSpace.inDirectory("files", parent, List.of(leaf),
                directory -> directory, directory -> directory);
        for (int iteration = 0; iteration < 2; iteration++)
            space.execute(0, new InputSpacePartitionExecution(new Random(1), space.newCoverage(), iteration));
        var broken = InputSpacePartitionTestSpace.<Path>inDirectory("broken", parent, List.of(leaf), directory -> {
            Files.writeString(directory.resolve("partial"), "data");
            throw new IOException("factory failed");
        }, directory -> directory);
        var execution = new InputSpacePartitionExecution(new Random(1), broken.newCoverage(), 0);
        IOException failure = assertThrows(IOException.class, () -> broken.execute(0, execution));
        assertEquals("factory failed", failure.getMessage());
        assertEquals("case", execution.selectedPath());
        assertTrue(execution.completedPaths().isEmpty());
        assertEquals("outside", Files.readString(sentinel));
        try (var paths = Files.list(parent)) { assertEquals(List.of(sentinel), paths.toList()); }
    }

    @Test
    void cleanupPreservesPrimaryFailureAndFailedLeavesAreNotRecorded() {
        var space = new InputSpacePartitionTestSpace<>("failure", List.of(
                InputSpacePartitionTestNode.<String>fixtureLeaf("case", (fixture, execution) -> {
                    throw new IllegalArgumentException("scenario failed");
                })), execution -> "fixture", fixture -> { throw new IllegalStateException("cleanup failed"); });
        var coverage = space.newCoverage();
        var execution = new InputSpacePartitionExecution(new Random(9), coverage, 0);
        var failure = assertThrows(IllegalArgumentException.class, () -> space.execute(0, execution));
        assertEquals("scenario failed", failure.getMessage());
        assertEquals("cleanup failed", failure.getSuppressed()[0].getMessage());
        assertEquals("case", execution.selectedPath());
        assertTrue(execution.completedPaths().isEmpty());
        assertEquals(0, coverage.count("case"));
        assertThrows(AssertionError.class, coverage::assertAllLeavesRecorded);
    }

    @Test
    void completedLeafCountsDoNotHideLaterSequenceOrCleanupFailures() {
        var sequence = InputSpacePartitionTestNode.<String>fixtureSequence("sequence", (fixture, execution) -> { }, List.of(
                InputSpacePartitionTestNode.fixtureLeaf("one", (fixture, execution) -> { }),
                InputSpacePartitionTestNode.fixtureLeaf("two", (fixture, execution) -> { throw new AssertionError("later"); })));
        AtomicInteger closed = new AtomicInteger();
        var space = new InputSpacePartitionTestSpace<>("sequence", List.of(sequence),
                execution -> "fixture", fixture -> closed.incrementAndGet());
        var execution = new InputSpacePartitionExecution(new Random(1), space.newCoverage(), 0);
        assertThrows(AssertionError.class, () -> space.execute(0, execution));
        assertEquals(1, closed.get());
        assertEquals(List.of("sequence.one"), execution.completedPaths());
        assertEquals("sequence.two", execution.selectedPath());
        assertThrows(AssertionError.class, execution.coverage()::assertAllLeavesRecorded);
        var cleanupFailure = new InputSpacePartitionTestSpace<>("cleanup", List.of(
                InputSpacePartitionTestNode.<String>fixtureLeaf("done", (fixture, facilities) -> { })),
                facilities -> "fixture", fixture -> { throw new IOException("cleanup only"); });
        var cleanupExecution = new InputSpacePartitionExecution(new Random(1), cleanupFailure.newCoverage(), 0);
        IOException error = assertThrows(IOException.class, () -> cleanupFailure.execute(0, cleanupExecution));
        assertEquals("cleanup only", error.getMessage());
        assertEquals(List.of("done"), cleanupExecution.completedPaths());
        // Coverage describes completed actions; execute still propagates a failed fixture lifecycle.
    }
}
