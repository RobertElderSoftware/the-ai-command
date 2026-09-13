package org.res.ai;

import java.util.List;
import java.util.Objects;

/** A validated line-oriented file_patch hunk. */
public final class PatchHunk {
    private final int oldStart;
    private final int oldCount;
    private final int newStart;
    private final int newCount;
    private final List<PatchLine> lines;

    public PatchHunk(int oldStart, int oldCount, int newStart, int newCount,
            List<PatchLine> lines) {
        if (oldStart < 1 || newStart < 1 || oldCount < 0 || newCount < 0) {
            throw new IllegalArgumentException("Invalid patch hunk range");
        }
        this.oldStart = oldStart;
        this.oldCount = oldCount;
        this.newStart = newStart;
        this.newCount = newCount;
        this.lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        int oldLines = 0;
        int newLines = 0;
        for (PatchLine line : this.lines) {
            if (line.type() != PatchLineType.ADD) oldLines++;
            if (line.type() != PatchLineType.REMOVE) newLines++;
        }
        if (oldLines != oldCount || newLines != newCount) {
            throw new IllegalArgumentException("Patch hunk counts do not match its lines");
        }
    }

    public int oldStart() { return oldStart; }
    public int oldCount() { return oldCount; }
    public int newStart() { return newStart; }
    public int newCount() { return newCount; }
    public List<PatchLine> lines() { return lines; }
}
