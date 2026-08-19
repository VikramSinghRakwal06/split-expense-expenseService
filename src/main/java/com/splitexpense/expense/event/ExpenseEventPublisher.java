package com.splitexpense.expense.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes the three events notification-service consumes.
 *
 * <p>Fire-and-forget, deliberately: an expense or settlement has already reached a terminal,
 * persisted outcome by the time any method here is called (see {@code ExpenseService} and
 * {@code SettlementService}), so a notification that never arrives is a degraded experience,
 * not a lost expense. Failing the request over an event Kafka couldn't accept would make
 * notification-service's availability a dependency of the recording path, which it should not
 * be.
 *
 * <p>Keyed by {@code groupId} rather than by expense or settlement id, unlike the payment
 * platform's transaction-keyed events: every event for one group should be consumed in order
 * relative to the others, since a group's activity feed and its unread-notification count are
 * both read as a sequence. Ordering across different groups never mattered.
 */
@Component
public class ExpenseEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ExpenseEventPublisher.class);

    private static final String TOPIC_EXPENSE_CREATED = "expense.created";
    private static final String TOPIC_EXPENSE_VOIDED = "expense.voided";
    private static final String TOPIC_SETTLEMENT_RECORDED = "settlement.recorded";

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public ExpenseEventPublisher(KafkaTemplate<Object, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishExpenseCreated(
            UUID expenseId,
            UUID groupId,
            UUID payerUserId,
            List<UUID> participantUserIds,
            BigDecimal amount,
            String currency,
            String description) {

        ExpenseCreatedEvent event = new ExpenseCreatedEvent(
                UUID.randomUUID(), expenseId, groupId, payerUserId, participantUserIds, amount,
                currency, description, Instant.now());

        send(TOPIC_EXPENSE_CREATED, groupId, event);
    }

    public void publishExpenseVoided(
            UUID expenseId,
            UUID groupId,
            List<UUID> participantUserIds,
            BigDecimal amount,
            String currency,
            String description) {

        ExpenseVoidedEvent event = new ExpenseVoidedEvent(
                UUID.randomUUID(), expenseId, groupId, participantUserIds, amount, currency,
                description, Instant.now());

        send(TOPIC_EXPENSE_VOIDED, groupId, event);
    }

    public void publishSettlementRecorded(
            UUID settlementId,
            UUID groupId,
            UUID fromUserId,
            UUID toUserId,
            BigDecimal amount,
            String currency) {

        SettlementRecordedEvent event = new SettlementRecordedEvent(
                UUID.randomUUID(), settlementId, groupId, fromUserId, toUserId, amount, currency,
                Instant.now());

        send(TOPIC_SETTLEMENT_RECORDED, groupId, event);
    }

    private void send(String topic, UUID key, Object event) {
        kafkaTemplate.send(topic, key.toString(), event)
                .exceptionally(ex -> {
                    log.warn("Failed to publish to {} for group {}: {}", topic, key, ex.getMessage());
                    return null;
                });
    }
}
