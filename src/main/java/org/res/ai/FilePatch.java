package org.res.ai;

import java.util.List;
import java.util.Objects;

/** Data specific to a UTF-8 line-oriented file_patch operation. */
public final class FilePatch {
    private final String path;
    private final String sourceSha256;
    private final String resultSha256;
    private final Boolean finalNewline;
    private final List<PatchHunk> hunks;

    public FilePatch(String path, String sourceSha256, String resultSha256,
            Boolean finalNewline, List<PatchHunk> hunks) {
        this.path = Objects.requireNonNull(path, "path");
        this.sourceSha256 = Objects.requireNonNull(sourceSha256, "sourceSha256");
        this.resultSha256 = resultSha256;
        this.finalNewline = finalNewline;
        this.hunks = List.copyOf(Objects.requireNonNull(hunks, "hunks"));
        if (this.hunks.isEmpty()) {
            throw new IllegalArgumentException("file_patch requires at least one hunk");
        }
    }

    public String path() { return path; }
    public String sourceSha256() { return sourceSha256; }
    public String resultSha256() { return resultSha256; }
    public Boolean finalNewline() { return finalNewline; }
    public List<PatchHunk> hunks() { return hunks; }
}
