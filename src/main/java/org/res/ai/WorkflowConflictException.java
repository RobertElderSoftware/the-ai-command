package org.res.ai;

/** A workflow revision mismatch detected before any batch commit attempt. */
final class WorkflowConflictException extends IllegalArgumentException {
    WorkflowConflictException(String message) { super(message); }
    WorkflowConflictException(String message, Throwable cause) { super(message, cause); }
}
