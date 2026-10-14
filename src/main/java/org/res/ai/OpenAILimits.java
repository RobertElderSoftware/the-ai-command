package org.res.ai;

import com.openai.errors.OpenAIServiceException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** SDK errors are classified by status and exact codes, not exception-message searches. */
final class OpenAILimits {
    private OpenAILimits() { }

    static RuntimeException normalize(OpenAIServiceException failure) {
        if (failure.getSuppressed().length != 0) return failure;
        ProviderLimitException.Kind kind = kind(failure.statusCode(), failure.code().orElse(null),
                failure.type().orElse(null));
        if (kind == null) return failure;
        Instant retryAt = null;
        Duration retryAfter = null;
        List<String> headers = failure.headers().values("retry-after");
        if (headers.size() == 1) {
            String header = headers.get(0).strip();
            try {
                if (header.matches("[0-9]{1,10}")) retryAfter = Duration.ofSeconds(Long.parseLong(header));
                else retryAt = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (RuntimeException ignored) { /* An invalid retry header is unavailable metadata. */ }
        }
        String code = failure.code().orElse(failure.type().orElse(null));
        return new ProviderLimitException("openai", kind, code, retryAt, retryAfter, null);
    }

    static ProviderLimitException.Kind kind(int status, String code, String type) {
        if (status != 429) return null;
        if ("insufficient_quota".equals(code) || "insufficient_quota".equals(type))
            return ProviderLimitException.Kind.QUOTA_EXHAUSTED;
        if ("rate_limit_exceeded".equals(code) || (code == null && "rate_limit_exceeded".equals(type)))
            return ProviderLimitException.Kind.RATE_LIMIT;
        return null; // An otherwise unexplained 429 retains the complete SDK exception.
    }
}
