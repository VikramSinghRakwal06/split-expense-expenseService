package com.payflow.payment.exception;

/**
 * A request carrying this {@code Idempotency-Key} has been accepted but has not yet reached a
 * terminal state — either a concurrent request for the same key is actively being processed,
 * or an earlier attempt crashed before recording an outcome.
 *
 * <p>Never a reason to attempt the transfer again under a new key: the whole point of the key
 * is that the caller does not yet know whether this one moved money. Retrying the same
 * request, with the same key, after a short wait is the correct client behaviour.
 */
public class TransferInProgressException extends RuntimeException {

    public TransferInProgressException(String message) {
        super(message);
    }
}
