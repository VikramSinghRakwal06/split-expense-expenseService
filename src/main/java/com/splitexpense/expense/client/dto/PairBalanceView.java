package com.splitexpense.expense.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mirrors group-service's {@code PairBalanceResponse}: one outstanding debt, already restated
 * in the direction it actually runs, with a positive amount. A pair that has settled to zero
 * is never in this list at all — see group-service's {@code GroupBalanceMapper}.
 */
public record PairBalanceView(UUID debtorId, UUID creditorId, BigDecimal amount) {
}
