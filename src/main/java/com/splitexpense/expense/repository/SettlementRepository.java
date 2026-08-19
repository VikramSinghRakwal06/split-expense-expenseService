package com.splitexpense.expense.repository;

import com.splitexpense.expense.entity.Settlement;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for {@link Settlement}.
 */
@Repository
public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    /**
     * Resolves the settlement a given {@code Idempotency-Key} produced, if any. Backed by
     * {@code uq_settlements_idempotency_key}.
     *
     * @param idempotencyKey the client-supplied key
     * @return the settlement it produced, or empty if the key has never been used
     */
    Optional<Settlement> findByIdempotencyKey(String idempotencyKey);

    /**
     * One page of a group's settlements, newest first.
     *
     * @param groupId  group whose settlements are being read
     * @param pageable page number, size and sort (expected: {@code createdAt} desc)
     * @return the requested page
     */
    Page<Settlement> findByGroupId(UUID groupId, Pageable pageable);
}
