package org.res.ai;

import java.util.List;
import org.res.ai.InputSpacePartitionTestNode.ExceptionConsumer;

/** Builds the randomized file_patch operation branch. */
public final class FilePatchOperationTestNode {
    private FilePatchOperationTestNode() { }

    public static InputSpacePartitionTestNode<InputSpacePartitionTestContext> create() {
        return InputSpacePartitionTestNode.fixtureChoice("file_patch", List.of(
                leaf("replacement", FilePatchTestFixture::replacement),
                leaf("insertion", FilePatchTestFixture::insertion),
                leaf("deletion", FilePatchTestFixture::deletion),
                leaf("stale_rejected", FilePatchTestFixture::stalePatchIsRejected)));
    }

    private static InputSpacePartitionTestNode<InputSpacePartitionTestContext> leaf(String name, ExceptionConsumer<FilePatchTestFixture> action) {
        return InputSpacePartitionTestNode.fixtureLeaf(name, (context, execution) ->
                action.accept(new FilePatchTestFixture(context)));
    }
}
