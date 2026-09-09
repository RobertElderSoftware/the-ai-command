package org.res.ai;

import java.io.PrintStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Counts selections in a declared input-space partition tree. */
public final class InputSpacePartitionHierarchy {
    private static final Set<String> HIDDEN_DIMENSIONS = Set.of(
            "encoding", "target", "path_depth", "verification");

    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, List<String>> children = new LinkedHashMap<>();

    public InputSpacePartitionHierarchy(
            Collection<InputSpacePartitionNode> roots) {
        List<InputSpacePartitionNode> rootList = List.copyOf(
                Objects.requireNonNull(roots, "roots"));
        children.put("", names(rootList, ""));
        for (InputSpacePartitionNode root : rootList) initialize(root, "");
    }

    public String select(Random random, String category) {
        List<String> choices = children.get(category);
        if (choices == null || choices.isEmpty()) {
            throw new IllegalArgumentException(
                    "Category has no children: " + category);
        }
        String selected = choices.get(random.nextInt(choices.size()));
        record(selected);
        return segment(selected);
    }

    public void record(String category) {
        if (!counts.containsKey(category)) {
            throw new IllegalArgumentException(
                    "Category is not declared: " + category);
        }
        counts.merge(category, 1, Integer::sum);
    }

    public int count(String category) {
        return counts.getOrDefault(category, 0);
    }

    public int total(String category) {
        int directCount = count(category);
        if (directCount > 0) return directCount;
        return children.getOrDefault(category, List.of()).stream()
                .mapToInt(this::total).sum();
    }

    public void assertAllLeavesRecorded() {
        Map<String, Integer> leafValueCounts = new LinkedHashMap<>();
        counts.forEach((category, count) -> {
            if (children.getOrDefault(category, List.of()).isEmpty()) {
                leafValueCounts.merge(segment(category), count, Integer::sum);
            }
        });
        leafValueCounts.forEach((leaf, count) -> assertTrue(count > 0,
                () -> "Input-space partition was not exercised: " + leaf));
    }

    public void printSummary(PrintStream output) {
        output.println("Total Tests Run: " + total("operation"));
        List<String> branches = children.getOrDefault("operation", List.of());
        for (int index = 0; index < branches.size(); index++) {
            printBranch(output, branches.get(index), "",
                    index == branches.size() - 1);
        }
    }

    private void initialize(InputSpacePartitionNode node, String parent) {
        String path = qualify(parent, node.name());
        if (counts.putIfAbsent(path, 0) != null) {
            throw new IllegalArgumentException("Duplicate category: " + path);
        }
        children.put(path, names(node.children(), path));
        for (InputSpacePartitionNode child : node.children()) initialize(child, path);
    }

    private void printBranch(PrintStream output, String category,
            String prefix, boolean last) {
        String connector = last ? "└─" : "├─";
        output.println(prefix + connector + segment(category) + ": "
                + total(category));
        String childPrefix = prefix + (last ? "  " : "│ ");
        printChildren(output, category, childPrefix);
    }

    private void printChildren(PrintStream output, String category,
            String prefix) {
        List<String> descendants = children.getOrDefault(category, List.of());
        if (descendants.size() == 1
                && HIDDEN_DIMENSIONS.contains(segment(descendants.get(0)))) {
            printChildren(output, descendants.get(0), prefix);
            return;
        }
        for (int index = 0; index < descendants.size(); index++) {
            printBranch(output, descendants.get(index), prefix,
                    index == descendants.size() - 1);
        }
    }

    private static List<String> names(
            List<InputSpacePartitionNode> nodes, String parent) {
        return nodes.stream().map(node -> qualify(parent, node.name())).toList();
    }

    private static String qualify(String parent, String child) {
        return parent.isEmpty() ? child : parent + "." + child;
    }

    private static String segment(String category) {
        int dot = category.lastIndexOf('.');
        return dot < 0 ? category : category.substring(dot + 1);
    }
}
