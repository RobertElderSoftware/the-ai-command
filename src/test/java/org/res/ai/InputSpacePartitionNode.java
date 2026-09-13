package org.res.ai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Immutable metadata; every distinct leaf path is a coverage obligation. */
public final class InputSpacePartitionNode {
    private final String name;
    private final List<InputSpacePartitionNode> children;

    public InputSpacePartitionNode(String name, List<InputSpacePartitionNode> children) {
        this.name = validateName(name);
        this.children = validateChildren(children, InputSpacePartitionNode::name);
    }

    public static InputSpacePartitionNode node(String name, InputSpacePartitionNode... children) {
        return new InputSpacePartitionNode(name, List.of(children));
    }

    public static InputSpacePartitionNode leaf(String name) { return node(name); }
    public String name() { return name; }
    public List<InputSpacePartitionNode> children() { return children; }
    public boolean isLeaf() { return children.isEmpty(); }

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
