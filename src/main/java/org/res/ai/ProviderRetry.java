package org.res.ai;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;

/** Retries only the individual provider call; never replays application operations. */
final class ProviderRetry {
    static final Duration BUFFER = Duration.ofSeconds(5);
    @FunctionalInterface interface Sleeper { void sleep(long nanos) throws InterruptedException; }
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final boolean terminal;

    ProviderRetry() {
        this(Clock.systemDefaultZone(), System::nanoTime,
                nanos -> java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos),
                Boolean.parseBoolean(System.getProperty("ai.retry.ansi", "true")));
    }

    ProviderRetry(Clock clock, LongSupplier nanoTime, Sleeper sleeper, boolean terminal) {
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
        this.terminal = terminal;
    }

    /** Null means that timing is missing, stale, ambiguous, or unsuitable for replay. */
    static Duration delay(ProviderLimitException limit, Instant now) {
        if (limit.kind() == ProviderLimitException.Kind.QUOTA_EXHAUSTED) return null;
        try {
            Instant latest = null;
            if (limit.retryAt() != null) {
                if (!limit.retryAt().isAfter(now)) return null;
                latest = limit.retryAt();
            }
            if (limit.retryAfter() != null) {
                if (limit.retryAfter().isZero() || limit.retryAfter().isNegative()) return null;
                Instant relative = now.plus(limit.retryAfter());
                if (latest == null || relative.isAfter(latest)) latest = relative;
            }
            String bucket = null;
            java.util.Set<String> names = new java.util.HashSet<>();
            for (var window : limit.resetWindows()) {
                if (window.bucket() == null || window.bucket().isBlank()
                        || window.window() == null || window.window().isBlank()
                        || !names.add(window.window())) return null;
                if (bucket != null && !bucket.equals(window.bucket())) return null;
                bucket = window.bucket();
                if (!window.resetsAt().isAfter(now)) return null;
                if (latest == null || window.resetsAt().isAfter(latest)) latest = window.resetsAt();
            }
            if (latest == null) return null;
            Duration delay = Duration.between(now, latest).plus(BUFFER);
            delay.toNanos(); // Reject delays that cannot be measured by this controller.
            now.plus(delay);
            return delay;
        } catch (ArithmeticException | java.time.DateTimeException invalid) {
            return null;
        }
    }

    String complete(LLMProvider provider, String prompt, OutputStream output,
            ConversationHistory history, boolean contextCommitted, boolean enabled) throws IOException {
        // One network budget for this exact prompt, including all usage-limit waits.
        NetworkRetry network = new NetworkRetry(clock, nanoTime, sleeper, terminal,
                Duration.ofSeconds(NetworkProbe.seconds("ai.network.retrySeconds", 15)),
                Boolean.parseBoolean(System.getProperty("ai.network.replayUncertain", "true")), true);
        for (long attempt = 1; ; attempt++) {
            checkInterrupted();
            ProviderLimitException failure;
            try {
                return network.complete(provider, prompt, output, history, contextCommitted, enabled);
            } catch (ProviderLimitException limit) {
                if (!enabled || FailureReporting.limit(limit) == null) throw limit;
                failure = limit;
            }
            Instant received = clock.instant();
            long started = nanoTime.getAsLong();
            Duration delay = delay(failure, received);
            if (delay == null) {
                try {
                    write(output, "Automatic retry unavailable: no complete, future reset time is known, "
                            + "or this limit requires billing action. Exiting.\n");
                } catch (IOException | RuntimeException secondary) {
                    failure.addSuppressed(secondary);
                }
                throw failure;
            }
            Instant retryAt = received.plus(delay);
            try {
                if (history != null) history.retryEvent("provider_wait", failure,
                        contextCommitted, attempt, retryAt, true);
                waitUntil(output, failure.backend(), attempt, started, delay, retryAt);
                checkInterrupted();
                if (history != null) history.retryEvent("provider_retry", failure,
                        contextCommitted, attempt, retryAt, true);
                checkInterrupted();
            } catch (InterruptedIOException cancelled) {
                try {
                    write(output, "Retry cancelled.\n");
                    if (history != null) history.retryEvent("provider_retry_cancelled", failure,
                            contextCommitted, attempt, retryAt, false);
                } catch (IOException | RuntimeException secondary) {
                    cancelled.addSuppressed(secondary);
                }
                throw cancelled;
            } catch (IOException | RuntimeException secondary) {
                // Secondary failures must remain visible and must never cause another submission.
                secondary.addSuppressed(failure);
                throw secondary;
            }
        }
    }

    private void waitUntil(OutputStream output, String backend, long attempt, long started,
            Duration delay, Instant retryAt) throws IOException {
        long total = delay.toNanos();
        boolean first = true;
        RetryDisplay display = terminal ? new RetryDisplay(output) : null;
        try {
            while (true) {
                checkInterrupted();
                long elapsed = nanoTime.getAsLong() - started;
                long remaining = elapsed >= total ? 0 : total - Math.max(0, elapsed);
                if (remaining == 0) break;
                if (terminal || first) {
                    long seconds = remaining / 1_000_000_000L
                            + (remaining % 1_000_000_000L == 0 ? 0 : 1);
                    String countdown = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
                            seconds / 3600, seconds / 60 % 60, seconds % 60);
                    String message = backend + " waiting for usage limit reset | retry " + attempt + " | now " + time(clock.instant())
                            + " | retry at " + time(retryAt) + " (includes 5s buffer) | remaining "
                            + (terminal ? "\u001b[33m" : "") + countdown
                            + (terminal ? "\u001b[0m" : "") + " | Ctrl+C to exit";
                    if (display != null) display.update(message);
                    else write(output, message + "\n");
                    first = false;
                }
                try {
                    sleeper.sleep(Math.min(remaining, 1_000_000_000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Provider retry cancelled");
                }
            }
        } finally {
            if (display != null) display.close();
        }
        checkInterrupted();
        write(output, "Reset wait completed; resubmitting the same prompt.\n");
    }

    private String time(Instant instant) {
        return displayTime(instant, clock);
    }

    static String displayTime(Instant instant, Clock clock) {
        return DateTimeFormatter.ofPattern("dd MMM HH:mm:ss", java.util.Locale.ENGLISH)
                .format(instant.atZone(clock.getZone()));
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Provider retry cancelled");
    }

    private void write(OutputStream output, String value) throws IOException {
        output.write(StatusColour.format(value, terminal).getBytes(StandardCharsets.UTF_8));
        output.flush();
        if (output instanceof PrintStream stream && stream.checkError())
            throw new IOException("Could not publish provider retry status");
    }
}
