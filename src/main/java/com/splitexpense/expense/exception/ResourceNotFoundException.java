package com.splitexpense.expense.exception;

/**
 * No expense or settlement exists with the given id, <strong>or</strong> the caller is not a
 * member of the group it belongs to.
 *
 * <p>The two cases are deliberately indistinguishable to the caller, mirroring group-service's
 * own {@code GroupController}: confirming that an expense exists in a group somebody does not
 * belong to is already more than they should learn. The membership half of this check is made
 * by asking group-service for the group with the caller's own token and catching
 * {@link NoSuchGroupException}; see {@code ExpenseService#getExpense}.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
