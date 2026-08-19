package com.splitexpense.expense.exception;

/**
 * The payer, or a participant, named in an expense — or a party named in a settlement — is
 * not a current member of the group. Mapped to 422.
 *
 * <p>Checked locally against the membership {@code GroupClient#getGroupForCaller} already
 * returned, before any group-service {@code :apply} call is attempted. That call would
 * refuse the same request with its own 422 — see group-service's identically-named exception
 * — but failing here first means one wasted remote round trip is skipped, and the message
 * can name the specific participant without echoing anything group-service didn't already
 * hand this service in the group view.
 *
 * <p>422, not 400: nothing about the request is malformed, and adding the person to the group
 * makes the identical request valid.
 */
public class NotAGroupMemberException extends RuntimeException {

    public NotAGroupMemberException(String message) {
        super(message);
    }
}
