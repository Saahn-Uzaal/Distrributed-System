package com.ridehailing.payment.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * T23 — topology declare bởi service khi khởi động (events.md §1): Spring AMQP declarables,
 * RabbitAdmin declare idempotent trên kết nối đầu tiên. payment-service là consumer của
 * TripCompleted (§3.4) → chỉ declare exchange + inbox queue + DLQ queue CỦA MÌNH; không
 * declare thay service khác (trip.inbox/matching.inbox thuộc trip-service/matching-service).
 */
@Configuration
public class RabbitTopologyConfig {

    public static final String EVENTS_EXCHANGE = "ride.events";
    public static final String DLX_EXCHANGE = "ride.events.dlx";
    public static final String PAYMENT_INBOX_QUEUE = "payment.inbox";
    public static final String PAYMENT_DLQ_QUEUE = "payment.dlq";

    /** Routing key duy nhất payment-service consume (events.md §1: payment.inbox ← trip.completed). */
    public static final String TRIP_COMPLETED_ROUTING_KEY = "trip.completed";

    @Bean
    public TopicExchange rideEventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false); // topic, durable, không auto-delete
    }

    @Bean
    public TopicExchange rideEventsDlxExchange() {
        return new TopicExchange(DLX_EXCHANGE, true, false);
    }

    /** Inbox durable; dead-letter sang ride.events.dlx với rk payment.dlq (events.md §1). */
    @Bean
    public Queue paymentInboxQueue() {
        Map<String, Object> args = Map.of(
                "x-dead-letter-exchange", DLX_EXCHANGE,
                "x-dead-letter-routing-key", PAYMENT_DLQ_QUEUE);
        return new Queue(PAYMENT_INBOX_QUEUE, true, false, false, args);
    }

    @Bean
    public Binding paymentInboxBinding() {
        return BindingBuilder.bind(paymentInboxQueue()).to(rideEventsExchange()).with(TRIP_COMPLETED_ROUTING_KEY);
    }

    /** DLQ của service này (events.md §1): durable, bind vào ride.events.dlx với rk = tên queue. */
    @Bean
    public Queue paymentDlqQueue() {
        return new Queue(PAYMENT_DLQ_QUEUE, true, false, false);
    }

    @Bean
    public Binding paymentDlqBinding() {
        return BindingBuilder.bind(paymentDlqQueue()).to(rideEventsDlxExchange()).with(PAYMENT_DLQ_QUEUE);
    }
}
