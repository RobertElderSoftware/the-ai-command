package org.res.ai;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Sanitized transport metadata. A failed probe never submits a prompt. */
public final class ProviderNetworkException extends RuntimeException {
    public enum Phase { PREFLIGHT, NOT_SUBMITTED, UNCERTAIN }
    private final String backend;
    private final String endpoint;
    private final Phase phase;

    public ProviderNetworkException(String backend, String endpoint, Phase phase) {
        super(ProviderLimitException.safe(backend, 40) + " cannot reach "
                + ProviderLimitException.safe(endpoint, 240) + "."
                + (phase == Phase.UNCERTAIN
                ? " Submission outcome is uncertain; a fresh submission may consume additional quota." : ""));
        this.backend = ProviderLimitException.safe(backend, 40);
        this.endpoint = ProviderLimitException.safe(endpoint, 240);
        this.phase = java.util.Objects.requireNonNull(phase);
    }

    public String backend() { return backend; }
    public String endpoint() { return endpoint; }
    public Phase phase() { return phase; }

    /** Do not classify certificate/configuration failures, interruption, or secondary failures. */
    static boolean transport(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean network = false;
        for (Throwable value = failure; value != null && seen.add(value); value = value.getCause()) {
            if (value.getSuppressed().length != 0 || value instanceof javax.net.ssl.SSLException
                    || value instanceof java.security.GeneralSecurityException
                    || value instanceof InterruptedException) return false;
            if (value instanceof java.io.InterruptedIOException
                    && !(value instanceof java.net.SocketTimeoutException)) return false;
            network |= value instanceof java.net.UnknownHostException
                    || value instanceof java.net.SocketException
                    || value instanceof java.net.SocketTimeoutException
                    || value instanceof java.io.EOFException;
        }
        return network && !Thread.currentThread().isInterrupted();
    }

    static boolean codexTransport(com.google.gson.JsonObject error) {
        if (error == null || error.has("code")) return false;
        String message = CodexEvents.string(error, "message");
        // Only terminal event messages; never stderr, item/tool text, or exception transcripts.
        // Codex does not establish whether these requests were accepted, so all are UNCERTAIN.
        return message != null && (message.startsWith(
                "stream disconnected before completion: error sending request for url (")
                || message.equals("stream disconnected before completion: stream closed before response.completed"));
    }
}
