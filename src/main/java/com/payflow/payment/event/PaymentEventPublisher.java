package com.payflow.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes the two payment events notification-service consumes.
 *
 * <p>Fire-and-forget, deliberately: a transfer has already reached a terminal, persisted
 * outcome by the time either method here is called (see {@code PaymentService}), so a
 * notification that never arrives is a degraded experience, not a lost transfer. Failing
 * the request over an event Kafka couldn't accept would make notification-service's
 * availability a dependency of the transfer path, which it should not be.
 */
@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private static final String TOPIC_COMPLETED = "payment.completed";
    private static final String TOPIC_FAILED = "payment.failed";

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public PaymentEventPublisher(KafkaTemplate<Object, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishCompleted(
            UUID transactionId,
            UUID senderUserId,
            UUID receiverUserId,
            BigDecimal amount,
            String currency) {

        PaymentCompletedEvent event = new PaymentCompletedEvent(
                UUID.randomUUID(), transactionId, senderUserId, receiverUserId, amount,
                currency, Instant.now());

        send(TOPIC_COMPLETED, transactionId, event);
    }

    public void publishFailed(
            UUID transactionId, UUID senderUserId, BigDecimal amount, String reason) {

        PaymentFailedEvent event = new PaymentFailedEvent(
                UUID.randomUUID(), transactionId, senderUserId, amount, reason, Instant.now());

        send(TOPIC_FAILED, transactionId, event);
    }

    private void send(String topic, UUID key, Object event) {
        // Keyed by transactionId so any retry/reversal events for the same transfer land on
        // the same partition and are consumed in order.
        kafkaTemplate.send(topic, key.toString(), event)
                .exceptionally(ex -> {
                    log.warn("Failed to publish to {} for transaction {}: {}",
                            topic, key, ex.getMessage());
                    return null;
                });
    }
}
