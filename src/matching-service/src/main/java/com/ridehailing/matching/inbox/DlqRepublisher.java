package com.ridehailing.matching.inbox;

import com.ridehailing.matching.config.RabbitTopologyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * T14 — republish chủ động vào DLQ (events.md §2/§6a): event malformed/unsupported được
 * republish body gốc sang {@code ride.events.dlx} với rk = tên DLQ của queue này, kèm header
 * {@code x-dlq-reason}, rồi ACK message gốc. Broker dead-lettering không thể thêm header này
 * (§6) nên trường hợp malformed KHÔNG dùng reject(requeue=false).
 */
@Component
public class DlqRepublisher {

    public static final String DLQ_REASON_HEADER = "x-dlq-reason";

    private final RabbitTemplate rabbitTemplate;
    private final Logger log = LoggerFactory.getLogger(DlqRepublisher.class);

    public DlqRepublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
        // mandatory=true: broker trả về message nếu không có binding khớp → exception ở đây.
        this.rabbitTemplate.setMandatory(true);
    }

    /** Republish body gốc vào DLQ của service này. Trả về true khi broker nhận message. */
    public boolean republish(byte[] body, MessageProperties props, String reason) {
        MessageProperties newProps = new MessageProperties();
        // Giữ nguyên identity fields để replay khớp theo eventId/messageId (events.md §6).
        if (props.getMessageId() != null) {
            newProps.setMessageId(props.getMessageId());
        }
        if (props.getCorrelationId() != null) {
            newProps.setCorrelationId(props.getCorrelationId());
        }
        if (props.getType() != null) {
            newProps.setType(props.getType());
        }
        newProps.setContentType("application/json");
        newProps.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Map<String, Object> headers = props.getHeaders();
        if (headers != null) {
            for (Map.Entry<String, Object> e : headers.entrySet()) {
                // x-death là header broker thêm khi dead-letter — không copy sang.
                if (!"x-death".equals(e.getKey())) {
                    newProps.setHeader(e.getKey(), e.getValue());
                }
            }
        }
        newProps.setHeader(DLQ_REASON_HEADER, reason);
        try {
            rabbitTemplate.send(RabbitTopologyConfig.DLX_EXCHANGE, RabbitTopologyConfig.MATCHING_DLQ_QUEUE,
                    new Message(body, newProps));
            return true;
        } catch (AmqpException e) {
            log.error("DLQ republish failed for messageId={}: {}", props.getMessageId(), String.valueOf(e.getMessage()));
            return false;
        }
    }
}
