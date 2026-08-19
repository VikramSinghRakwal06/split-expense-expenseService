package com.splitexpense.expense.exception;

/**
 * A settlement could not be applied — group-service refused the delta. See
 * {@link ExpenseFailedException}; the reasoning is identical.
 */
public class SettlementFailedException extends RuntimeException {

    public SettlementFailedException(String message) {
        super(message);
    }
}
