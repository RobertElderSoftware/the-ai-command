package org.res.ai;

import java.io.PrintStream;

/** Only an isolated limit or its explicitly supported commit wrapper is concise. */
final class FailureReporting {
    private FailureReporting() { }

    static ProviderLimitException limit(Throwable failure) {
        if (failure == null || failure.getSuppressed().length != 0) return null;
        Throwable candidate = failure instanceof ContextCommittedException ? failure.getCause() : failure;
        if (!(candidate instanceof ProviderLimitException limit)
                || candidate.getSuppressed().length != 0 || candidate.getCause() != null) return null;
        return limit;
    }

    static int report(Throwable failure, PrintStream out, PrintStream err) {
        Throwable candidate = failure instanceof ContextCommittedException ? failure.getCause() : failure;
        if (failure.getSuppressed().length == 0 && candidate instanceof ProviderNetworkException network
                && network.getSuppressed().length == 0 && network.getCause() == null) {
            out.println(network.getMessage() + (failure instanceof ContextCommittedException
                    ? " Context changes were already saved; do not replay the entire request." : ""));
            if (!out.checkError()) return 4;
        }
        ProviderLimitException limit = limit(failure);
        if (limit != null) {
            out.println(limit.display() + (failure instanceof ContextCommittedException
                    ? " Context changes were already saved; do not automatically replay this request." : ""));
            if (!out.checkError()) return 3;
            err.println("Could not publish the provider-limit diagnostic.");
        }
        err.println("The AI command failed:");
        failure.printStackTrace(err);
        return 1;
    }
}
