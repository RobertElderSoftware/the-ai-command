package org.res.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Provider-neutral limit metadata. Timing information never authorizes replay. */
public final class ProviderLimitException extends RuntimeException {
    public enum Kind { USAGE_LIMIT, RATE_LIMIT, QUOTA_EXHAUSTED }
    public record ResetWindow(String bucket, String window, Instant resetsAt) {
        public ResetWindow {
            bucket = safe(bucket, 80);
            window = safe(window, 40);
            Objects.requireNonNull(resetsAt);
        }
    }
    private final String backend;
    private final Kind kind;
    private final String providerCode;
    private final Instant retryAt;
    private final Duration retryAfter;
    private final String resetHint;
    private final List<ResetWindow> resetWindows;

    public ProviderLimitException(String backend, Kind kind, String providerCode,
            Instant retryAt, Duration retryAfter, String resetHint) {
        this(backend, kind, providerCode, retryAt, retryAfter, resetHint, List.of());
    }

    public ProviderLimitException(String backend, Kind kind, String providerCode,
            Instant retryAt, Duration retryAfter, String resetHint, List<ResetWindow> windows) {
        super(safe(backend, 40) + " " + Objects.requireNonNull(kind).name()
                .toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + " reached.");
        this.backend = safe(Objects.requireNonNull(backend), 40);
        this.kind = kind;
        this.providerCode = safe(providerCode, 80);
        this.retryAt = retryAt;
        if (retryAfter != null && retryAfter.isNegative())
            throw new IllegalArgumentException("Negative retry delay");
        this.retryAfter = retryAfter;
        this.resetHint = safe(resetHint, 120);
        this.resetWindows = List.copyOf(windows);
        if (resetWindows.size() > 2) throw new IllegalArgumentException("At most two reset windows");
    }

    public String backend() { return backend; }
    public Kind kind() { return kind; }
    public String providerCode() { return providerCode; }
    public Instant retryAt() { return retryAt; }
    public Duration retryAfter() { return retryAfter; }
    public String resetHint() { return resetHint; }
    public List<ResetWindow> resetWindows() { return resetWindows; }

    public ProviderLimitException withWindows(List<ResetWindow> windows) {
        return new ProviderLimitException(backend, kind, providerCode, retryAt, retryAfter, resetHint, windows);
    }

    public String display() {
        StringBuilder text = new StringBuilder(getMessage());
        if (retryAt != null) text.append(" Provider retry time: ").append(retryAt).append('.');
        else if (retryAfter != null) text.append(" Provider retry delay: ").append(retryAfter).append('.');
        else if (resetHint != null) text.append(" Provider suggests trying again at ").append(resetHint)
                .append(" (date/timezone unspecified).");
        for (ResetWindow window : resetWindows)
            text.append(" ").append(window.bucket()).append('/').append(window.window())
                    .append(" resets at ").append(window.resetsAt()).append('.');
        return text.toString();
    }

    JsonObject history(boolean contextCommitted) {
        JsonObject result = new JsonObject();
        result.addProperty("backend", backend);
        result.addProperty("kind", kind.name().toLowerCase(java.util.Locale.ROOT));
        if (providerCode != null) result.addProperty("providerCode", providerCode);
        if (retryAt != null) result.addProperty("retryAt", retryAt.toString());
        if (retryAfter != null) result.addProperty("retryAfter", retryAfter.toString());
        if (resetHint != null) result.addProperty("resetHint", resetHint);
        if (!resetWindows.isEmpty()) {
            JsonArray windows = new JsonArray();
            for (ResetWindow window : resetWindows) {
                JsonObject entry = new JsonObject();
                entry.addProperty("bucket", window.bucket());
                entry.addProperty("window", window.window());
                entry.addProperty("resetsAt", window.resetsAt().toString());
                windows.add(entry);
            }
            result.add("resetWindows", windows);
        }
        result.addProperty("contextCommitted", contextCommitted);
        result.addProperty("automaticReplayAuthorized", false);
        return result;
    }

    /** ASCII-only bounded metadata cannot inject terminal controls or history headers. */
    static String safe(String value, int maximum) {
        if (value == null) return null;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length() && result.length() < maximum; i++) {
            char c = value.charAt(i);
            result.append(c >= 32 && c <= 126 ? c : ' ');
        }
        return result.toString().strip();
    }
}
