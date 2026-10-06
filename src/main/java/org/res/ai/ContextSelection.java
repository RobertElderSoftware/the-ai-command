package org.res.ai;

import com.google.gson.JsonArray;

import java.util.List;
import java.util.Objects;

/** Immutable selection coordinates, independent of file access permissions. */
public record ContextSelection(Kind kind, List<Range> ranges) {
    public enum Kind { WHOLE, LINES, BYTES }

    /** Lines use first/last; bytes use offset/count. */
    public record Range(long first, long second) { }

    public static final ContextSelection WHOLE = new ContextSelection(Kind.WHOLE, List.of());

    public ContextSelection {
        Objects.requireNonNull(kind, "kind");
        ranges = List.copyOf(Objects.requireNonNull(ranges, "ranges"));
        if (kind == Kind.WHOLE) {
            if (!ranges.isEmpty()) throw new IllegalArgumentException("Whole-file selection cannot have ranges");
        } else {
            if (ranges.isEmpty()) throw new IllegalArgumentException("Subset requires nonempty ranges");
            long previousEnd = -1;
            for (Range range : ranges) {
                String description = kind + " range [" + range.first() + "," + range.second() + "]";
                if (kind == Kind.LINES) {
                    if (range.first() < 1 || range.second() < range.first()
                            || range.first() <= previousEnd)
                        throw new IllegalArgumentException("Invalid or overlapping " + description);
                    previousEnd = range.second();
                } else {
                    if (range.first() < 0 || range.second() <= 0 || range.first() < previousEnd)
                        throw new IllegalArgumentException("Invalid or overlapping " + description);
                    try {
                        previousEnd = Math.addExact(range.first(), range.second());
                    } catch (ArithmeticException exception) {
                        throw new IllegalArgumentException("Overflowing " + description, exception);
                    }
                }
            }
        }
    }

    public boolean isWhole() { return kind == Kind.WHOLE; }

    JsonArray jsonRanges() {
        JsonArray result = new JsonArray();
        for (Range range : ranges) {
            JsonArray pair = new JsonArray();
            pair.add(range.first());
            pair.add(range.second());
            result.add(pair);
        }
        return result;
    }
}
