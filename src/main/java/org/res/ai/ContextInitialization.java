package org.res.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Explicit CLI initialization, before history registration and context loading. */
final class ContextInitialization {
    private ContextInitialization() { }

    static void prepare(Path directory, Map<String, String> options) throws IOException {
        if (!options.containsKey("--enable-context")
                || options.containsKey("--ignore-context") || options.containsKey("--help")) return;
        WorkingDirectoryPaths paths = new WorkingDirectoryPaths(directory);
        String filename = options.get("--context");
        ContextFile entry = new ContextFile(filename, FileAccess.READ_WRITE);
        Path target = paths.contextFile(entry);
        if (target.startsWith(paths.resolve(ConversationHistory.DIRECTORY)))
            throw new IllegalArgumentException("Context configuration must be outside "
                    + ConversationHistory.DIRECTORY);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        Files.createDirectories(target.getParent());
        paths.contextFile(entry);
        try {
            Files.writeString(target, "{\"contexts\":[],\"permissions\":[]}\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        } catch (FileAlreadyExistsException concurrentCreation) {
            // Never replace a configuration created by another invocation.
            paths.contextFile(new ContextFile(filename, FileAccess.READ));
        }
    }
}
