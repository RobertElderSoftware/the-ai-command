package org.res.ai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Executable tree parameterized by its component fixture, also used for coverage reporting. */
public final class InputSpacePartitionTestNode<F> {
    /** A fixture action that does not need execution facilities. */
    @FunctionalInterface
    public interface ExceptionConsumer<T> {
        void accept(T value) throws Exception;
    }

    @FunctionalInterface
    public interface FixtureAction<F> {
        void execute(F fixture, InputSpacePartitionExecution execution) throws Exception;
    }

    private final String name;
    private final List<InputSpacePartitionTestNode<F>> children;
    private final FixtureAction<F> action;
    private final boolean choice;

    private InputSpacePartitionTestNode(String name, List<InputSpacePartitionTestNode<F>> children,
            FixtureAction<F> action, boolean choice) {
        this.name = validateName(name);
        this.children = validateChildren(children, InputSpacePartitionTestNode::name);
        this.action = Objects.requireNonNull(action, "action");
        this.choice = choice;
        if (choice && this.children.isEmpty())
            throw new IllegalArgumentException("A choice node requires children");
    }

    public static <F> InputSpacePartitionTestNode<F> fixtureLeaf(String name, FixtureAction<F> action) {
        return new InputSpacePartitionTestNode<>(name, List.of(), action, false);
    }

    /** Adapts fixture-only actions without wrapping their exceptions or assertion errors. */
    public static <F> InputSpacePartitionTestNode<F> fixtureLeaf(String name, ExceptionConsumer<F> action) {
        Objects.requireNonNull(action, "action");
        return fixtureLeaf(name, (fixture, execution) -> action.accept(fixture));
    }

    public static <F> InputSpacePartitionTestNode<F> fixtureChoice(
            String name, List<InputSpacePartitionTestNode<F>> children) {
        return new InputSpacePartitionTestNode<>(name, children, (fixture, execution) -> { }, true);
    }

    public static <F> InputSpacePartitionTestNode<F> fixtureSequence(String name,
            FixtureAction<F> action, List<InputSpacePartitionTestNode<F>> children) {
        return new InputSpacePartitionTestNode<>(name, children, action, false);
    }

    /** Each value supplies an action followed by the shared continuation, if any. */
    public static <F, T> InputSpacePartitionTestNode<F> fixtureValues(String name,
            List<T> values, Function<T, String> naming,
            Function<T, FixtureAction<F>> actions, List<InputSpacePartitionTestNode<F>> continuation) {
        return fixtureChoice(name, List.copyOf(values).stream().map(value ->
                fixtureSequence(naming.apply(value), actions.apply(value), continuation)).toList());
    }

    public String name() { return name; }
    public List<InputSpacePartitionTestNode<F>> children() { return children; }
    public boolean isLeaf() { return children.isEmpty(); }

    public void execute(F fixture, InputSpacePartitionExecution execution) throws Exception {
        execute(fixture, execution, name);
    }

    private void execute(F fixture, InputSpacePartitionExecution execution, String path) throws Exception {
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(execution, "execution").enter(path);
        if (choice) {
            InputSpacePartitionTestNode<F> child = children.get(execution.random().nextInt(children.size()));
            child.execute(fixture, execution, path + "." + child.name);
        } else {
            action.execute(fixture, execution);
            for (InputSpacePartitionTestNode<F> child : children)
                child.execute(fixture, execution, path + "." + child.name);
            if (isLeaf()) {
                execution.coverage().record(path);
                execution.complete(path);
            }
        }
    }

    static String validateName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.indexOf('.') >= 0)
            throw new IllegalArgumentException("Hierarchy node name must be one nonblank segment: " + name);
        return name;
    }

    static <T> List<T> validateChildren(List<T> children, Function<T, String> naming) {
        List<T> copy = List.copyOf(Objects.requireNonNull(children, "children"));
        Set<String> names = new LinkedHashSet<>();
        for (T child : copy) {
            String name = naming.apply(child);
            if (!names.add(name))
                throw new IllegalArgumentException("Duplicate child hierarchy node: " + name);
        }
        return copy;
    }
}
