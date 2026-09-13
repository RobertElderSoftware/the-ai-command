package org.res.ai;

import java.util.Objects;

/** One context, removal, or addition line in a patch hunk. */
public final class PatchLine {
    private final PatchLineType type;
    private final String text;

    public PatchLine(PatchLineType type, String text) {
        this.type = Objects.requireNonNull(type, "type");
        this.text = Objects.requireNonNull(text, "text");
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Patch line text must exclude line terminators");
        }
    }

    public PatchLineType type() {
        return type;
    }

    public String text() {
        return text;
    }
}
