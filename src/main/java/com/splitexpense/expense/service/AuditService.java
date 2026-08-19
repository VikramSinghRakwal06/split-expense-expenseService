package com.splitexpense.expense.service;

import com.splitexpense.expense.entity.AuditLog;
import com.splitexpense.expense.repository.AuditLogRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the append-only audit trail, for both expenses and settlements.
 *
 * <h2>Why {@code REQUIRES_NEW}</h2>
 *
 * <p>Every write here runs in its own transaction, independent of whatever transaction is
 * driving the saga in {@link ExpenseService} or {@link SettlementService}. That is deliberate
 * and load-bearing: when an expense fails and its own transaction rolls back, an audit row
 * written in the <em>same</em> transaction would roll back with it — the trail would be silent
 * about exactly the events most worth reconstructing afterwards. {@code REQUIRES_NEW} suspends
 * the caller's transaction for the duration of the write and commits independently, so the
 * entry survives regardless of what the caller ultimately does.
 *
 * <p>This is only called from a bean other than itself, which is what lets Spring's proxy
 * intercept the call and apply the propagation — see group-service's {@code WalletService} for
 * the same reasoning about self-invocation.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Appends one entry to an expense's or settlement's trail.
     *
     * @param subjectId the expense or settlement the event belongs to; null for an event that
     *                  happens before that row exists (e.g. a rejected request)
     * @param event     short machine-readable label, e.g. {@code EXPENSE_INITIATED}
     * @param details   free-text context, safe to log; never a group balance
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID subjectId, String event, String details) {
        AuditLog auditLog = AuditLog.builder()
                .subjectId(subjectId)
                .event(event)
                .details(details)
                .build();
        auditLogRepository.save(auditLog);
        log.debug("Audit: {} for {} — {}", event, subjectId, details);
    }
}
