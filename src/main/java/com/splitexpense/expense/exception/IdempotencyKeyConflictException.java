package com.splitexpense.expense.exception;

/**
 * A client reused an {@code Idempotency-Key} with a request body that does not match the one
 * it was first used with.
 *
 * <p>A client that does this has a bug — keys are meant to identify one specific transfer
 * attempt, retried verbatim — and returning the earlier response would answer a question the
 * caller did not ask. Rejected outright rather than replayed.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String message) {
        super(message);
    }
}
