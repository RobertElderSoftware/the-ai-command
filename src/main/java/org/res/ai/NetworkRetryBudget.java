package org.res.ai;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/** Mutable budget shared across usage-limit waits for one retained prompt. */
final class NetworkRetryBudget {
    private final int limit;
    private final long baseSeconds;
    private final DoubleSupplier jitter;
    private int used;

    NetworkRetryBudget(int limit, long baseSeconds, DoubleSupplier jitter) {
        if (limit < 0 || limit > 10 || baseSeconds < 1 || baseSeconds > 3600)
            throw new IllegalArgumentException("Invalid network retry budget");
        this.limit = limit;
        this.baseSeconds = baseSeconds;
        this.jitter = java.util.Objects.requireNonNull(jitter);
    }

    static NetworkRetryBudget configured() {
        String value = System.getProperty("ai.network.maxResubmissions", "3");
        int limit;
        try { limit = Integer.parseInt(value); }
        catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("ai.network.maxResubmissions must be an integer between 0 and 10");
        }
        return new NetworkRetryBudget(limit, NetworkProbe.seconds("ai.network.backoffSeconds", 30),
                () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());
    }

    int used() { return used; }
    int limit() { return limit; }
    boolean exhausted() { return used >= limit; }
    void claim() {
        if (exhausted()) throw new IllegalStateException("Network retry budget exhausted");
        used++;
    }
    long delayNanos() {
        long base = Duration.ofSeconds(Math.min(3600L, baseSeconds << used)).toNanos();
        double sample = jitter.getAsDouble();
        if (!Double.isFinite(sample) || sample < 0 || sample > 1)
            throw new IllegalArgumentException("Jitter must be between zero and one");
        return base + (long) (base / 10L * sample);
    }
}
