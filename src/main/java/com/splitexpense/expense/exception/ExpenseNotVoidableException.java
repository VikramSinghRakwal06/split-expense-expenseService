package com.splitexpense.expense.exception;

/**
 * An expense was asked to be voided while it is not in a state that permits it — it never
 * completed, or it has already been voided. Mapped to 409.
 *
 * <p>Only a {@code COMPLETED} expense may be voided; see {@code ExpenseStatus}. A conflict,
 * not a validation failure: the request names a real expense, its current state simply
 * forbids this particular transition.
 */
public class ExpenseNotVoidableException extends RuntimeException {

    public ExpenseNotVoidableException(String message) {
        super(message);
    }
}
