package com.payflow.payment.exception;

/**
 * The sender was debited, the receiver could not be credited, and the compensating credit
 * back to the sender succeeded.
 *
 * <p>Terminal, and safe: the net effect on both wallets is zero, exactly as if the transfer
 * had never been attempted. 409, not 422 or 502 — the request itself was fine, something got
 * in the way, and a fresh attempt (under a new {@code Idempotency-Key}) may well succeed.
 */
public class TransferReversedException extends RuntimeException {

    public TransferReversedException(String message) {
        super(message);
    }
}
