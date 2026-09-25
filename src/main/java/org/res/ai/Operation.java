package org.res.ai;

import com.google.gson.JsonObject;
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
    final JsonObject binaryPatch;

    static Operation deletion(String path) {
        return new Operation(OperationType.FILE_DELETE, path, null, null, null, null);
    }

    static Operation textPatch(FilePatch patch) {
        return new Operation(OperationType.FILE_PATCH, patch.path(), null, null, null, null, patch);
    }

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
        this.binaryPatch = null;
        if (type == OperationType.FILE_PATCH_BINARY) {
            throw new IllegalArgumentException("Use the binary patch constructor");
        } else if (type == OperationType.FILE_PATCH) {
            Objects.requireNonNull(patch, "patch");
        } else if (type == OperationType.FILE_DELETE) {
            Objects.requireNonNull(path, "path");
        } else {
            Objects.requireNonNull(encoding, "encoding");
            Objects.requireNonNull(data, "data");
        }
    }

    Operation(String path, JsonObject binaryPatch) {
        this.type = OperationType.FILE_PATCH_BINARY;
        this.path = Objects.requireNonNull(path, "path");
        this.binaryPatch = Objects.requireNonNull(binaryPatch, "binaryPatch").deepCopy();
        this.encoding = null;
        this.data = null;
        this.length = null;
        this.sha256 = null;
        this.patch = null;
    }

    byte[] decode() {
        if (type != OperationType.STDOUT && type != OperationType.FILE_WRITE) {
            throw new IllegalStateException(type.opcode() + " has no encoded data payload");
        }
        byte[] bytes = "utf-8".equals(encoding)
                ? data.getBytes(StandardCharsets.UTF_8)
                : Base64.getDecoder().decode(data.strip());
        if (length != null && length != bytes.length)
            throw new IllegalArgumentException("Output length verification failed");
        if (sha256 != null && !AICommandApplication.sha256(bytes).equalsIgnoreCase(sha256))
            throw new IllegalArgumentException("Output sha256 verification failed");
        return bytes;
    }
}
