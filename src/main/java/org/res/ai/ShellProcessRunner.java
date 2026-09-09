package org.res.ai;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a command while draining stdout and stderr independently.
 *
 * Partial results consume output. The final result contains ONLY the remaining
 * output, not a complete transcript. Concatenate partial bytes in polling order,
 * then append the final bytes separately for each stream when a transcript is
 * needed. Decode after concatenating, or use an incremental charset decoder:
 * partial results may split UTF-8 characters.
 *
 * Existing constructors have no timeout and no lifetime output limit. They
 * permit 8 MiB of unread output per stream. Poll regularly for long-lived
 * commands, or use the configurable constructor to change this limit.
 *
 * close() cancels unfinished work. Reader errors and configured timeouts also
 * terminate the process without requiring a caller to poll. Nonzero process
 * exits are returned normally through ShellProcessFinalResult.
 */
public class ShellProcessRunner implements AutoCloseable {
    private static final ThreadFactory DAEMON_THREADS = task -> {
        Thread thread = new Thread(task, "shell-process-worker");
        thread.setDaemon(true);
        return thread;
    };

    private final List<String> commandParts;
    private final Map<String, String> environmentVariables;
    private final File commandWorkingDirectory;
    private final boolean expectsStdin;
    private final long timeoutNanos;
    private final Duration timeout;
    private final long maxBufferedBytes;
    private final long maxTotalBytes;
    private final Object resultLock = new Object();
    private final AtomicReference<Exception> failure = new AtomicReference<>();
    private final AtomicBoolean cleanedUp = new AtomicBoolean();

    private Process process;
    private ExecutorService readers;
    private ScheduledExecutorService monitor;
    private ShellProcessReaderThread stdoutReader;
    private ShellProcessReaderThread stderrReader;
    private Future<byte[]> stdoutFuture;
    private Future<byte[]> stderrFuture;
    private long startedAt;
    private boolean setupAttempted;
    private volatile boolean completed;
    private boolean finalResultTaken;

    public ShellProcessRunner(List<String> commandParts) throws Exception {
        this(commandParts, null, null, false);
    }

    public ShellProcessRunner(List<String> commandParts,
                              Map<String, String> environmentVariables,
                              File commandWorkingDirectory, boolean expectsStdin) throws Exception {
        this(commandParts, environmentVariables, commandWorkingDirectory,
                expectsStdin, Duration.ZERO);
    }

    public ShellProcessRunner(List<String> commandParts, Duration timeout) throws Exception {
        this(commandParts, null, null, false, timeout);
    }

    /** A zero timeout allows an indefinitely running command. */
    public ShellProcessRunner(List<String> commandParts,
                              Map<String, String> environmentVariables,
                              File commandWorkingDirectory, boolean expectsStdin,
                              Duration timeout) throws Exception {
        this(commandParts, environmentVariables, commandWorkingDirectory, expectsStdin,
                timeout, ShellProcessReaderThread.DEFAULT_MAX_BUFFERED_BYTES, 0);
    }

    /**
     * Limits apply independently to stdout and stderr. Zero disables a limit.
     * maxBufferedBytes bounds unread output, allowing unlimited lifetime output
     * when the caller keeps polling. maxTotalBytes optionally bounds lifetime
     * output even when partial results are consumed.
     *
     * The timeout starts after process creation and includes draining output.
     * For ffprobe, use Duration.ofSeconds(60) and 8 MiB for both byte limits.
     */
    public ShellProcessRunner(List<String> commandParts,
                              Map<String, String> environmentVariables,
                              File commandWorkingDirectory, boolean expectsStdin,
                              Duration timeout, long maxBufferedBytes,
                              long maxTotalBytes) throws Exception {
        this.commandParts = List.copyOf(Objects.requireNonNull(commandParts, "commandParts"));
        if (this.commandParts.isEmpty() || this.commandParts.get(0).isEmpty())
            throw new IllegalArgumentException("A nonempty executable name is required");
        this.environmentVariables = environmentVariables == null
                ? null : Map.copyOf(environmentVariables);
        this.commandWorkingDirectory = commandWorkingDirectory;
        this.expectsStdin = expectsStdin;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative())
            throw new IllegalArgumentException("Timeout must not be negative");
        try {
            this.timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Timeout is too large", e);
        }
        if (maxBufferedBytes < 0 || maxTotalBytes < 0)
            throw new IllegalArgumentException("Output limits must not be negative");
        this.maxBufferedBytes = maxBufferedBytes;
        this.maxTotalBytes = maxTotalBytes;
        commonSetup();
    }

    /** Retained for compatibility; setup cannot be repeated on an existing runner. */
    public final synchronized void commonSetup() throws Exception {
        if (setupAttempted)
            throw new IllegalStateException("This ShellProcessRunner has already attempted setup");
        setupAttempted = true;
        try {
            ProcessBuilder builder = new ProcessBuilder(commandParts);
            if (commandWorkingDirectory != null) builder.directory(commandWorkingDirectory);
            if (environmentVariables != null) {
                // Preserve the original environment replacement behavior.
                builder.environment().clear();
                builder.environment().putAll(environmentVariables);
            }
            try {
                process = builder.start();
            } catch (IOException e) {
                throw new IOException("Cannot start executable '" + commandParts.get(0)
                        + "': check that it is installed, executable, and on PATH, and that "
                        + "its working directory and runtime dependencies exist. " + e.getMessage(), e);
            }
            startedAt = System.nanoTime();
            if (!expectsStdin) process.getOutputStream().close();
            readers = Executors.newFixedThreadPool(2, DAEMON_THREADS);
            stdoutReader = new ShellProcessReaderThread("stdout", process.getInputStream(),
                    maxBufferedBytes, maxTotalBytes);
            stderrReader = new ShellProcessReaderThread("stderr", process.getErrorStream(),
                    maxBufferedBytes, maxTotalBytes);
            stdoutFuture = readers.submit(stdoutReader);
            stderrFuture = readers.submit(stderrReader);
            monitor = Executors.newSingleThreadScheduledExecutor(DAEMON_THREADS);
            monitor.scheduleWithFixedDelay(this::checkProgress, 0, 10, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            failure.compareAndSet(null, e);
            cleanup();
            throw e;
        }
    }

    public OutputStream getOutputStreamForStdin() throws Exception {
        throwIfFailed();
        if (!expectsStdin)
            throw new IllegalStateException("This command was started with expectsStdin=false");
        return process.getOutputStream();
    }

    public ShellProcessPartialResult getPartialResult() throws Exception {
        checkProgress();
        synchronized (resultLock) {
            throwIfFailed();
            if (finalResultTaken)
                throw new IllegalStateException("The final result has already been consumed");
            return new ShellProcessPartialResult(stdoutReader.readPartialResult(),
                    stderrReader.readPartialResult());
        }
    }

    /** True when the process and both readers have finished; failures are thrown. */
    public boolean isFinished() throws Exception {
        checkProgress();
        throwIfFailed();
        return completed;
    }

    /**
     * Waits for completion and consumes the remaining output exactly once.
     * Partial polling may continue from another thread while this method waits.
     * Interruption cancels the command and preserves the interrupt flag.
     */
    public ShellProcessFinalResult getFinalResult() throws Exception {
        try {
            while (!isFinished()) TimeUnit.MILLISECONDS.sleep(10);
            synchronized (resultLock) {
                throwIfFailed();
                if (finalResultTaken)
                    throw new IllegalStateException("The final result has already been consumed");
                ShellProcessPartialResult output = new ShellProcessPartialResult(
                        stdoutFuture.get(), stderrFuture.get());
                ShellProcessFinalResult result = new ShellProcessFinalResult(output, process.exitValue());
                finalResultTaken = true;
                return result;
            }
        } catch (InterruptedException e) {
            failure.compareAndSet(null, e);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            cleanup();
        }
    }

    private void throwIfFailed() throws Exception {
        Exception problem = failure.get();
        if (problem != null) throw problem;
    }

    private void checkProgress() {
        if (completed || failure.get() != null) return;
        try {
            inspectReader(stdoutFuture);
            inspectReader(stderrFuture);
            if (timeoutNanos > 0 && System.nanoTime() - startedAt >= timeoutNanos)
                throw new IOException("Command '" + commandParts.get(0)
                        + "' timed out after " + timeout);
            if (!process.isAlive() && stdoutFuture.isDone() && stderrFuture.isDone()) {
                completed = true;
                // Results remain in the futures until the caller consumes them.
                readers.shutdown();
                monitor.shutdown();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        } catch (Exception e) {
            fail(e);
        }
    }

    private static void inspectReader(Future<byte[]> future) throws Exception {
        if (!future.isDone()) return;
        try {
            future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IOException("Process output reader failed", cause);
        }
    }

    private void fail(Exception e) {
        failure.compareAndSet(null, e);
        cleanup();
    }

    /** Cancels unfinished work; safe to call repeatedly and from another thread. */
    @Override
    public void close() {
        if (!completed)
            failure.compareAndSet(null, new CancellationException(
                    "Command '" + commandParts.get(0) + "' was cancelled"));
        cleanup();
    }

    private void cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) return;
        if (monitor != null) monitor.shutdownNow();
        if (process != null && process.isAlive()) {
            // Also terminate currently discoverable children that may hold pipes open.
            try {
                process.descendants().forEach(child -> {
                    try { child.destroyForcibly(); } catch (RuntimeException ignored) { }
                });
            } catch (RuntimeException ignored) { }
            process.destroyForcibly();
        }
        if (stdoutFuture != null && !stdoutFuture.isDone()) stdoutFuture.cancel(true);
        if (stderrFuture != null && !stderrFuture.isDone()) stderrFuture.cancel(true);
        if (readers != null) readers.shutdownNow();
        if (process != null) {
            closeQuietly(process.getOutputStream());
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
        }
    }

    private static void closeQuietly(Closeable stream) {
        try { stream.close(); } catch (IOException ignored) {
            // Cleanup must not replace the original process or reader failure.
        }
    }
}
