package org.res.ai;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** A decoded model-response operation and its optional verification metadata. */
public final class Operation {
    final OperationType type;
    final String path;
    final String encoding;
    final String data;
    final Long length;
    final String sha256;

    public Operation(OperationType type, String path, String encoding, String data,
                     Long length, String sha256) {
        this.type = Objects.requireNonNull(type, "type");
        this.path = path;
        this.encoding = Objects.requireNonNull(encoding, "encoding");
        this.data = Objects.requireNonNull(data, "data");
        this.length = length;
        this.sha256 = sha256;
    }

    byte[] decode() {
        return "utf-8".equals(encoding)
                ? data.getBytes(StandardCharsets.UTF_8)
                : Base64.getDecoder().decode(data.strip());
    }
}
