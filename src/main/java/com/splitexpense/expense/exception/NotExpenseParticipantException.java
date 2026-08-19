package com.splitexpense.expense.exception;

/**
 * The caller voiding an expense is neither its payer nor one of its participants.
 *
 * <p>Mapped to 403: the caller has already proven membership of the group by the time this
 * check runs — group-service confirmed it — so the expense's existence is not a secret from
 * them; they simply lack standing over this specific expense. An uninvolved group member
 * cannot unilaterally undo somebody else's recorded expense.
 */
public class NotExpenseParticipantException extends RuntimeException {

    public NotExpenseParticipantException(String message) {
        super(message);
    }
}
