package org.res.ai;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;

/** Shared replacement mechanics; callers retain destination validation. */
final class AtomicFileReplacement {
    @FunctionalInterface
    interface Validation {
        void validate() throws IOException;
    }

    private AtomicFileReplacement() { }

    /** Validate after writing, then preserve permissions and replace the destination. */
    static void replace(Path target, byte[] bytes, String temporaryPrefix,
            boolean forceToStorage, Validation validation) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), temporaryPrefix, ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                if (forceToStorage) channel.force(true);
            }
            validation.validate();
            var permissions = Files.getFileAttributeView(target,
                    PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (permissions != null)
                Files.setPosixFilePermissions(temporary, permissions.readAttributes().permissions());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
