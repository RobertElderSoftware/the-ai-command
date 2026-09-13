package org.res.ai;

import java.util.List;

/** Ordered independent spaces, without sampling policy or execution state. */
public final class InputSpacePartitionTestRegistry {
    private final List<InputSpacePartitionTestSpace<?>> spaces;

    public InputSpacePartitionTestRegistry(List<InputSpacePartitionTestSpace<?>> spaces) {
        this.spaces = InputSpacePartitionNode.validateChildren(spaces, InputSpacePartitionTestSpace::name);
        if (this.spaces.isEmpty()) throw new IllegalArgumentException("No test spaces registered");
    }

    public static InputSpacePartitionTestRegistry applicationSpaces(java.nio.file.Path parent) {
        InputSpacePartitionTestTreeFactory operations = new InputSpacePartitionTestTreeFactory();
        return new InputSpacePartitionTestRegistry(List.of(
                operations.operationSpace(parent), operations.containmentSpace(parent),
                BatchTestFixture.space(parent), new RequestContextTest().space(parent),
                AICommandApplicationTest.loggingSpace(parent),
                AICommandApplicationTest.patchRegressionSpace(parent)));
    }

    public List<InputSpacePartitionTestSpace<?>> spaces() { return spaces; }
}
