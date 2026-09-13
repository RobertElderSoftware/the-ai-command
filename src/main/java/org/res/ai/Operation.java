package org.res.ai;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** A parsed model-response operation. */
public final class Operation {
    final OperationType type;
    final String path;
    final String encoding;
    final String data;
    final Long length;
    final String sha256;
    final FilePatch patch;

    public Operation(OperationType type, String path, String encoding, String data,
            Long length, String sha256) {
        this(type, path, encoding, data, length, sha256, null);
    }

    public Operation(OperationType type, String path, String encoding, String data,
            Long length, String sha256, FilePatch patch) {
        this.type = Objects.requireNonNull(type, "type");
        this.path = path;
        this.encoding = encoding;
        this.data = data;
        this.length = length;
        this.sha256 = sha256;
        this.patch = patch;
        if (type == OperationType.FILE_PATCH) {
            Objects.requireNonNull(patch, "patch");
        } else {
            Objects.requireNonNull(encoding, "encoding");
            Objects.requireNonNull(data, "data");
        }
    }

    byte[] decode() {
        if (type == OperationType.FILE_PATCH) {
            throw new IllegalStateException("file_patch has no encoded data payload");
        }
        return "utf-8".equals(encoding)
                ? data.getBytes(StandardCharsets.UTF_8)
                : Base64.getDecoder().decode(data.strip());
    }
}
