package com.splitexpense.expense.repository;

import com.splitexpense.expense.entity.AuditLog;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for the append-only {@link AuditLog} table.
 *
 * <p>Deliberately does not extend {@code JpaRepository}. It extends the bare {@code
 * Repository} marker instead, which contributes no methods at all, so this interface exposes
 * exactly the two operations declared below. There is no {@code delete}, no {@code
 * deleteAll}, no {@code saveAndFlush} that could rewrite an entry — the trail is corrected by
 * appending, never by editing or removing what was already written.
 */
@Repository
public interface AuditLogRepository
        extends org.springframework.data.repository.Repository<AuditLog, UUID> {

    /**
     * Appends one audit entry. The only write this interface permits.
     *
     * @param auditLog a new, never-before-persisted entry
     * @return the persisted instance, with its generated id and timestamp populated
     */
    AuditLog save(AuditLog auditLog);

    /**
     * Reconstructs an expense's or settlement's full history, oldest first, for support and
     * incident review.
     *
     * @param subjectId the expense or settlement to reconstruct
     * @param pageable  page number and size; sort is fixed to {@code createdAt} ascending by
     *                  {@code idx_audit_logs_subject_created} regardless of what is passed
     * @return the requested page of this subject's audit trail
     */
    Page<AuditLog> findBySubjectId(UUID subjectId, Pageable pageable);
}
