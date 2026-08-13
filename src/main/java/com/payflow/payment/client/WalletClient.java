package com.payflow.payment.client;

import com.payflow.payment.client.dto.WalletBalanceView;
import com.payflow.payment.client.dto.WalletErrorBody;
import com.payflow.payment.client.dto.WalletMovementRequest;
import com.payflow.payment.exception.InsufficientFundsException;
import com.payflow.payment.exception.NoWalletException;
import com.payflow.payment.exception.WalletOperationException;
import com.payflow.payment.exception.WalletServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The only way this service moves money: the two internal calls onto wallet-service that back
 * every leg of a transfer.
 *
 * <p>Every failure wallet-service or the network can produce is translated here into exactly
 * one of the three exceptions {@code application.yml}'s {@code resilience4j.*} configuration
 * already knows how to treat:
 *
 * <ul>
 *   <li>{@link InsufficientFundsException} — the debit was refused for a healthy, ordinary
 *       reason. Never retried, never counted against the breaker.</li>
 *   <li>{@link WalletOperationException} — wallet-service reached a decision and reported it.
 *       Whatever it was, it is not this service's to second-guess by retrying.</li>
 *   <li>{@link WalletServiceUnavailableException} — wallet-service could not be reached, or
 *       answered in a way that means "unwell". Retried, and counted against the breaker. See
 *       its javadoc for why a call that fails this way must be treated as having possibly
 *       succeeded.</li>
 * </ul>
 *
 * <p>{@code @CircuitBreaker} and {@code @Retry} only take effect on calls arriving through
 * this bean's Spring proxy, so {@link #debit} and {@link #credit} must never call each other
 * or themselves directly.
 */
@Component
public class WalletClient {

    private static final Logger log = LoggerFactory.getLogger(WalletClient.class);

    private final RestClient walletRestClient;
    private final AdminTokenProvider adminTokenProvider;

    public WalletClient(
            @Qualifier("walletRestClient") RestClient walletRestClient,
            AdminTokenProvider adminTokenProvider) {
        this.walletRestClient = walletRestClient;
        this.adminTokenProvider = adminTokenProvider;
    }

    /**
     * Debits the sender's wallet. A 422 here means the sender cannot afford the transfer.
     *
     * @param walletId    sender's wallet
     * @param amount      positive sum to remove
     * @param reference   this transfer's transaction id, for wallet-service's ledger
     * @param description human-readable note for the ledger entry
     * @return the wallet's balance immediately after the debit
     * @throws InsufficientFundsException      the sender cannot cover this transfer
     * @throws WalletOperationException        wallet-service refused the debit for any other
     *                                         business reason
     * @throws WalletServiceUnavailableException wallet-service could not be reached or is
     *                                         unwell
     */
    @CircuitBreaker(name = "walletService", fallbackMethod = "walletUnavailable")
    @Retry(name = "walletService")
    public WalletBalanceView debit(UUID walletId, BigDecimal amount, String reference, String description) {
        return attempt(walletId, "debit", amount, reference, description, true, true);
    }

    /**
     * Credits the receiver's wallet. Also used, with the sender's own wallet id, to issue the
     * compensating credit that reverses a debit whose matching credit failed or was ambiguous.
     *
     * @param walletId    wallet to credit
     * @param amount      positive sum to add
     * @param reference   this transfer's transaction id, for wallet-service's ledger
     * @param description human-readable note for the ledger entry
     * @return the wallet's balance immediately after the credit
     * @throws WalletOperationException        wallet-service refused the credit
     * @throws WalletServiceUnavailableException wallet-service could not be reached or is
     *                                         unwell
     */
    @CircuitBreaker(name = "walletService", fallbackMethod = "walletUnavailable")
    @Retry(name = "walletService")
    public WalletBalanceView credit(UUID walletId, BigDecimal amount, String reference, String description) {
        return attempt(walletId, "credit", amount, reference, description, false, true);
    }

    /**
     * @param unprocessableMeansInsufficientFunds whether a 422 on this call should surface as
     *                                            {@link InsufficientFundsException} rather
     *                                            than a generic {@link WalletOperationException}
     * @param allowTokenRetry                     whether a 401 may be retried once after
     *                                            forcing a fresh service token; false on the
     *                                            retry itself, so a second rejection cannot
     *                                            loop
     */
    private WalletBalanceView attempt(
            UUID walletId,
            String direction,
            BigDecimal amount,
            String reference,
            String description,
            boolean unprocessableMeansInsufficientFunds,
            boolean allowTokenRetry) {

        try {
            return walletRestClient.post()
                    .uri("/api/v1/wallets/{walletId}/{direction}", walletId, direction)
                    .header(HttpHeaders.AUTHORIZATION, adminTokenProvider.bearerToken())
                    .body(new WalletMovementRequest(amount, reference, description))
                    .retrieve()
                    .body(WalletBalanceView.class);

        } catch (HttpClientErrorException.Unauthorized ex) {
            if (!allowTokenRetry) {
                throw new WalletServiceUnavailableException(
                        "wallet-service rejected the service token twice in a row", ex);
            }
            log.warn("Service token rejected by wallet-service on {}; refreshing and retrying once",
                    direction);
            adminTokenProvider.invalidate();
            return attempt(walletId, direction, amount, reference, description,
                    unprocessableMeansInsufficientFunds, false);

        } catch (HttpClientErrorException.UnprocessableEntity ex) {
            if (unprocessableMeansInsufficientFunds) {
                throw new InsufficientFundsException(extractMessage(ex));
            }
            throw new WalletOperationException(ex.getStatusCode().value(), extractMessage(ex));

        } catch (HttpClientErrorException ex) {
            throw new WalletOperationException(ex.getStatusCode().value(), extractMessage(ex));

        } catch (HttpServerErrorException ex) {
            throw new WalletServiceUnavailableException(
                    "wallet-service responded with " + ex.getStatusCode().value(), ex);

        } catch (ResourceAccessException ex) {
            // Connect refused, connect timeout, or read timeout. See the class javadoc and
            // WalletServiceUnavailableException: a read timeout here does not mean the call
            // failed, only that its outcome is unknown.
            throw new WalletServiceUnavailableException("wallet-service was unreachable", ex);
        }
    }

    /**
     * Resolves the caller's own wallet, forwarding their bearer token rather than the service
     * account's — this is a read the caller is entitled to make for themselves, not an
     * internal movement, so it must not be escalated to admin.
     *
     * @param authorizationHeader the caller's own {@code Authorization} header, verbatim
     * @return the caller's wallet
     * @throws NoWalletException                 the caller has never created a wallet
     * @throws WalletServiceUnavailableException wallet-service could not be reached or is
     *                                           unwell
     */
    @CircuitBreaker(name = "walletService", fallbackMethod = "myWalletUnavailable")
    @Retry(name = "walletService")
    public WalletBalanceView getWalletForCaller(String authorizationHeader) {
        try {
            return walletRestClient.get()
                    .uri("/api/v1/wallets/me")
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .retrieve()
                    .body(WalletBalanceView.class);

        } catch (HttpClientErrorException.NotFound ex) {
            throw new NoWalletException("No wallet exists for this account. Create one first.");

        } catch (HttpClientErrorException ex) {
            throw new WalletOperationException(ex.getStatusCode().value(), extractMessage(ex));

        } catch (HttpServerErrorException ex) {
            throw new WalletServiceUnavailableException(
                    "wallet-service responded with " + ex.getStatusCode().value(), ex);

        } catch (ResourceAccessException ex) {
            throw new WalletServiceUnavailableException("wallet-service was unreachable", ex);
        }
    }

    @SuppressWarnings("unused")
    private WalletBalanceView myWalletUnavailable(String authorizationHeader, Throwable throwable) {
        throw new WalletServiceUnavailableException("wallet-service is currently unavailable", throwable);
    }

    /**
     * Invoked by the resilience4j proxy in place of {@link #debit} or {@link #credit} once the
     * breaker is open, or after every retry attempt on a {@link WalletServiceUnavailableException}
     * is exhausted. {@link InsufficientFundsException} and {@link WalletOperationException} are
     * configured as ignored exceptions in {@code application.yml}, so they never reach this
     * method — they propagate directly from the failed attempt.
     *
     * @param throwable what the last attempt (or the open breaker itself) produced
     */
    @SuppressWarnings("unused")
    private WalletBalanceView walletUnavailable(
            UUID walletId, BigDecimal amount, String reference, String description,
            Throwable throwable) {
        throw new WalletServiceUnavailableException("wallet-service is currently unavailable", throwable);
    }

    private String extractMessage(HttpStatusCodeException ex) {
        try {
            WalletErrorBody body = ex.getResponseBodyAs(WalletErrorBody.class);
            if (body != null && body.message() != null && !body.message().isBlank()) {
                return body.message();
            }
        } catch (RuntimeException ignored) {
            // Response body was not the expected shape; fall through to the generic message.
        }
        return "wallet-service responded with " + ex.getStatusCode().value();
    }
}
