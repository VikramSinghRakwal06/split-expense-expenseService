package com.payflow.payment.client.dto;

import java.math.BigDecimal;

/**
 * Outbound payload for {@code POST /api/v1/wallets/{walletId}/debit} and {@code .../credit}
 * against wallet-service.
 *
 * <p>Field names and shape match wallet-service's {@code MoneyMovementRequest} exactly — see
 * {@link ServiceLoginRequest} for why this is a structural duplicate rather than a shared
 * dependency. {@code reference} is always this service's transaction id, so every movement
 * wallet-service records can be traced back to the transfer that caused it.
 */
public record WalletMovementRequest(BigDecimal amount, String reference, String description) {
}
