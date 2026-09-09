package org.res.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Reads one process stream. Partial reads consume buffered bytes.
 * call() returns only the remaining bytes, atomically removing them from the
 * partial buffer. Each byte is returned exactly once across these operations.
 */
public class ShellProcessReaderThread implements Callable<byte[]> {
    static final long DEFAULT_MAX_BUFFERED_BYTES = 8L * 1024 * 1024;

    private final String streamName;
    private final InputStream inputStream;
    private final Object outputLock = new Object();
    private final long maxBufferedBytes;
    private final long maxTotalBytes;
    private ByteArrayOutputStream bufferedOutput = new ByteArrayOutputStream();

    public ShellProcessReaderThread(String streamName, InputStream inputStream) {
        this(streamName, inputStream, DEFAULT_MAX_BUFFERED_BYTES, 0);
    }

    /**
     * A zero limit disables that limit. The buffered limit applies to unread
     * bytes; the total limit applies over the entire stream's lifetime.
     */
    public ShellProcessReaderThread(String streamName, InputStream inputStream,
                                    long maxBufferedBytes, long maxTotalBytes) {
        if (maxBufferedBytes < 0 || maxTotalBytes < 0)
            throw new IllegalArgumentException("Output limits must not be negative");
        this.streamName = Objects.requireNonNull(streamName, "streamName");
        this.inputStream = Objects.requireNonNull(inputStream, "inputStream");
        this.maxBufferedBytes = maxBufferedBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    public byte[] readPartialResult() {
        synchronized (outputLock) {
            byte[] result = bufferedOutput.toByteArray();
            // Release the old allocation so a transient burst is not retained.
            bufferedOutput = new ByteArrayOutputStream();
            return result;
        }
    }

    @Override
    public byte[] call() throws Exception {
        long totalBytes = 0;
        try (InputStream input = inputStream) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                if (maxTotalBytes > 0 && count > maxTotalBytes - totalBytes)
                    throw new IOException(streamName + " exceeded its lifetime output limit of "
                            + maxTotalBytes + " bytes");
                if (maxTotalBytes > 0) totalBytes += count;
                synchronized (outputLock) {
                    if (maxBufferedBytes > 0
                            && count > maxBufferedBytes - bufferedOutput.size())
                        throw new IOException(streamName + " exceeded its unread output limit of "
                                + maxBufferedBytes + " bytes; poll getPartialResult() more often "
                                + "or configure a larger buffer limit");
                    bufferedOutput.write(buffer, 0, count);
                }
            }
        } catch (Exception e) {
            throw new IOException("Cannot read process " + streamName + ": " + e.getMessage(), e);
        }
        return readPartialResult();
    }
}
