package com.splitexpense.expense.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Outbound mirror of group-service's {@code BalanceDeltaRequest}: one debt, in one direction,
 * as part of an {@link ApplyBalancesPayload}.
 *
 * @param debtorId   who came to owe money
 * @param creditorId who came to be owed it
 * @param amount     positive magnitude of the debt
 */
public record BalanceDeltaPayload(UUID debtorId, UUID creditorId, BigDecimal amount) {
}
