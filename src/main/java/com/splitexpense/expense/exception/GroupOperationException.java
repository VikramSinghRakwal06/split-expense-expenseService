package com.splitexpense.expense.exception;

/**
 * group-service understood a request and refused it on business grounds — an archived group
 * (409), a participant who is not (or is no longer) a member (422), or a payload it rejected
 * (400).
 *
 * <p>The service is healthy and its answer is correct, so this is deliberately neither
 * retried nor recorded by the circuit breaker: asking again produces the same refusal, having
 * spent the caller's latency budget to do it.
 *
 * <p>Distinct from {@link GroupServiceUnavailableException} in the one way that matters — an
 * apply that fails with this exception definitively did <strong>not</strong> change any
 * balance, because group-service reached a decision and reported it.
 */
public class GroupOperationException extends RuntimeException {

    /** The status group-service replied with, retained for logging and for the audit trail. */
    private final int status;

    public GroupOperationException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
