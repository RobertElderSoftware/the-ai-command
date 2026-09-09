package org.res.ai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Executable node whose structure is also used as coverage metadata. */
public final class InputSpacePartitionTestNode {
    @FunctionalInterface
    public interface Action {
        void execute(InputSpacePartitionTestContext context) throws Exception;
    }

    private final String name;
    private final List<InputSpacePartitionTestNode> children;
    private final Action action;
    private final boolean choice;

    private InputSpacePartitionTestNode(String name,
            List<InputSpacePartitionTestNode> children, Action action,
            boolean choice) {
        this.name = validateName(name);
        this.children = List.copyOf(Objects.requireNonNull(children, "children"));
        this.action = Objects.requireNonNull(action, "action");
        this.choice = choice;
        Set<String> names = new LinkedHashSet<>();
        for (InputSpacePartitionTestNode child : this.children) {
            if (!names.add(child.name)) {
                throw new IllegalArgumentException("Duplicate child: " + child.name);
            }
        }
        if (choice && this.children.isEmpty()) {
            throw new IllegalArgumentException("A choice node requires children");
        }
    }

    public static InputSpacePartitionTestNode leaf(String name, Action action) {
        return new InputSpacePartitionTestNode(name, List.of(), action, false);
    }

    public static InputSpacePartitionTestNode choice(String name,
            InputSpacePartitionTestNode... children) {
        return new InputSpacePartitionTestNode(
                name, List.of(children), context -> { }, true);
    }

    public static InputSpacePartitionTestNode sequence(String name, Action action,
            InputSpacePartitionTestNode... children) {
        return new InputSpacePartitionTestNode(
                name, List.of(children), action, false);
    }

    public static <T> InputSpacePartitionTestNode stateChoice(String name,
            InputSpacePartitionStateKey<T> key, Map<String, T> choices) {
        return choice(name, choices.entrySet().stream()
                .map(entry -> leaf(entry.getKey(),
                        context -> context.put(key, entry.getValue())))
                .toArray(InputSpacePartitionTestNode[]::new));
    }

    public String name() {
        return name;
    }

    public List<InputSpacePartitionTestNode> children() {
        return children;
    }

    public boolean isLeaf() {
        return children.isEmpty();
    }

    public void execute(InputSpacePartitionTestContext context) throws Exception {
        execute(context, name);
    }

    public void execute(InputSpacePartitionTestContext context, String path)
            throws Exception {
        Objects.requireNonNull(context, "context");
        if (isLeaf()) {
            action.execute(context);
        } else if (choice) {
            String selected = context.coverage().select(context.random(), path);
            InputSpacePartitionTestNode child = children.stream()
                    .filter(candidate -> candidate.name.equals(selected))
                    .findFirst().orElseThrow();
            child.execute(context, path + "." + selected);
        } else {
            action.execute(context);
            for (InputSpacePartitionTestNode child : children) {
                String childPath = path + "." + child.name;
                if (child.isLeaf()) context.coverage().record(childPath);
                child.execute(context, childPath);
            }
        }
    }

    public InputSpacePartitionNode toPartitionNode() {
        return new InputSpacePartitionNode(name, children.stream()
                .map(InputSpacePartitionTestNode::toPartitionNode).toList());
    }

    private static String validateName(String name) {
        String value = Objects.requireNonNull(name, "name");
        if (value.isBlank() || value.indexOf('.') >= 0) {
            throw new IllegalArgumentException(
                    "Partition name must be one nonblank segment: " + value);
        }
        return value;
    }
}
