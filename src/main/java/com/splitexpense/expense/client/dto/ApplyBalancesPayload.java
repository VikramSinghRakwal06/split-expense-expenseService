package com.splitexpense.expense.client.dto;

import java.util.List;

/**
 * Outbound payload for {@code POST /api/v1/groups/{groupId}/balances:apply} against
 * group-service. Mirrors group-service's {@code ApplyBalancesRequest} field for field — see
 * {@link ServiceLoginRequest} for why this is a structural duplicate rather than a shared
 * dependency.
 *
 * @param referenceId this service's expense or settlement id; group-service deduplicates by
 *                     it, which is what makes a retried apply safe
 * @param reason       {@code "EXPENSE"}, {@code "EXPENSE_VOID"} or {@code "SETTLEMENT"} —
 *                      passed as the raw string group-service's own enum accepts
 * @param description  human-readable note, recorded on every resulting ledger entry
 * @param deltas       the debts to apply; all commit together or none do
 */
public record ApplyBalancesPayload(
        String referenceId, String reason, String description, List<BalanceDeltaPayload> deltas) {
}
