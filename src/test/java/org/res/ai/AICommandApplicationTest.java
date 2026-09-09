package org.res.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AICommandApplicationTest {
    private static final int ITERATIONS = 100;

    @TempDir
    Path temporaryDirectory;

    @Test
    void randomizedOperationsAreProcessedCorrectly() throws Exception {
        Random random = new Random(0xC10F2026L);
        InputSpacePartitionTestTreeFactory tree =
                new InputSpacePartitionTestTreeFactory();
        InputSpacePartitionHierarchy coverage =
                new InputSpacePartitionHierarchy(tree.partitionRoots());
        EnumSet<OperationType> tested = EnumSet.noneOf(OperationType.class);

        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            tested.add(tree.executeRandomOperation(context(
                    random, coverage, iteration)));
        }
        assertEquals(EnumSet.allOf(OperationType.class), tested);

        tree.executeContainmentTests(context(random, coverage, ITERATIONS));
        coverage.assertAllLeavesRecorded();
        coverage.printSummary(System.out);
    }

    private InputSpacePartitionTestContext context(Random random,
            InputSpacePartitionHierarchy coverage, int iteration) {
        return new InputSpacePartitionTestContext(
                random, temporaryDirectory, coverage, iteration);
    }
}
