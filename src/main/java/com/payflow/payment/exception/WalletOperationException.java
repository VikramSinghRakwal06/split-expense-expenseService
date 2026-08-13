package com.payflow.payment.exception;

/**
 * wallet-service understood the request and refused it on business grounds — an unknown
 * wallet (404), a frozen or closed wallet (409), a contended wallet that exhausted its
 * optimistic-locking retries (409), or a payload it rejected (400).
 *
 * <p>The service is healthy and its answer is correct, so this is deliberately neither
 * retried nor recorded by the circuit breaker: asking again produces the same refusal,
 * having spent the caller's latency budget to do it.
 *
 * <p>Distinct from {@link WalletServiceUnavailableException} in the one way that matters —
 * a movement that fails with this exception definitively did <strong>not</strong> move
 * money, because wallet-service reached a decision and reported it. There is no ambiguity
 * to compensate for.
 *
 * <p>{@link InsufficientFundsException} is the one business refusal important enough to
 * model separately, because it is the expected outcome of an ordinary transfer rather than
 * a sign of a misaddressed one.
 */
public class WalletOperationException extends RuntimeException {

    /** The status wallet-service replied with, retained for logging and for the audit trail. */
    private final int status;

    public WalletOperationException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
