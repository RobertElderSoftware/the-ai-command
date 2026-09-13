package org.res.ai;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.res.ai.InputSpacePartitionTestNode.FixtureAction;

import static org.res.ai.InputSpacePartitionTestContext.*;
import static org.res.ai.InputSpacePartitionTestNode.*;

/** Builds the executable operation and containment partition trees. */
public final class InputSpacePartitionTestTreeFactory {
    @FunctionalInterface
    public interface PathCheck {
        void execute(InputSpacePartitionTestContext context,
                ContainmentTestFixture fixture, String path) throws Exception;
    }

    private final InputSpacePartitionTestNode<InputSpacePartitionTestContext> operation;
    private final List<InputSpacePartitionTestNode<InputSpacePartitionTestContext>> containment;

    public InputSpacePartitionTestTreeFactory() {
        operation = fixtureChoice("operation", List.of(
                fixtureSequence("stdout", (context, execution) -> { }, List.of(
                        stateChoice("encoding", ENCODING, List.of(Encoding.values()), Encoding::token,
                                verification((context, execution) -> context.testStdout())))),
                fixtureSequence("file_write", (context, execution) -> { }, List.of(
                        stateChoice("encoding", ENCODING, List.of(Encoding.values()), Encoding::token,
                                stateChoice("target", TARGET_EXISTS, List.of(true, false),
                                        exists -> exists ? "existing" : "missing",
                                        stateChoice("path_depth", PATH_DEPTH, List.of(0, 1, 2), Object::toString,
                                                verification((context, execution) -> context.testFileWrite())))))),
                FilePatchOperationTestNode.create()));

        PathCheck input = (context, fixture, path) ->
                context.assertInputRejected(fixture.workingDirectory(), path);
        PathCheck utf8 = (context, fixture, path) ->
                context.assertWriteRejected(fixture.workingDirectory(), path, Encoding.UTF_8);
        containment = List.of(
                containment("input_traversal_rejected", ContainmentTestFixture::traversalPaths, input),
                containment("output_traversal_rejected", ContainmentTestFixture::traversalPaths, utf8),
                containment("symlink_input_rejected", fixture -> List.of(fixture.existingSymlinkPath()), input),
                containment("symlink_existing_target_rejected", fixture -> List.of(fixture.existingSymlinkPath()), utf8),
                containment("symlink_missing_target_rejected", fixture -> List.of(fixture.missingSymlinkPath()),
                        (context, fixture, path) -> context.assertWriteRejected(
                                fixture.workingDirectory(), path, Encoding.BASE64)));
    }

    private static InputSpacePartitionTestNode<InputSpacePartitionTestContext> containment(String name,
            Function<ContainmentTestFixture, List<String>> paths, PathCheck check) {
        return fixtureLeaf(name, (context, execution) -> {
            ContainmentTestFixture fixture = new ContainmentTestFixture(context);
            for (String path : paths.apply(fixture)) {
                check.execute(context, fixture, path);
                fixture.assertOutsideUnchanged();
                fixture.assertMissingTargetUncreated();
            }
        });
    }

    public InputSpacePartitionTestSpace<InputSpacePartitionTestContext> operationSpace(Path parent) {
        org.junit.jupiter.api.Assertions.assertEquals(List.of(OperationType.values()).stream()
                        .map(OperationType::opcode).toList(),
                operation.children().stream().map(InputSpacePartitionTestNode::name).toList());
        return space("operations", parent, List.of(operation));
    }

    public InputSpacePartitionTestSpace<InputSpacePartitionTestContext> containmentSpace(Path parent) {
        return space("containment", parent, containment);
    }

    private static InputSpacePartitionTestSpace<InputSpacePartitionTestContext> space(String name,
            Path parent, List<InputSpacePartitionTestNode<InputSpacePartitionTestContext>> roots) {
        return InputSpacePartitionTestSpace.inDirectory(name, parent, roots,
                InputSpacePartitionTestContext::new, context -> context.resolve("."));
    }

    private static InputSpacePartitionTestNode<InputSpacePartitionTestContext> verification(
            FixtureAction<InputSpacePartitionTestContext> terminal) {
        return fixtureValues("verification", List.of(TestVerificationMode.values()),
                TestVerificationMode::partitionName, value -> (context, execution) -> {
                    context.put(VERIFICATION, value);
                    terminal.execute(context, execution);
                }, List.of());
    }

    /** Typed operation state remains local to this factory; value branching is generic. */
    private static <T> InputSpacePartitionTestNode<InputSpacePartitionTestContext> stateChoice(
            String name, InputSpacePartitionStateKey<T> key, List<T> values, Function<T, String> naming,
            InputSpacePartitionTestNode<InputSpacePartitionTestContext> continuation) {
        return fixtureValues(name, values, naming,
                value -> (context, execution) -> context.put(key, value), List.of(continuation));
    }
}
