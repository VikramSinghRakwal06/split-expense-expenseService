package com.splitexpense.expense.exception;

/**
 * A settlement was for more than {@code fromUserId} currently owes {@code toUserId} — including
 * the zero-debt case, where the pair has already settled or the debt runs the other way.
 * Mapped to 422.
 *
 * <p>Checked against a fresh read of the group's balances before any settlement row is
 * written, so a settlement can never manufacture a debt that never existed — see {@code
 * SettlementService#recordSettlement}.
 *
 * <p>422, not 400: nothing about the request is malformed by itself, and it becomes valid the
 * moment an actual debt of at least this size exists between the two parties.
 */
public class InsufficientDebtException extends RuntimeException {

    public InsufficientDebtException(String message) {
        super(message);
    }
}
