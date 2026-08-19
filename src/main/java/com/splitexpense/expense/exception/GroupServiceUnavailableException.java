package com.splitexpense.expense.exception;

/**
 * group-service could not be reached, or answered in a way that says it is unwell: a connect
 * or read timeout, a connection refused, a 5xx, or an unreadable body.
 *
 * <p>This is the <em>only</em> exception the circuit breaker records as a failure and the
 * only one the retry will try again — see {@code resilience4j.*} in {@code application.yml}.
 * The distinction it draws is between "group-service is broken" and "group-service is working
 * and said no", and everything about the resilience configuration depends on it. Were a
 * business rejection routed through this type, a run of expenses touching an archived group
 * would trip the breaker and take expense recording down for everyone.
 *
 * <h2>Ambiguity, and why it costs nothing here</h2>
 *
 * <p>A read timeout thrown as this exception does not mean the apply did not happen — the
 * request may have been received and committed, with only the response lost. Unlike
 * wallet-service's two-call transfer, that ambiguity is harmless: group-service's
 * {@code :apply} endpoint is idempotent by this service's own reference id, so retrying (or
 * simply leaving the expense {@code INITIATED} for a later retry) converges on the correct
 * state whether or not the original call landed. See {@code ExpenseService}.
 */
public class GroupServiceUnavailableException extends RuntimeException {

    public GroupServiceUnavailableException(String message) {
        super(message);
    }

    public GroupServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
