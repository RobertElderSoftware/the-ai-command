package org.res.ai;

import java.io.IOException;

/** A control operation was saved before a subsequent request-stage failure. */
public final class ContextCommittedException extends IOException {
    public ContextCommittedException(Throwable cause) {
        super("Context changes were committed; a subsequent follow-up failed. "
                + "Do not retry the request automatically.", cause);
    }
}
