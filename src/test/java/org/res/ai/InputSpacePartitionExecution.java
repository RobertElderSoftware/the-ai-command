package org.res.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Randomness, coverage, and diagnostics for one fixture execution. */
public final class InputSpacePartitionExecution {
    private final Random random;
    private final InputSpacePartitionHierarchy coverage;
    private final int iteration;
    private final List<String> completedPaths = new ArrayList<>();
    private String selectedPath = "";

    public InputSpacePartitionExecution(Random random,
            InputSpacePartitionHierarchy coverage, int iteration) {
        this.random = Objects.requireNonNull(random, "random");
        this.coverage = Objects.requireNonNull(coverage, "coverage");
        if (iteration < 0) throw new IllegalArgumentException("Negative iteration");
        this.iteration = iteration;
    }

    public Random random() { return random; }
    public InputSpacePartitionHierarchy coverage() { return coverage; }
    public int iteration() { return iteration; }
    public String selectedPath() { return selectedPath; }
    public List<String> completedPaths() { return List.copyOf(completedPaths); }
    void enter(String path) { selectedPath = path; }
    void complete(String path) { completedPaths.add(path); }
}
