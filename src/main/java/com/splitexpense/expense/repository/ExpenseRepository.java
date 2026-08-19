package com.splitexpense.expense.repository;

import com.splitexpense.expense.entity.Expense;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for {@link Expense}.
 *
 * <p>Full {@code JpaRepository} surface, unlike {@code AuditLogRepository}: an expense row is
 * mutable while its saga runs, advancing through {@code ExpenseStatus}.
 */
@Repository
public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    /**
     * Resolves the expense a given {@code Idempotency-Key} produced, if any.
     *
     * <p>Backed by {@code uq_expenses_idempotency_key}, so this is an index lookup. Used to
     * serve a replay once the expense has reached a terminal state; the arbiter of
     * <em>first-writer-wins</em> is still the unique constraint itself, not this read.
     *
     * @param idempotencyKey the client-supplied key
     * @return the expense it produced, or empty if the key has never been used
     */
    Optional<Expense> findByIdempotencyKey(String idempotencyKey);

    /**
     * One page of a group's expenses, newest first.
     *
     * @param groupId  group whose expenses are being read
     * @param pageable page number, size and sort (expected: {@code createdAt} desc)
     * @return the requested page
     */
    Page<Expense> findByGroupId(UUID groupId, Pageable pageable);

    /**
     * One page of the expenses a user paid, newest first. Serves "expenses I'm involved in"
     * from the payer side; a participant-only view would need a join through
     * {@code expense_splits} and is left for when the product actually needs it.
     *
     * @param payerUserId user to match as payer
     * @param pageable    page number, size and sort (expected: {@code createdAt} desc)
     * @return the requested page
     */
    Page<Expense> findByPayerUserId(UUID payerUserId, Pageable pageable);
}
