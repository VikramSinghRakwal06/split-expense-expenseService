package com.splitexpense.expense.repository;

import com.splitexpense.expense.entity.ExpenseSplit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for {@link ExpenseSplit}.
 *
 * <p>Full {@code JpaRepository} surface: splits are written once when an expense is recorded
 * and read back whole every time that expense is displayed.
 */
@Repository
public interface ExpenseSplitRepository extends JpaRepository<ExpenseSplit, UUID> {

    /**
     * All of one expense's splits, in no guaranteed order — {@code ExpenseMapper} sorts them
     * for a stable response.
     *
     * @param expenseId the expense to read
     * @return its splits
     */
    List<ExpenseSplit> findByExpenseId(UUID expenseId);
}
