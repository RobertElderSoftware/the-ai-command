package org.res.ai;

import java.util.Objects;

/** A transmitted filename and its explicit access policy. */
public final class ContextFile {
    private final String path;
    private final FileAccess access;

    public ContextFile(String path, FileAccess access) {
        this.path = Objects.requireNonNull(path, "path");
        this.access = Objects.requireNonNull(access, "access");
        if (path.isBlank()) throw new IllegalArgumentException("File path must not be blank");
    }

    public String path() { return path; }
    public FileAccess access() { return access; }
}
