package org.res.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Daily readable transcripts with timestamped event headers and literal text.
 * Header bytes count decoded payload bytes; binary payloads use base64.
 * One framing newline follows each payload, independently of its contents.
 * Missing terminal events indicate interruption, not proof of unapplied operations.
 */
public final class ConversationHistory {
    public static final String DIRECTORY = "conversation_history";
    private static final Gson CONTEXT_JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
    private static final Object APPEND_LOCK = new Object();
    private final Path directory;
    private final WorkingDirectoryPaths paths;
    private final String contextFilename;
    private final Clock clock;
    private final boolean enabled;
    private final boolean registerContext;
    private boolean contextSelectionChanged;
    private ConfigurationStore configuration;

    void useConfiguration(ConfigurationStore store) { configuration = store; }

    RequestContext configuration() throws IOException {
        if (configuration == null) configuration = new ConfigurationStore(directory, contextFilename);
        return configuration.context();
    }

    public void contextsChanged(String report) throws IOException {
        // Do not immediately re-add a history file explicitly removed this invocation.
        contextSelectionChanged = true;
        if (configuration != null) configuration.suppressHistory();
        append("contexts_changed", report.getBytes(StandardCharsets.UTF_8));
    }
    private final String requestId = UUID.randomUUID().toString();

    public ConversationHistory(Path directory, String contextFilename) {
        this(directory, contextFilename, Clock.systemDefaultZone());
    }

    public ConversationHistory(Path directory, String contextFilename, boolean enabled) {
        this(directory, contextFilename, Clock.systemDefaultZone(), enabled);
    }

    ConversationHistory(Path directory, String contextFilename, Clock clock) {
        this(directory, contextFilename, clock, true);
    }

    public ConversationHistory(Path directory, String contextFilename, boolean enabled,
            boolean registerContext) {
        this(directory, contextFilename, Clock.systemDefaultZone(), enabled, registerContext);
    }

    private ConversationHistory(Path directory, String contextFilename, Clock clock, boolean enabled) {
        this(directory, contextFilename, clock, enabled, true);
    }

    private ConversationHistory(Path directory, String contextFilename, Clock clock,
            boolean enabled, boolean registerContext) {
        this.registerContext = registerContext;

        this.directory = WorkingDirectoryPaths.canonical(directory);
        this.paths = new WorkingDirectoryPaths(this.directory);
        this.contextFilename = contextFilename == null ? "context.json" : contextFilename;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.enabled = enabled && Files.isDirectory(paths.resolve(DIRECTORY), LinkOption.NOFOLLOW_LINKS);
        Path context = paths.contextFile(new ContextFile(this.contextFilename, FileAccess.READ_WRITE));
        if (context.startsWith(paths.resolve(DIRECTORY)))
            throw new IllegalArgumentException("Context configuration must be outside " + DIRECTORY);
    }

    public String contextFilename() { return contextFilename; }

    boolean isEnabled() { return enabled; }

    /** Record already-formatted execution observations without terminal ANSI. */
    void executionEvent(String event, String report) throws IOException {
        append(event, report.getBytes(StandardCharsets.UTF_8));
    }

    void workflowEvent(String event, JsonObject record) throws IOException {
        append(event, WorkflowHistoryFormat.format(event, record).getBytes(StandardCharsets.UTF_8));
    }

    public boolean protects(Path file) throws IOException {
        return file.startsWith(paths.resolve(DIRECTORY))
                || WorkingDirectoryPaths.sameFile(file, paths.resolve(contextFilename));
    }

    public void input(byte[] bytes) throws IOException { append("user", bytes); }
    public void sent() throws IOException { append("request_sent", null); }
    public void received() throws IOException { append("response_received", null); }
    public void output(byte[] bytes) throws IOException { append("stdout", bytes); }
    public void applied() throws IOException { append("applied", null); }

    void patchRejected(String report) throws IOException {
        append("patch_rejected", report.getBytes(StandardCharsets.UTF_8));
    }

    public void failed(Throwable failure) throws IOException {
        ProviderLimitException limit = FailureReporting.limit(failure);
        if (limit != null) {
            append("provider_limit", limit.history(failure instanceof ContextCommittedException)
                    .toString().getBytes(StandardCharsets.UTF_8));
        } else {
            // Exception messages may contain an entire prompt. Never copy them to history.
            append("failed", (ProviderLimitException.safe(failure.getClass().getName(), 160)
                    + "; see terminal diagnostics; completion is not established")
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    private void append(String event, byte[] payload) throws IOException {
        if (!enabled) return;
        OffsetDateTime time = OffsetDateTime.now(clock);
        String filename = DIRECTORY + "/" + time.toLocalDate() + ".txt";
        String literal = payload == null ? null : text(payload);
        String encoding = payload == null ? "none" : literal == null ? "base64" : "utf-8";
        String body = payload == null ? "" : literal == null
                ? Base64.getEncoder().encodeToString(payload) : literal;
        String header = "--- " + time + " | " + event + " | request=" + requestId
                + (payload == null ? "" : " | encoding=" + encoding + " | bytes=" + payload.length)
                + " ---\n";
        // Keep the payload literal, even when it contains newlines or header-like text.
        byte[] bytes = (header + body + "\n").getBytes(StandardCharsets.UTF_8);
        // Serialize threads before taking the OS lock, which also coordinates processes.
        synchronized (APPEND_LOCK) {
            Path target = paths.contextFile(new ContextFile(filename, FileAccess.READ_WRITE));
            if (WorkingDirectoryPaths.sameFile(target, paths.resolve(contextFilename)))
                throw new IOException("History file aliases its context configuration: " + filename);
            if (Files.notExists(target.getParent(), LinkOption.NOFOLLOW_LINKS)) return;
            try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    var lock = channel.lock()) {
                long size = channel.size();
                if (size > 0) {
                    ByteBuffer last = ByteBuffer.allocate(1);
                    channel.position(size - 1);
                    channel.read(last);
                    channel.position(size);
                    // Preserve a crash-truncated record, but keep the next event on a new line.
                    if (last.array()[0] != '\n') write(channel, new byte[] {'\n'});
                }
                write(channel, bytes);
                channel.force(true);
                register(filename);
            }
        }
    }

    /** Reconcile even existing files, including a prior crash before registration. */
    private void register(String filename) throws IOException {
        if (!registerContext || contextSelectionChanged) return;
        if (configuration == null) {
            Path config = paths.contextFile(new ContextFile(contextFilename, FileAccess.READ_WRITE));
            if (Files.notExists(config, LinkOption.NOFOLLOW_LINKS)) return;
            configuration = new ConfigurationStore(directory, contextFilename);
        }
        configuration.registerHistory(filename);
    }

    private static void write(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static String text(byte[] bytes) {
        String text = Encoding.strictUtf8(bytes);
        return text == null || text.chars().anyMatch(ch -> Character.isISOControl(ch)
                && ch != '\n' && ch != '\r' && ch != '\t') ? null : text;
    }
    /** Store a readable listing before constructing the JSON follow-up prompt. */
    public void listingReport(String report) throws IOException {
        if (enabled) append("list_files_report", ListingHistoryFormat.format(report).getBytes(StandardCharsets.UTF_8));
    }

    void retryEvent(String event, ProviderLimitException limit, boolean contextCommitted,
            long attempt, java.time.Instant retryAt, boolean authorized) throws IOException {
        JsonObject data = limit.history(contextCommitted);
        data.addProperty("automaticReplayAuthorized", authorized);
        data.addProperty("replayScope", "provider_call");
        data.addProperty("retryAttempt", attempt);
        data.addProperty("scheduledRetryAt", retryAt.toString());
        data.addProperty("bufferSeconds", 5);
        append(event, data.toString().getBytes(StandardCharsets.UTF_8));
    }

    void networkEvent(String event, JsonObject data) throws IOException {
        append(event, data.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void listingRequested() throws IOException { append("list_files_requested", null); }
}
