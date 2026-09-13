package org.res.ai;

import java.io.PrintStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Counts full leaf paths independently; branch totals sum their descendants. */
public final class InputSpacePartitionHierarchy {
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final List<InputSpacePartitionNode> roots;

    public InputSpacePartitionHierarchy(Collection<InputSpacePartitionNode> roots) {
        this.roots = InputSpacePartitionNode.validateChildren(
                List.copyOf(Objects.requireNonNull(roots, "roots")), InputSpacePartitionNode::name);
        for (InputSpacePartitionNode node : this.roots) initialize(node, "");
    }

    public void record(String path) {
        if (!counts.containsKey(path))
            throw new IllegalArgumentException("Not a declared leaf: " + path);
        counts.merge(path, 1, Integer::sum);
    }

    public int count(String path) { return counts.getOrDefault(path, 0); }

    public int total(String path) {
        String prefix = path.isEmpty() ? "" : path + ".";
        return counts.entrySet().stream()
                .filter(entry -> entry.getKey().equals(path) || entry.getKey().startsWith(prefix))
                .mapToInt(Map.Entry::getValue).sum();
    }

    public void assertAllLeavesRecorded() {
        List<String> missing = counts.keySet().stream().filter(path -> count(path) == 0).toList();
        assertTrue(missing.isEmpty(), () -> "Input-space partitions were not exercised: " + missing);
    }

    public void printPaths(PrintStream output) { printBranches(output, roots, "", ""); }

    private void initialize(InputSpacePartitionNode node, String parent) {
        String path = qualify(parent, node.name());
        if (node.isLeaf()) counts.put(path, 0);
        for (InputSpacePartitionNode child : node.children()) initialize(child, path);
    }

    private void printBranches(PrintStream output, List<InputSpacePartitionNode> branches,
            String parent, String prefix) {
        for (int index = 0; index < branches.size(); index++) {
            InputSpacePartitionNode node = branches.get(index);
            String path = qualify(parent, node.name());
            boolean last = index == branches.size() - 1;
            output.println(prefix + (last ? "└─" : "├─") + node.name() + ": " + total(path));
            printBranches(output, node.children(), path, prefix + (last ? "  " : "│ "));
        }
    }

    private static String qualify(String parent, String child) {
        return parent.isEmpty() ? child : parent + "." + child;
    }
}
