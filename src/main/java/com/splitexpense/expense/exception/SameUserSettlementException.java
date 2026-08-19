package com.splitexpense.expense.exception;

/**
 * A settlement named the same user as both payer and recipient.
 *
 * <p>Rejected before any group-service call or {@link com.splitexpense.expense.entity.Settlement}
 * row is created — checked in application code so the caller gets a clear 400 rather than
 * discovering it as a raw {@code ck_settlements_distinct_users} constraint violation, which
 * exists as the schema's own backstop, not as the primary defence.
 */
public class SameUserSettlementException extends RuntimeException {

    public SameUserSettlementException(String message) {
        super(message);
    }
}
