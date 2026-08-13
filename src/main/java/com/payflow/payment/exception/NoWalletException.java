package com.payflow.payment.exception;

/**
 * The caller has no wallet yet — wallet-service answered {@code GET /api/v1/wallets/me} with
 * 404.
 *
 * <p>A perfectly healthy service giving a correct answer, exactly like
 * {@link InsufficientFundsException}: listed in {@code resilience4j.*}'s ignore-exceptions in
 * {@code application.yml} so it is neither retried nor counted against the {@code
 * walletService} breaker, and never reaches {@link com.payflow.payment.client.WalletClient}'s
 * fallback method.
 *
 * <p>Surfaces to the client as 404.
 */
public class NoWalletException extends RuntimeException {

    public NoWalletException(String message) {
        super(message);
    }
}
