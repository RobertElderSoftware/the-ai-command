package org.res.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** Shared working-directory containment, identity, and no-symlink checks. */
public final class WorkingDirectoryPaths {
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private final Path directory;

    public WorkingDirectoryPaths(Path directory) {
        this.directory = canonical(Objects.requireNonNull(directory, "directory"));
    }

    public Path resolve(String path) { return resolve(directory, path); }

    public Path contextFile(ContextFile entry) {
        Path file = resolve(entry.path());
        rejectReserved(directory, file);
        if (Files.notExists(file, NO_FOLLOW)) {
            if (entry.access() != FileAccess.READ_WRITE)
                throw new IllegalArgumentException("READ file does not exist: " + entry.path());
        } else if (!Files.isRegularFile(file, NO_FOLLOW)) {
            throw new IllegalArgumentException("Not a regular file: " + entry.path());
        }
        return file;
    }

    static Path canonical(Path directory) {
        try {
            Path canonical = directory.toRealPath();
            if (!Files.isDirectory(canonical, NO_FOLLOW))
                throw new IllegalArgumentException("Working directory is not a directory: " + directory);
            return canonical;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot resolve working directory: " + directory, exception);
        }
    }

    static Path resolve(Path directory, String pathText) {
        Objects.requireNonNull(pathText, "pathText");
        if (pathText.isBlank() || pathText.contains("---")
                || pathText.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid file path: " + pathText);
        Path supplied = Path.of(pathText);
        if (supplied.isAbsolute())
            throw new IllegalArgumentException("Absolute paths are not allowed: " + pathText);
        for (Path component : supplied) {
            if ("..".equals(component.toString()))
                throw new IllegalArgumentException("Parent path components are not allowed: " + pathText);
        }
        Path resolved = directory.resolve(supplied).normalize();
        if (!resolved.startsWith(directory))
            throw new IllegalArgumentException("Path escapes working directory: " + pathText);
        Path current = directory;
        for (Path component : directory.relativize(resolved)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current))
                throw new IllegalArgumentException("Symbolic links are not allowed: " + pathText);
            if (Files.exists(current, NO_FOLLOW)) {
                try {
                    if (!current.toRealPath().startsWith(directory))
                        throw new IllegalArgumentException("Path escapes working directory: " + pathText);
                    if (!current.equals(resolved) && !Files.isDirectory(current, NO_FOLLOW))
                        throw new IllegalArgumentException("Not a directory in path: " + pathText);
                } catch (IOException exception) {
                    throw new IllegalArgumentException("Cannot validate path: " + pathText, exception);
                }
            }
        }
        return resolved;
    }

    static void rejectReserved(Path directory, Path file) {
        if (file.startsWith(directory.resolve("__ciop__")))
            throw new IllegalArgumentException("Reserved protocol namespace: " + file);
    }

    static boolean sameFile(Path first, Path second) throws IOException {
        return first.equals(second) || (Files.exists(first, NO_FOLLOW)
                && Files.exists(second, NO_FOLLOW) && Files.isSameFile(first, second));
    }

    static byte[] read(Path file) throws IOException {
        if (!Files.isRegularFile(file, NO_FOLLOW))
            throw new IllegalArgumentException("Not a readable regular file: " + file);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return input.readAllBytes();
        }
    }
}
