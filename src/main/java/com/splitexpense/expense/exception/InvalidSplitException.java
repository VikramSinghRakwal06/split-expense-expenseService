package com.splitexpense.expense.exception;

/**
 * The participant list and split inputs given for an expense do not describe a valid split —
 * a duplicate participant, a percentage set that does not sum to 100, exact amounts that do
 * not sum to the total, or similar. Mapped to 400.
 *
 * <p>Raised entirely within {@code SplitCalculator}, before any wallet-service-style remote
 * call or database write, so a malformed split never reaches group-service at all.
 */
public class InvalidSplitException extends RuntimeException {

    public InvalidSplitException(String message) {
        super(message);
    }
}
