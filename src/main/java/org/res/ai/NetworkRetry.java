package org.res.ai;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Owns one outage episode; called inside the usage-limit loop, outside operation application. */
final class NetworkRetry {
    @FunctionalInterface interface HistorySink {
        void record(String event, JsonObject data) throws IOException;
    }
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final ProviderRetry.Sleeper sleeper;
    private final boolean terminal;
    private final long interval;
    private final boolean replayUncertain;
    private final boolean shutdownHook;
    private final NetworkRetryBudget budget;

    NetworkRetry(Clock clock, LongSupplier nanoTime, ProviderRetry.Sleeper sleeper,
            boolean terminal, Duration interval, boolean replayUncertain, boolean shutdownHook) {
        this(clock, nanoTime, sleeper, terminal, interval, replayUncertain, shutdownHook,
                NetworkRetryBudget.configured());
    }

    NetworkRetry(Clock clock, LongSupplier nanoTime, ProviderRetry.Sleeper sleeper,
            boolean terminal, Duration interval, boolean replayUncertain, boolean shutdownHook,
            NetworkRetryBudget budget) {
        this.budget = budget;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
        this.terminal = terminal;
        this.interval = interval.toNanos();
        if (this.interval <= 0) throw new IllegalArgumentException("Positive network retry interval required");
        this.replayUncertain = replayUncertain;
        this.shutdownHook = shutdownHook;
    }

    String complete(LLMProvider provider, String prompt, OutputStream output,
            ConversationHistory history, boolean contextCommitted, boolean enabled) throws IOException {
        return completeWithHistory(provider, prompt, output, history == null ? null : history::networkEvent,
                contextCommitted, enabled);
    }

    String completeWithHistory(LLMProvider provider, String prompt, OutputStream output,
            HistorySink history, boolean contextCommitted, boolean enabled) throws IOException {
        Episode episode = null;
        Thread hook = null;
        long submissions = 0;
        ProviderNetworkException pending = null;
        int recoveryAttempts = 0;
        Throwable terminalFailure = null;
        String outcome = "failed";
        try {
            while (true) {
                interrupted();
                boolean submitting = false;
                try {
                    provider.checkNetwork();
                    if (pending != null) {
                        if (provider.canRecover() && recoveryAttempts < 3) {
                            recoveryAttempts++;
                            write(output, "Recovering the original response (lookup "
                                    + recoveryAttempts + "/3); no new prompt submitted.\n");
                            if (episode != null) episode.recoveryAttempts++;
                            String recovered = provider.recover();
                            if (episode != null) episode.reachableAt = nanoTime.getAsLong();
                            outcome = "retrieved";
                            return recovered;
                        }
                        if (pending.phase() == ProviderNetworkException.Phase.UNCERTAIN && !replayUncertain) {
                            outcome = "uncertain";
                            throw pending;
                        }
                        if (budget.exhausted()) {
                            outcome = "exhausted";
                            write(output, "Network resubmission budget exhausted (" + budget.used()
                                    + "/" + budget.limit() + "). Exiting; inspect provider activity before retrying.\n");
                            throw pending;
                        }
                        write(output, "Fresh prompt resubmission " + (budget.used() + 1) + "/"
                                + budget.limit() + "; original result unavailable. May consume duplicate quota.\n");
                        budget.claim();
                        pending = null;
                        recoveryAttempts = 0;
                    }
                    if (episode != null) episode.reachableAt = nanoTime.getAsLong();
                    interrupted();
                    submissions++;
                    if (episode != null) episode.resubmissions = Math.max(0, submissions - 1);
                    submitting = true;
                    String response = provider.complete(prompt);
                    outcome = "recovered";
                    return response;
                } catch (ProviderNetworkException failure) {
                    if (outcome.equals("exhausted") || outcome.equals("uncertain")) throw failure;
                    if (episode != null) episode.reachableAt = null;
                    if (failure.getSuppressed().length != 0 || failure.getCause() != null
                            || !enabled || (failure.phase() == ProviderNetworkException.Phase.UNCERTAIN
                            && !replayUncertain && !provider.canRecover())) {
                        outcome = failure.phase() == ProviderNetworkException.Phase.UNCERTAIN
                                ? "uncertain" : "disabled";
                        throw failure;
                    }
                    interrupted();
                    if (submitting) {
                        pending = failure;
                        recoveryAttempts = 0;
                    }
                    if (pending != null && budget.exhausted() && !provider.canRecover()) {
                        outcome = "exhausted";
                        write(output, "Network resubmission budget exhausted (" + budget.used()
                                + "/" + budget.limit() + "). Exiting; inspect provider activity before retrying.\n");
                        throw failure;
                    }
                    if (episode == null) {
                        episode = new Episode(failure, nanoTime.getAsLong(), output, history, contextCommitted);
                        episode.resubmissions = Math.max(0, submissions - 1);
                        // Mark the episode before output so secondary failures still close it.
                        episode.record("network_wait_started", "waiting");
                        episode.started = true;
                        if (shutdownHook) {
                            Episode current = episode;
                            hook = new Thread(() -> {
                                Thread summary = new Thread(() -> {
                                    try { current.finish("cancelled"); }
                                    catch (IOException | RuntimeException ignored) { }
                                }, "network-summary");
                                summary.setDaemon(true);
                                summary.start();
                                try { summary.join(250); }
                                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                            }, "network-wait-shutdown");
                            Runtime.getRuntime().addShutdownHook(hook);
                        }
                        if (!terminal)
                            write(output, "Cannot reach " + failure.endpoint() + "; retrying every "
                                    + interval / 1_000_000_000L + " seconds. Ctrl+C to exit.\n");
                    }
                    if (failure.phase() == ProviderNetworkException.Phase.PREFLIGHT)
                        episode.failedChecks++;
                    if (submitting) {
                        long delay = budget.delayNanos();
                        write(output, "Original response interrupted; recovery or fresh submission after "
                                + duration(delay) + ". Network resubmissions used " + budget.used()
                                + "/" + budget.limit() + "; Ctrl+C to exit.\n");
                        waitForRetry(episode, delay);
                    } else {
                        waitForRetry(episode, interval);
                    }
                } catch (ProviderLimitException limit) {
                    outcome = "provider_limit";
                    throw limit; // Close this episode before handing off to the reset-time controller.
                }
            }
        } catch (IOException | RuntimeException failure) {
            terminalFailure = failure;
            if (episode != null && failure != episode.failure
                    && !(failure instanceof ProviderLimitException)
                    && !(failure instanceof ProviderNetworkException)) failure.addSuppressed(episode.failure);
            if (failure instanceof InterruptedIOException || Thread.currentThread().isInterrupted())
                outcome = "cancelled";
            throw failure;
        } finally {
            try {
                if (episode != null && episode.started) episode.finish(outcome);
            } catch (IOException | RuntimeException secondary) {
                if (terminalFailure != null) terminalFailure.addSuppressed(secondary);
                else throw secondary;
            } finally {
                if (hook != null) {
                    try { Runtime.getRuntime().removeShutdownHook(hook); }
                    catch (IllegalStateException shuttingDown) { }
                }
            }
        }
    }

    private void waitForRetry(Episode episode, long waitNanos) throws IOException {
        long started = nanoTime.getAsLong();
        var retryAt = clock.instant().plusNanos(waitNanos);
        RetryDisplay display = terminal ? new RetryDisplay(episode.output) : null;
        try {
            while (true) {
                interrupted();
                long elapsed = Math.max(0, nanoTime.getAsLong() - started);
                long remaining = elapsed >= waitNanos ? 0 : waitNanos - elapsed;
                if (remaining == 0) break;
                if (terminal) {
                    String text = "Network wait | detected " + ProviderRetry.displayTime(episode.detectedAt, clock)
                            + " | elapsed \u001b[35m" + duration(episode.elapsed()) + "\u001b[1;36m"
                            + " | check retries " + Math.max(0, episode.failedChecks - 1)
                            + " | resubmissions " + budget.used() + "/" + budget.limit()
                            + " | lookups " + episode.recoveryAttempts
                            + " | retry " + ProviderRetry.displayTime(retryAt, clock)
                            + " | in \u001b[1;33m" + duration(remaining)
                            + "\u001b[0m | Ctrl+C";
                    display.update(text);
                }
                try { sleeper.sleep(Math.min(remaining, 1_000_000_000L)); }
                catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Network retry cancelled");
                }
            }
        } finally {
            if (display != null) display.close();
        }
    }

    private final class Episode {
        final ProviderNetworkException failure;
        final long begin;
        final java.time.Instant detectedAt = clock.instant();
        final OutputStream output;
        final HistorySink history;
        final boolean contextCommitted;
        final AtomicBoolean finished = new AtomicBoolean();
        volatile Long reachableAt;
        volatile long failedChecks;
        volatile long resubmissions;
        volatile long recoveryAttempts;
        boolean started;

        Episode(ProviderNetworkException failure, long begin, OutputStream output,
                HistorySink history, boolean contextCommitted) {
            this.failure = failure;
            this.begin = begin;
            this.output = output;
            this.history = history;
            this.contextCommitted = contextCommitted;
        }

        long elapsed() {
            Long reached = reachableAt;
            return Math.max(0, (reached == null ? nanoTime.getAsLong() : reached) - begin);
        }

        void record(String event, String outcome) throws IOException {
            if (history == null) return;
            JsonObject data = new JsonObject();
            data.addProperty("backend", failure.backend());
            data.addProperty("endpoint", failure.endpoint());
            data.addProperty("phase", failure.phase().name().toLowerCase(java.util.Locale.ROOT));
            data.addProperty("outcome", outcome);
            data.addProperty("elapsedMillis", elapsed() / 1_000_000L);
            data.addProperty("failedChecks", failedChecks);
            data.addProperty("promptResubmissions", resubmissions);
            data.addProperty("networkBudgetUsed", budget.used());
            data.addProperty("networkBudgetLimit", budget.limit());
            data.addProperty("recoveryAttempts", recoveryAttempts);
            data.addProperty("possibleDuplicateQuota", resubmissions > 0);
            data.addProperty("contextCommitted", contextCommitted);
            data.addProperty("uncertainReplayAuthorized", replayUncertain);
            history.record(event, data);
        }

        void finish(String outcome) throws IOException {
            if (!finished.compareAndSet(false, true)) return;
            // FileChannel history appends reject an already-interrupted thread.
            boolean cancelled = Thread.interrupted();
            try {
                record("network_wait_finished", outcome);
                write(output, (terminal ? "\u001b[0m\n" : "") + "Network wait finished: " + outcome
                        + "; observed duration " + duration(elapsed()) + "; failed checks " + failedChecks
                        + "; prompt resubmissions " + resubmissions + ".\n");
            } finally {
                if (cancelled) Thread.currentThread().interrupt();
            }
        }
    }

    private static String duration(long nanos) {
        long seconds = nanos / 1_000_000_000L + (nanos % 1_000_000_000L == 0 ? 0 : 1);
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
                seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Network retry cancelled");
    }

    private void write(OutputStream output, String text) throws IOException {
        output.write(StatusColour.format(text.replace("\u001b[0m", ""), terminal).getBytes(StandardCharsets.UTF_8));
        output.flush();
        if (output instanceof PrintStream stream && stream.checkError())
            throw new IOException("Could not publish network retry status");
    }
}
