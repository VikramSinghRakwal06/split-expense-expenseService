package com.payflow.payment.repository;

import com.payflow.payment.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for {@link IdempotencyRecord}.
 *
 * <p>Full {@code JpaRepository} surface: a row is inserted with no reply, then updated once
 * the transfer it guards reaches a terminal state.
 */
@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {
}
