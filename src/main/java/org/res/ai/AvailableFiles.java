package org.res.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Permission-filtered discovery. Neither traversal nor file reads follow symbolic links. */
final class AvailableFiles {
    private final Path root;
    private final WorkingDirectoryPaths paths;
    private final RequestContext policy;
    private final List<ContextFile> originals;
    private final Set<String> protectedPaths;
    private final List<JsonObject> entries = new ArrayList<>();
    private final List<JsonObject> errors = new ArrayList<>();
    private boolean complete = true;

    private AvailableFiles(Path root, RequestContext policy, List<ContextFile> originals,
            Set<String> protectedPaths) {
        this.root = WorkingDirectoryPaths.canonical(root);
        this.paths = new WorkingDirectoryPaths(this.root);
        this.policy = policy;
        this.originals = List.copyOf(originals);
        this.protectedPaths = Set.copyOf(protectedPaths);
    }

    static String report(Path root, RequestContext policy, List<ContextFile> originals,
            Set<String> protectedPaths) throws IOException {
        return new AvailableFiles(root, policy, originals, protectedPaths).scan().toString();
    }

    private JsonObject scan() throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (directory.equals(root)) return FileVisitResult.CONTINUE;
                if (directory.startsWith(root.resolve("__ciop__"))) return FileVisitResult.SKIP_SUBTREE;
                if (!safe(directory)) return FileVisitResult.SKIP_SUBTREE;
                inspect(directory, attributes);
                // A denied parent may contain permitted descendants.
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!attributes.isSymbolicLink() && attributes.isRegularFile() && safe(file))
                    inspect(file, attributes);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                failed(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
                if (failure != null) failed(directory);
                return FileVisitResult.CONTINUE;
            }
        });
        Comparator<JsonObject> order = Comparator.comparing(value -> value.get("path").getAsString());
        entries.sort(order);
        errors.sort(order);
        JsonArray files = new JsonArray();
        entries.forEach(files::add);
        JsonArray failures = new JsonArray();
        errors.forEach(failures::add);
        JsonObject report = new JsonObject();
        report.addProperty("complete", complete);
        report.add("entries", files);
        report.add("errors", failures);
        return report;
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
    }

    private boolean safe(Path file) {
        if (file.startsWith(root.resolve("__ciop__"))) return false;
        try {
            return paths.resolve(relative(file)).equals(file);
        } catch (IllegalArgumentException failure) {
            complete = false;
            return false;
        }
    }

    private FileAccess permission(Path file) throws IOException {
        String name = relative(file);
        FileAccess access = null;
        if (policy.hasPermissions()) {
            access = policy.accessFor(root, name);
        } else {
            // Without configuration, discovery cannot expand the explicit input selection.
            for (ContextFile original : originals) {
                if (WorkingDirectoryPaths.sameFile(file, paths.resolve(original.path()))) {
                    access = original.access();
                    break;
                }
            }
        }
        if (access == null) return null;
        for (String nameToProtect : protectedPaths) {
            Path protectedFile = paths.resolve(nameToProtect);
            if (file.startsWith(protectedFile) || protectedFile.startsWith(file)
                    || WorkingDirectoryPaths.sameFile(file, protectedFile)) return FileAccess.READ;
        }
        return access;
    }

    private void inspect(Path file, BasicFileAttributes visited) {
        try {
            FileAccess access = permission(file);
            if (access == null) return;
            Path checked = paths.resolve(relative(file));
            BasicFileAttributes before = Files.readAttributes(checked,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.isSymbolicLink() || (!before.isDirectory() && !before.isRegularFile())) return;
            if (before.isDirectory() != visited.isDirectory()) throw new IOException("Entry changed");
            JsonObject entry = new JsonObject();
            entry.addProperty("path", relative(file));
            entry.addProperty("type", before.isDirectory() ? "directory" : "file");
            entry.addProperty("access", access.name());
            if (before.isRegularFile()) {
                entry.addProperty("size_bytes", before.size());
                Long count = textLines(checked);
                if (count != null) entry.addProperty("line_count", count);
            }
            paths.resolve(relative(file));
            BasicFileAttributes after = Files.readAttributes(checked,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!Objects.equals(before.fileKey(), after.fileKey())
                    || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || before.isDirectory() != after.isDirectory()
                    || before.isRegularFile() != after.isRegularFile())
                throw new IOException("Entry changed during inspection");
            entries.add(entry);
        } catch (IOException | IllegalArgumentException failure) {
            failed(file);
        }
    }

    private void failed(Path file) {
        complete = false;
        if (file.equals(root) || !safe(file)) return;
        try {
            if (permission(file) == null) return;
            JsonObject error = new JsonObject();
            error.addProperty("path", relative(file));
            error.addProperty("error", "Entry could not be inspected or changed during enumeration");
            errors.add(error);
        } catch (IOException | IllegalArgumentException ignored) {
            // Do not disclose a path whose permission or containment cannot be established.
        }
    }

    /** Strict UTF-8 with the same printable-control policy used by CIOP text payloads. */
    private static Long textLines(Path file) throws IOException {
        long lines = 0;
        int last = -1;
        try (var reader = new InputStreamReader(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS),
                StandardCharsets.UTF_8.newDecoder())) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                for (int index = 0; index < count; index++) {
                    char ch = buffer[index];
                    if (Character.isISOControl(ch) && ch != '\t' && ch != '\r' && ch != '\n') return null;
                    if (ch == '\n') lines++;
                    last = ch;
                }
            }
        } catch (CharacterCodingException binary) {
            return null;
        }
        return lines + (last != -1 && last != '\n' ? 1 : 0);
    }
}
