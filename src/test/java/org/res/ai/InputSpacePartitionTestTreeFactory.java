package org.res.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Builds the executable operation and containment partition trees. */
public final class InputSpacePartitionTestTreeFactory {
    private final InputSpacePartitionTestNode operation;
    private final InputSpacePartitionTestNode containment;

    public InputSpacePartitionTestTreeFactory() {
        operation = InputSpacePartitionTestNode.choice("operation",
                InputSpacePartitionTestNode.sequence("stdout", context -> { },
                        encoding(context -> context.testStdout())),
                InputSpacePartitionTestNode.sequence("file_write", context -> { },
                        encoding(context -> context.testFileWrite(),
                                target(pathDepth(verification(
                                        context -> context.testFileWrite()))))));

        containment = InputSpacePartitionTestNode.sequence("containment",
                context -> { },
                InputSpacePartitionTestNode.leaf(
                        "input_traversal_rejected", context -> {
                            ContainmentTestFixture fixture = context.require(
                                    ContainmentTestFixture.KEY);
                            int index = 0;
                            for (String path : fixture.traversalPaths()) {
                                if (index++ > 0) context.coverage().record(
                                        "containment.input_traversal_rejected");
                                context.assertInputRejected(
                                        fixture.workingDirectory(), path);
                            }
                        }),
                InputSpacePartitionTestNode.leaf(
                        "output_traversal_rejected", context -> {
                            ContainmentTestFixture fixture = context.require(
                                    ContainmentTestFixture.KEY);
                            int index = 0;
                            for (String path : fixture.traversalPaths()) {
                                if (index++ > 0) context.coverage().record(
                                        "containment.output_traversal_rejected");
                                context.assertWriteRejected(
                                        fixture.workingDirectory(), path,
                                        Encoding.UTF_8);
                            }
                            fixture.assertOutsideUnchanged();
                        }),
                InputSpacePartitionTestNode.leaf(
                        "symlink_input_rejected", context -> {
                            ContainmentTestFixture fixture = context.require(
                                    ContainmentTestFixture.KEY);
                            context.assertInputRejected(fixture.workingDirectory(),
                                    fixture.existingSymlinkPath());
                        }),
                InputSpacePartitionTestNode.leaf(
                        "symlink_existing_target_rejected", context -> {
                            ContainmentTestFixture fixture = context.require(
                                    ContainmentTestFixture.KEY);
                            context.assertWriteRejected(fixture.workingDirectory(),
                                    fixture.existingSymlinkPath(), Encoding.UTF_8);
                            fixture.assertOutsideUnchanged();
                        }),
                InputSpacePartitionTestNode.leaf(
                        "symlink_missing_target_rejected", context -> {
                            ContainmentTestFixture fixture = context.require(
                                    ContainmentTestFixture.KEY);
                            context.assertWriteRejected(fixture.workingDirectory(),
                                    fixture.missingSymlinkPath(), Encoding.BASE64);
                            fixture.assertMissingTargetUncreated();
                        }));
    }

    public List<InputSpacePartitionNode> partitionRoots() {
        return List.of(operation.toPartitionNode(), containment.toPartitionNode());
    }

    public OperationType executeRandomOperation(
            InputSpacePartitionTestContext context) throws Exception {
        operation.execute(context);
        return context.require(InputSpacePartitionTestContext.OPERATION);
    }

    public void executeContainmentTests(
            InputSpacePartitionTestContext context) throws Exception {
        context.put(InputSpacePartitionTestContext.VERIFICATION,
                TestVerificationMode.NONE);
        context.put(ContainmentTestFixture.KEY,
                new ContainmentTestFixture(context));
        containment.execute(context);
    }

    private static InputSpacePartitionTestNode encoding(
            InputSpacePartitionTestNode.Action terminal) {
        return encoding(terminal, null);
    }

    private static InputSpacePartitionTestNode encoding(
            InputSpacePartitionTestNode.Action terminal,
            InputSpacePartitionTestNode continuation) {
        return nestedChoice("encoding", InputSpacePartitionTestContext.ENCODING,
                enumChoices(Encoding.values(), Encoding::token), value ->
                        continuation == null ? verification(terminal) : continuation);
    }

    private static InputSpacePartitionTestNode target(
            InputSpacePartitionTestNode continuation) {
        return nestedChoice("target",
                InputSpacePartitionTestContext.TARGET_EXISTS,
                ordered("existing", true, "missing", false),
                value -> continuation);
    }

    private static InputSpacePartitionTestNode pathDepth(
            InputSpacePartitionTestNode continuation) {
        return nestedChoice("path_depth", InputSpacePartitionTestContext.PATH_DEPTH,
                ordered("0", 0, "1", 1, "2", 2), value -> continuation);
    }

    private static InputSpacePartitionTestNode verification(
            InputSpacePartitionTestNode.Action terminal) {
        Map<String, TestVerificationMode> choices = enumChoices(
                TestVerificationMode.values(), TestVerificationMode::partitionName);
        return InputSpacePartitionTestNode.choice("verification",
                choices.entrySet().stream().map(entry ->
                        InputSpacePartitionTestNode.leaf(entry.getKey(), context -> {
                            context.put(InputSpacePartitionTestContext.VERIFICATION,
                                    entry.getValue());
                            terminal.execute(context);
                        })).toArray(InputSpacePartitionTestNode[]::new));
    }

    private static <T> InputSpacePartitionTestNode nestedChoice(String name,
            InputSpacePartitionStateKey<T> key, Map<String, T> choices,
            Function<T, InputSpacePartitionTestNode> continuation) {
        return InputSpacePartitionTestNode.choice(name,
                choices.entrySet().stream().map(entry ->
                        InputSpacePartitionTestNode.sequence(entry.getKey(),
                                context -> context.put(key, entry.getValue()),
                                continuation.apply(entry.getValue())))
                        .toArray(InputSpacePartitionTestNode[]::new));
    }

    private interface Name<T> {
        String get(T value);
    }

    private static <T> Map<String, T> enumChoices(T[] values, Name<T> naming) {
        Map<String, T> choices = new LinkedHashMap<>();
        for (T value : values) choices.put(naming.get(value), value);
        return choices;
    }

    private static <T> Map<String, T> ordered(
            String firstName, T first, String secondName, T second) {
        Map<String, T> result = new LinkedHashMap<>();
        result.put(firstName, first);
        result.put(secondName, second);
        return result;
    }

    private static <T> Map<String, T> ordered(String firstName, T first,
            String secondName, T second, String thirdName, T third) {
        Map<String, T> result = ordered(firstName, first, secondName, second);
        result.put(thirdName, third);
        return result;
    }
}
