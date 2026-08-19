package com.splitexpense.expense.client.dto;

import java.util.UUID;

/**
 * Mirrors group-service's {@code ApplyBalancesResponse}. Only {@code applied} is read by this
 * service — it is the field that tells {@code ExpenseService} and {@code SettlementService}
 * whether this call did the work or found it already done — but {@code balances} is declared
 * too so the response deserialises without relying on unknown-property leniency.
 */
public record ApplyBalancesResult(
        UUID groupId, String referenceId, boolean applied, Object balances) {
}
