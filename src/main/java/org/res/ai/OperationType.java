package org.res.ai;

/** Enumerates the operation codes understood by the application. */
public enum OperationType {
    STDOUT("stdout"),
    FILE_WRITE("file_write");

    private final String opcode;

    OperationType(String opcode) {
        this.opcode = opcode;
    }

    public String opcode() {
        return opcode;
    }

    public static OperationType fromOpcode(String opcode) {
        for (OperationType type : values()) {
            if (type.opcode.equals(opcode)) return type;
        }
        throw new IllegalArgumentException("Unknown op: " + opcode);
    }
}
