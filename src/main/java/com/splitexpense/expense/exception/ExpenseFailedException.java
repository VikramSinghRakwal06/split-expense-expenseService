package com.splitexpense.expense.exception;

/**
 * An expense could not be applied — group-service refused the deltas: the group was archived,
 * or a participant is not (or is no longer) a member.
 *
 * <p>Terminal, and requires no compensation: group-service refused before any balance changed,
 * so there is nothing to reverse. This is the one respect in which the expense saga is
 * strictly simpler than the transfer saga it replaces — see {@code ExpenseStatus}'s javadoc
 * for why no {@code DEBITED}-equivalent state, and therefore no reversal exception, exists
 * here at all.
 */
public class ExpenseFailedException extends RuntimeException {

    public ExpenseFailedException(String message) {
        super(message);
    }
}
