package org.res.ai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable node in an input-space partition hierarchy. */
public final class InputSpacePartitionNode {
    private final String name;
    private final List<InputSpacePartitionNode> children;

    public InputSpacePartitionNode(String name, List<InputSpacePartitionNode> children) {
        this.name = validateName(name);
        this.children = validateChildren(children);
    }

    /** Creates a hierarchy node with the supplied ordered children. */
    public static InputSpacePartitionNode node(
            String name, InputSpacePartitionNode... children) {
        Objects.requireNonNull(children, "children");
        return new InputSpacePartitionNode(name, List.of(children));
    }

    /** Creates a hierarchy node with no children. */
    public static InputSpacePartitionNode leaf(String name) {
        return new InputSpacePartitionNode(name, List.of());
    }

    public String name() {
        return name;
    }

    public List<InputSpacePartitionNode> children() {
        return children;
    }

    public boolean isLeaf() {
        return children.isEmpty();
    }

    private static String validateName(String name) {
        String nonNullName = Objects.requireNonNull(name, "name");
        if (nonNullName.isBlank()) {
            throw new IllegalArgumentException("Hierarchy node name must not be blank");
        }
        if (nonNullName.indexOf('.') >= 0) {
            throw new IllegalArgumentException(
                    "Hierarchy node name must be a single segment: " + nonNullName);
        }
        return nonNullName;
    }

    private static List<InputSpacePartitionNode> validateChildren(
            List<InputSpacePartitionNode> children) {
        Objects.requireNonNull(children, "children");
        List<InputSpacePartitionNode> copy = List.copyOf(children);
        Set<String> childNames = new LinkedHashSet<>();
        for (InputSpacePartitionNode child : copy) {
            if (!childNames.add(child.name())) {
                throw new IllegalArgumentException(
                        "Duplicate child hierarchy node: " + child.name());
            }
        }
        return copy;
    }
}
