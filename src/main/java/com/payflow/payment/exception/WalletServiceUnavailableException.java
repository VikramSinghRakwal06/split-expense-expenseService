package com.payflow.payment.exception;

/**
 * wallet-service could not be reached, or answered in a way that says it is unwell:
 * a connect or read timeout, a connection refused, a 5xx, or an unreadable body.
 *
 * <p>This is the <em>only</em> exception the circuit breaker records as a failure and the
 * only one the retry will try again — see {@code resilience4j.*} in {@code application.yml}.
 * The distinction it draws is between "wallet-service is broken" and "wallet-service is
 * working and said no", and everything about the resilience configuration depends on it.
 * Were a business rejection routed through this type, a run of customers with empty
 * wallets would trip the breaker and take payments down for everyone.
 *
 * <h2>Ambiguity is the point</h2>
 *
 * <p>A read timeout thrown as this exception does <strong>not</strong> mean the operation
 * did not happen. The request may have been received, applied and committed, with only the
 * response lost. That is precisely why a failed credit in step 6 of the transfer flow must
 * issue a compensating credit rather than simply give up: the service cannot know which of
 * the two occurred, and the safe assumption is that the money moved.
 */
public class WalletServiceUnavailableException extends RuntimeException {

    public WalletServiceUnavailableException(String message) {
        super(message);
    }

    public WalletServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
