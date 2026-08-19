package com.splitexpense.expense.mapper;

import com.splitexpense.expense.dto.response.SettlementResponse;
import com.splitexpense.expense.entity.Settlement;
import org.springframework.stereotype.Component;

/**
 * Converts {@link Settlement} entities into their client-facing representation.
 */
@Component
public class SettlementMapper {

    /**
     * @param settlement entity to convert, never null
     * @return the safe-to-serialise view of that settlement
     */
    public SettlementResponse toResponse(Settlement settlement) {
        return new SettlementResponse(
                settlement.getId(),
                settlement.getGroupId(),
                settlement.getFromUserId(),
                settlement.getToUserId(),
                settlement.getAmount(),
                settlement.getCurrency(),
                settlement.getStatus(),
                settlement.getFailureReason(),
                settlement.getNote(),
                settlement.getCreatedAt(),
                settlement.getUpdatedAt());
    }
}
