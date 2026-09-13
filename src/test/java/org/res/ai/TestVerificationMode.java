package org.res.ai;

import com.google.gson.JsonObject;

/** Optional verification fields included in a generated operation. */
public enum TestVerificationMode {
    NONE,
    LENGTH_ONLY,
    SHA256_ONLY,
    LENGTH_AND_SHA256;

    public String partitionName() {
        return name().toLowerCase();
    }

    public void apply(JsonObject operation, byte[] data) throws Exception {
        if (this == LENGTH_ONLY || this == LENGTH_AND_SHA256) {
            operation.addProperty("length", data.length);
        }
        if (this == SHA256_ONLY || this == LENGTH_AND_SHA256) {
            operation.addProperty("sha256", AICommandApplication.sha256(data));
        }
    }
}
