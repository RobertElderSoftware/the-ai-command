package org.res.ai;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Supported CIOP payload encodings. */
public enum Encoding {
    UTF_8("utf-8"),
    BASE64("base64");

    private final String token;

    Encoding(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public String encode(byte[] data) {
        return this == UTF_8
                ? new String(data, StandardCharsets.UTF_8)
                : Base64.getEncoder().encodeToString(data);
    }

    public static Encoding fromToken(String token) {
        for (Encoding encoding : values()) {
            if (encoding.token.equals(token)) return encoding;
        }
        throw new IllegalArgumentException("Unsupported encoding: " + token);
    }
}
