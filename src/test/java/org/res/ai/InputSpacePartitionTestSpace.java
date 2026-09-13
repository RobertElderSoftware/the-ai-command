package org.res.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Scenario roots and fresh fixture lifecycle for one independent component. */
public final class InputSpacePartitionTestSpace<F> {
    @FunctionalInterface
    public interface Factory<F> {
        F create(InputSpacePartitionExecution execution) throws Exception;
    }

    @FunctionalInterface
    public interface Cleanup<F> {
        void close(F fixture) throws Exception;
    }

    @FunctionalInterface
    public interface DirectoryFactory<F> {
        F create(Path directory) throws Exception;
    }

    @FunctionalInterface
    public interface DirectoryExecutionFactory<F> {
        F create(Path directory, InputSpacePartitionExecution execution) throws Exception;
    }

    public static <F> InputSpacePartitionTestSpace<F> inDirectory(String name,
            Path parent, List<InputSpacePartitionTestNode<F>> scenarios,
            DirectoryFactory<F> factory, Function<F, Path> directory) {
        return inDirectory(name, parent, scenarios, (path, execution) -> factory.create(path), directory);
    }

    /** A private container also owns sibling fixtures used by containment tests. */
    public static <F> InputSpacePartitionTestSpace<F> inDirectory(String name,
            Path parent, List<InputSpacePartitionTestNode<F>> scenarios,
            DirectoryExecutionFactory<F> factory, Function<F, Path> directory) {
        return new InputSpacePartitionTestSpace<>(name, scenarios, execution -> {
            Path container = Files.createTempDirectory(parent, name + "-");
            try {
                return Objects.requireNonNull(factory.create(
                        Files.createDirectory(container.resolve("work")), execution), "fixture");
            } catch (Exception | Error failure) {
                try { deleteDirectory(container); }
                catch (Exception | Error cleanup) {
                    if (failure != cleanup) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }, fixture -> deleteDirectory(directory.apply(fixture).getParent()));
    }

    private static void deleteDirectory(Path directory) throws java.io.IOException {
        // Files.walk does not follow symlinks.
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private final String name;
    private final List<InputSpacePartitionTestNode<F>> scenarios;
    private final Factory<F> factory;
    private final Cleanup<F> cleanup;

    public InputSpacePartitionTestSpace(String name, List<InputSpacePartitionTestNode<F>> scenarios,
            Factory<F> factory, Cleanup<F> cleanup) {
        this.name = InputSpacePartitionTestNode.validateName(name);
        this.scenarios = InputSpacePartitionTestNode.validateChildren(scenarios, InputSpacePartitionTestNode::name);
        if (this.scenarios.isEmpty()) throw new IllegalArgumentException("A space needs scenarios");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    public String name() { return name; }
    public List<InputSpacePartitionTestNode<F>> scenarios() { return scenarios; }

    InputSpacePartitionHierarchy newCoverage() {
        return new InputSpacePartitionHierarchy(scenarios);
    }

    void execute(int scenario, InputSpacePartitionExecution execution) throws Exception {
        InputSpacePartitionTestNode<F> node = scenarios.get(scenario);
        execution.enter(node.name());
        // Custom factories must clean partial acquisition if they fail before returning.
        F fixture = Objects.requireNonNull(factory.create(execution), "fixture");
        Throwable failure = null;
        try {
            node.execute(fixture, execution);
        } catch (Exception | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                cleanup.close(fixture);
            } catch (Exception | Error exception) {
                if (failure == null) throw exception;
                if (failure != exception) failure.addSuppressed(exception);
            }
        }
    }
}
