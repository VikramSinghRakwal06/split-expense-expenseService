package com.payflow.payment.repository;

import com.payflow.payment.entity.Transaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for {@link Transaction}.
 *
 * <p>Full {@code JpaRepository} surface, unlike {@code AuditLogRepository}: a transaction row
 * is mutable while its saga runs, advancing through {@code TransactionStatus} as the debit
 * and credit legs complete.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Resolves the transaction a given {@code Idempotency-Key} produced, if any.
     *
     * <p>Backed by {@code uq_transactions_idempotency_key}, so this is an index lookup. Used
     * to serve a replay once the transfer has reached a terminal state; the arbiter of
     * <em>first-writer-wins</em> is still the unique constraint itself, not this read.
     *
     * @param idempotencyKey the client-supplied key
     * @return the transaction it produced, or empty if the key has never been used
     */
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * One page of a wallet's transfer history, on either side, newest first.
     *
     * <p>Passing the same wallet id for both parameters lets PostgreSQL serve the OR from a
     * bitmap-or of {@code idx_transactions_sender_created} and
     * {@code idx_transactions_receiver_created} — see the migration for why there is no
     * single composite index instead.
     *
     * @param senderWalletId   wallet id to match as sender
     * @param receiverWalletId wallet id to match as receiver, normally identical to
     *                         {@code senderWalletId}
     * @param pageable         page number, size and sort (expected: {@code createdAt} desc)
     * @return the requested page
     */
    Page<Transaction> findBySenderWalletIdOrReceiverWalletId(
            UUID senderWalletId, UUID receiverWalletId, Pageable pageable);
}
