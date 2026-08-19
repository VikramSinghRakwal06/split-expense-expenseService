package com.splitexpense.expense.client.dto;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors group-service's {@code GroupBalancesResponse} field for field — see {@link
 * GroupView}'s javadoc for why the shape must match exactly rather than declaring only the
 * fields this service reads.
 */
public record GroupBalancesView(
        UUID groupId,
        String currency,
        List<PairBalanceView> pairs,
        List<MemberNetPositionView> netPositions) {

    /**
     * What one member currently owes another, or zero if the pair has settled or the debt
     * runs the other way.
     *
     * @param debtorId   the member who would be paying
     * @param creditorId the member who would be receiving
     * @return the amount {@code debtorId} owes {@code creditorId} right now
     */
    public java.math.BigDecimal owed(UUID debtorId, UUID creditorId) {
        return pairs.stream()
                .filter(p -> p.debtorId().equals(debtorId) && p.creditorId().equals(creditorId))
                .map(PairBalanceView::amount)
                .findFirst()
                .orElse(java.math.BigDecimal.ZERO);
    }
}
