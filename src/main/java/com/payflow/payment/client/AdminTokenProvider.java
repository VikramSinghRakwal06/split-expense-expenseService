package com.payflow.payment.client;

import com.payflow.payment.client.dto.ServiceLoginRequest;
import com.payflow.payment.client.dto.ServiceTokenResponse;
import com.payflow.payment.config.ServiceAccountProperties;
import com.payflow.payment.exception.WalletServiceUnavailableException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Holds the one bearer token this service authenticates to wallet-service with, and refreshes
 * it before it expires.
 *
 * <p>See {@link ServiceAccountProperties} for why a login flow rather than a static token: the
 * platform has no service-identity mechanism, so payment-service authenticates as an ordinary
 * {@code ADMIN} account through the same {@code /login} endpoint a person would use, and gets
 * back the same short-lived, unrevokable access token auth-service issues everyone.
 *
 * <p>Cached rather than fetched per call — a token good for roughly fifteen minutes should not
 * cost a network round trip on every transfer. Refreshed a safety margin ahead of its stated
 * expiry so an ordinary transfer never races the clock; {@link #invalidate()} forces an
 * out-of-cycle refresh for the one case the margin cannot cover, wallet-service rejecting a
 * token this service still believed was good.
 */
@Component
public class AdminTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(AdminTokenProvider.class);

    /** Refresh this far ahead of the token's stated expiry, not exactly at it. */
    private static final Duration REFRESH_SKEW = Duration.ofSeconds(30);

    private final RestClient authRestClient;
    private final ServiceAccountProperties credentials;

    private volatile CachedToken cached;

    public AdminTokenProvider(
            @Qualifier("authRestClient") RestClient authRestClient,
            ServiceAccountProperties credentials) {
        this.authRestClient = authRestClient;
        this.credentials = credentials;
    }

    /**
     * @return a value ready to place directly on an {@code Authorization} header
     * @throws WalletServiceUnavailableException if auth-service could not be reached or
     *                                           refused the service account's credentials;
     *                                           treated as an availability fault because,
     *                                           either way, no debit or credit can proceed
     */
    public String bearerToken() {
        return "Bearer " + tokenValue();
    }

    /**
     * Discards the cached token, forcing the next {@link #bearerToken()} call to log in again.
     *
     * <p>Called when wallet-service itself rejects a token this cache believed was still
     * valid — clock skew between services, or the account having been revoked mid-lifetime.
     */
    public synchronized void invalidate() {
        cached = null;
    }

    private synchronized String tokenValue() {
        CachedToken token = cached;
        if (token == null || Instant.now().isAfter(token.refreshAt())) {
            token = login();
            cached = token;
        }
        return token.accessToken();
    }

    private CachedToken login() {
        try {
            ServiceTokenResponse response = authRestClient.post()
                    .uri("/api/v1/auth/login")
                    .body(new ServiceLoginRequest(credentials.email(), credentials.password()))
                    .retrieve()
                    .body(ServiceTokenResponse.class);

            if (response == null || response.accessToken() == null) {
                throw new WalletServiceUnavailableException(
                        "auth-service returned no access token for the service account");
            }

            log.debug("Obtained a fresh service token, valid for {}s", response.expiresIn());
            Instant refreshAt = Instant.now()
                    .plusSeconds(response.expiresIn())
                    .minus(REFRESH_SKEW);
            return new CachedToken(response.accessToken(), refreshAt);
        } catch (RestClientException ex) {
            throw new WalletServiceUnavailableException(
                    "Could not obtain a service token from auth-service", ex);
        }
    }

    private record CachedToken(String accessToken, Instant refreshAt) {
    }
}
