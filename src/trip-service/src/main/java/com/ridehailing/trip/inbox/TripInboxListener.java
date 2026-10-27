package com.ridehailing.trip.inbox;

import com.rabbitmq.client.Channel;
import com.ridehailing.trip.config.RabbitTopologyConfig;
import com.ridehailing.trip.event.EventEnvelope;
import com.ridehailing.trip.event.InboxMessageValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * T16 — inbox consumer trip-service (events.md §4):
 * <ul>
 *   <li>validate (§2): malformed/unsupported → republish DLQ kèm {@code x-dlq-reason} + ACK gốc,
 *       KHÔNG retry như lỗi mạng;</li>
 *   <li>dedup insert {@code (consumer_name, event_id)} trong transaction cùng handler; duplicate
 *       → ACK an toàn, không thực thi lại (§4.1);</li>
 *   <li>ACK chỉ sau commit (§4.2) — rollback → không ACK → redelivery;</li>
 *   <li>transient failure: retry in-process có giới hạn (mặc định 3 lần, backoff env), hết lượt
 *       → {@code reject(requeue=false)} → DLQ; message vào DLQ theo cách này chỉ mang
 *       {@code x-death}, không có {@code x-dlq-reason} (§4.3/§6b).</li>
 * </ul>
 * Stale-event guard (§4.4) được implement trong handler (TripInboxHandler) — cần state trip hiện tại.
 */
@Component
public class TripInboxListener {

    private static final Logger log = LoggerFactory.getLogger(TripInboxListener.class);

    private final InboxMessageValidator validator;
    private final InboxEventRepository inboxRepo;
    private final InboxEventHandler handler;
    private final DlqRepublisher dlqRepublisher;
    private final TransactionTemplate txTemplate;
    private final String consumerName;
    private final int maxRetries;
    private final long[] backoffMs;

    public TripInboxListener(InboxMessageValidator validator,
                            InboxEventRepository inboxRepo,
                            InboxEventHandler handler,
                            DlqRepublisher dlqRepublisher,
                            PlatformTransactionManager txManager,
                            @Value("${app.inbox.consumer-name:trip-service}") String consumerName,
                            @Value("${app.inbox.max-retries:3}") int maxRetries,
                            @Value("${app.inbox.retry-backoff-ms:1000,3000,10000}") String backoffCsv) {
        this.validator = validator;
        this.inboxRepo = inboxRepo;
        this.handler = handler;
        this.dlqRepublisher = dlqRepublisher;
        this.txTemplate = new TransactionTemplate(txManager);
        this.consumerName = consumerName;
        this.maxRetries = Math.max(0, maxRetries);
        this.backoffMs = parseBackoff(backoffCsv);
    }

    private static long[] parseBackoff(String csv) {
        String[] parts = csv.split(",");
        long[] ms = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                ms[i] = Math.max(0, Long.parseLong(parts[i].trim()));
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "app.inbox.retry-backoff-ms must be a comma-separated list of milliseconds: " + csv);
            }
        }
        return ms;
    }

    // spring-rabbit 4.x: ackMode (không phải acknowledge); delivery tag nằm trong
    // MessageProperties.getDeliveryTag() (amqp-client 5 không còn Channel.getDeliveryTag()).
    @RabbitListener(queues = RabbitTopologyConfig.TRIP_INBOX_QUEUE, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) throws IOException {
        long startNanos = System.nanoTime();
        final long deliveryTag = message.getMessageProperties().getDeliveryTag();
        // MDC structured log (spec §11): correlationId từ AMQP property; tripId sau validate.
        String correlationId = message.getMessageProperties().getCorrelationId();
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put("correlationId", correlationId);
        try {
            process(message, channel, deliveryTag);
        } catch (RuntimeException e) {
            // Phòng thủ: không để exception thoát listener — reject vào DLQ (§4.3: không requeue vô hạn).
            log.error("inbox processing failed unexpectedly; rejecting to DLQ", e);
            MDC.put("eventType", "dlq_rejected");
            MDC.put("result", "failure");
            channel.basicNack(deliveryTag, false, false);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            MDC.put("durationMs", String.valueOf(durationMs));
            MDC.clear();
        }
    }

    private void process(Message message, Channel channel, long deliveryTag) throws IOException {
        byte[] body = message.getBody();
        var props = message.getMessageProperties();

        // 1) Validate (events.md §2): malformed/unsupported → republish DLQ + ACK gốc. Không retry.
        InboxMessageValidator.ValidationResult vr = validator.validate(body);
        if (!vr.ok()) {
            boolean republished = dlqRepublisher.republish(body, props, vr.reason());
            if (republished) {
                MDC.put("eventType", "inbox_dlq");
                MDC.put("result", "success");
                log.info("event rejected to DLQ reason={} messageId={}", vr.reason(), props.getMessageId());
                channel.basicAck(deliveryTag, false);
            } else {
                // Fallback: broker dead-letter message gốc (chỉ x-death, không có x-dlq-reason).
                MDC.put("eventType", "dlq_rejected");
                MDC.put("result", "failure");
                log.error("DLQ republish failed; rejecting original messageId={}", props.getMessageId());
                channel.basicNack(deliveryTag, false, false);
            }
            return;
        }

        EventEnvelope envelope = vr.envelope();
        // M4: correlationId lấy từ envelope (events.md §5 — consumer log bằng event.correlationId); AMQP
        // property chỉ là gợi ý trước khi validate (thiếu thì trước đây sinh UUID ngẫu nhiên → mất truy vết).
        MDC.put("correlationId", envelope.correlationId().toString());
        MDC.put("tripId", envelope.aggregateId().toString());

        // 2) Dedup + handler trong một transaction (§4.1/§4.2). Retry bounded khi transient fail (§4.3).
        for (int attempt = 1; ; attempt++) {
            try {
                boolean duplicate = txTemplate.execute(status -> {
                    boolean inserted = inboxRepo.insertIfAbsent(
                            consumerName, envelope.eventId(), envelope.eventType(),
                            envelope.correlationId(), envelope.aggregateId(),
                            new String(body, StandardCharsets.UTF_8));
                    if (!inserted) {
                        return true; // duplicate → không thực thi lại handler
                    }
                    handler.handle(envelope);
                    return false;
                });
                if (duplicate) {
                    MDC.put("eventType", "inbox_duplicate");
                    MDC.put("result", "success");
                    log.info("duplicate event skipped eventId={} deliveryAttempt={}", envelope.eventId(), attempt);
                    channel.basicAck(deliveryTag, false);
                } else {
                    MDC.put("eventType", "inbox_processed");
                    MDC.put("result", "success");
                    log.info("event processed eventId={} eventType={} tripId={}",
                            envelope.eventId(), envelope.eventType(), envelope.aggregateId());
                    channel.basicAck(deliveryTag, false);
                }
                return;
            } catch (RuntimeException e) {
                // Rollback: không có inbox row commit → redelivery/retry xử lý lại từ đầu.
                if (attempt > maxRetries) {
                    MDC.put("eventType", "inbox_dlq");
                    MDC.put("result", "retry_exhausted");
                    log.error("event failed after {} attempts; rejecting to DLQ eventId={}", attempt, envelope.eventId(), e);
                    channel.basicNack(deliveryTag, false, false);
                    return;
                }
                long backoff = backoffMs[Math.min(attempt - 1, backoffMs.length - 1)];
                MDC.put("eventType", "inbox_retry");
                MDC.put("result", "failure");
                log.warn("event processing failed attempt={}; retrying in {} ms eventId={}",
                        attempt, backoff, envelope.eventId(), e);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during retry backoff", ie);
                }
            }
        }
    }
}
