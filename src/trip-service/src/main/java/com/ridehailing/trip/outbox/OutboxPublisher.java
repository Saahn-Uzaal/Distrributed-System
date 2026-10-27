package com.ridehailing.trip.outbox;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ReturnCallback;
import com.rabbitmq.client.ReturnListener;
import com.ridehailing.trip.config.RabbitTopologyConfig;
import com.ridehailing.trip.repo.OutboxRepository;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.RabbitUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * T13 — outbox publisher (events.md §1/§4.6): publish message **persistent** với
 * **mandatory=true**, chờ **publisher confirm** rồi mới cho phép đánh dấu SENT.
 * Message bị broker returned (không có binding khớp routing key) → KHÔNG coi là đã gửi:
 * giữ PENDING + ghi last_error, worker retry chu kỳ kế tiếp (§1).
 * Broker down / lỗi mạng → PublishOutcome.fail; row giữ PENDING, app không chết.
 */
@Component
public class OutboxPublisher {

    /** Kết quả publish một row outbox: sent=true chỉ khi broker confirm ACK và không returned. */
    public record PublishOutcome(boolean sent, String error) {
        static PublishOutcome ok() { return new PublishOutcome(true, null); }
        static PublishOutcome fail(String error) { return new PublishOutcome(false, error); }
    }

    private final CachingConnectionFactory connectionFactory;
    private final ObjectMapper json;
    private final long confirmTimeoutMs;

    public OutboxPublisher(CachingConnectionFactory connectionFactory, ObjectMapper json,
                           @Value("${app.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.connectionFactory = connectionFactory;
        this.json = json;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    public PublishOutcome publish(OutboxRepository.OutboxRow row) {
        byte[] body;
        try {
            body = envelopeBytes(row);
        } catch (RuntimeException e) {
            // Payload hỏng (không parse/serialize được): KHÔNG ném ra worker — trả lỗi để row giữ
            // PENDING + last_error và bị backoff, không chặn các row phía sau (poison message).
            return PublishOutcome.fail("envelope build failed: " + describe(e));
        }
        String messageId = row.id().toString();
        try (var connection = connectionFactory.createConnection()) {
            // Channel KHÔNG transactional: spring-rabbit 4.x coi tham số createChannel(boolean)
            // là cờ transactional — true sẽ txSelect() trước và chặn confirmSelect()
            // ("cannot switch from tx to confirm mode"). Confirm + return listener tự quản.
            var channel = connection.createChannel(false);
            AtomicReference<com.rabbitmq.client.Return> returnedRef = new AtomicReference<>();
            // Chỉ nhận return của CHÍNH message này (khớp messageId = eventId).
            ReturnCallback onReturn = r -> {
                if (r.getProperties() != null && messageId.equals(r.getProperties().getMessageId())) {
                    returnedRef.set(r);
                }
            };
            ReturnListener listener = null;
            try {
                channel.confirmSelect();
                listener = channel.addReturnListener(onReturn);

                // mandatory=true: broker trả về message nếu không có binding khớp (events.md §1).
                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                        .deliveryMode(2) // PERSISTENT
                        .contentType("application/json")
                        .messageId(messageId)          // = eventId, consumer có thể dedup ở tầng AMQP
                        .correlationId(row.correlationId().toString())
                        .type(row.eventType())
                        .build();
                channel.basicPublish(RabbitTopologyConfig.EVENTS_EXCHANGE, row.routingKey(), true, props, body);

                boolean confirmed;
                try {
                    confirmed = channel.waitForConfirms(confirmTimeoutMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return PublishOutcome.fail("interrupted while waiting for publisher confirm");
                } catch (TimeoutException e) {
                    return PublishOutcome.fail("confirm timeout after " + confirmTimeoutMs + " ms");
                }
                if (!confirmed) {
                    return PublishOutcome.fail("publisher nack or no confirm within " + confirmTimeoutMs + " ms");
                }

                // RabbitMQ gửi basic.return TRƯỚC basic.ack cho message mandatory không route được,
                // trên cùng channel và client xử lý frame theo thứ tự — khi waitForConfirms trả về,
                // return (nếu có) đã được listener ghi nhận. Không cần chờ thêm.
                com.rabbitmq.client.Return r = returnedRef.get();
                if (r != null) {
                    return PublishOutcome.fail("returned by broker replyCode=" + r.getReplyCode()
                            + " replyText=" + r.getReplyText());
                }
                return PublishOutcome.ok();
            } finally {
                // Channel được CachingConnectionFactory cache lại: phải gỡ listener, nếu không mỗi lần
                // publish lại cộng dồn một listener trên cùng channel.
                if (listener != null) {
                    channel.removeReturnListener(listener);
                }
                RabbitUtils.closeChannel(channel);
            }
        } catch (Exception e) {
            // Broker down / lỗi mạng: giữ PENDING, worker retry chu kỳ kế tiếp.
            return PublishOutcome.fail(describe(e));
        }
    }

    /** Envelope chuẩn events.md §2 — thứ tự field theo spec, eventId = outbox.id ổn định qua mọi lần publish. */
    private byte[] envelopeBytes(OutboxRepository.OutboxRow row) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", row.id().toString());
        env.put("eventType", row.eventType());
        env.put("schemaVersion", row.schemaVersion());
        env.put("occurredAt", row.occurredAt().toString()); // ISO-8601 UTC
        env.put("producer", row.producer());
        env.put("correlationId", row.correlationId().toString());
        if (row.causationId() != null) {
            env.put("causationId", row.causationId().toString());
        }
        env.put("aggregateId", row.aggregateId().toString());
        env.put("aggregateVersion", row.aggregateVersion());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = json.readValue(row.payloadJson(), Map.class); // jsonb: thứ tự key do PostgreSQL chuẩn hóa
        env.put("payload", payload);
        try {
            return json.writeValueAsBytes(env);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize outbox envelope for eventId=" + row.id(), e);
        }
    }

    private static String describe(Exception e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage());
    }
}
