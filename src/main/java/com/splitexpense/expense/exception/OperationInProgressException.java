package com.splitexpense.expense.exception;

/**
 * A request carrying this {@code Idempotency-Key} has been accepted but has not yet reached a
 * terminal state — either a concurrent request for the same key is actively being processed,
 * or an earlier attempt crashed before recording an outcome.
 *
 * <p>Shared by the expense and settlement sagas, which are structurally identical from the
 * idempotency layer's point of view. Never a reason to attempt the operation again under a
 * new key: the whole point of the key is that the caller does not yet know whether this one
 * took effect. Retrying the same request, with the same key, after a short wait is the
 * correct client behaviour.
 */
public class OperationInProgressException extends RuntimeException {

    public OperationInProgressException(String message) {
        super(message);
    }
}
