package com.payflow.payment.client.dto;

/**
 * Mirrors auth-service's {@code AuthResponse} field for field — see {@link WalletBalanceView}
 * for why the full shape is declared rather than just the two fields ({@code accessToken},
 * {@code expiresIn}) this service actually reads. {@code user} is left as {@code Object};
 * nothing here ever inspects the authenticated account's profile.
 */
public record ServiceTokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        Object user) {
}
