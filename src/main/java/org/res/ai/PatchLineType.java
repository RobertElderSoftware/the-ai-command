package org.res.ai;

/** A line's role within a file_patch hunk. */
public enum PatchLineType {
    CONTEXT("context"),
    REMOVE("remove"),
    ADD("add");

    private final String token;

    PatchLineType(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static PatchLineType fromToken(String token) {
        for (PatchLineType type : values()) {
            if (type.token.equals(token)) return type;
        }
        throw new IllegalArgumentException("Unknown patch line type: " + token);
    }
}
