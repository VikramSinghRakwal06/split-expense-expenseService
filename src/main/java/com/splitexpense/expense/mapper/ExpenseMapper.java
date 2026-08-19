package com.splitexpense.expense.mapper;

import com.splitexpense.expense.dto.response.ExpenseResponse;
import com.splitexpense.expense.dto.response.ExpenseSplitResponse;
import com.splitexpense.expense.entity.Expense;
import com.splitexpense.expense.entity.ExpenseSplit;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Converts {@link Expense} and {@link ExpenseSplit} entities into their client-facing
 * representations.
 *
 * <p>Kept as an explicit component rather than a mapping framework, mirroring group-service's
 * {@code GroupMapper}: hand-written so that excluding {@code version} is an obvious choice
 * rather than a generator's default.
 */
@Component
public class ExpenseMapper {

    /** Deterministic output order, so two reads of an unchanged expense produce identical
     *  JSON regardless of what order the repository happened to return rows in. */
    private static final Comparator<ExpenseSplit> BY_USER_ID =
            Comparator.comparing(ExpenseSplit::getUserId);

    /**
     * @param expense entity to convert, never null
     * @param splits  the expense's splits, in any order
     * @return the safe-to-serialise view of that expense
     */
    public ExpenseResponse toResponse(Expense expense, List<ExpenseSplit> splits) {
        List<ExpenseSplitResponse> splitViews = splits.stream()
                .sorted(BY_USER_ID)
                .map(split -> new ExpenseSplitResponse(split.getUserId(), split.getShareAmount()))
                .toList();

        return new ExpenseResponse(
                expense.getId(),
                expense.getGroupId(),
                expense.getPayerUserId(),
                expense.getAmount(),
                expense.getCurrency(),
                expense.getDescription(),
                expense.getSplitType(),
                expense.getStatus(),
                expense.getFailureReason(),
                splitViews,
                expense.getCreatedAt(),
                expense.getUpdatedAt());
    }
}
