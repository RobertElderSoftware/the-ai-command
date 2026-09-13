package org.res.ai;

import java.io.IOException;

/** Loads the authoritative protocol resource independently of the working directory. */
public final class ProtocolDocument {
    public static final String PATH = "__ciop__/CIOP_PROTOCOL_INSTRUCTIONS.txt";

    private ProtocolDocument() { }

    public static byte[] bytes() throws IOException {
        try (var input = ProtocolDocument.class.getResourceAsStream("/specification.txt")) {
            if (input == null) throw new IOException("Missing embedded CIOP resource: specification.txt");
            return input.readAllBytes();
        }
    }
}
