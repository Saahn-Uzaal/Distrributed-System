package com.ridehailing.matching.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * T14 — topology declare bởi service khi khởi động (events.md §1): Spring AMQP declarables,
 * RabbitAdmin declare idempotent trên kết nối đầu tiên. matching-service là consumer của
 * TripRequested (§3.1) → chỉ declare exchange + inbox queue + DLQ queue CỦA MÌNH; không
 * declare thay service khác (trip.inbox/payment.inbox thuộc trip-service/payment-service).
 */
@Configuration
public class RabbitTopologyConfig {

    public static final String EVENTS_EXCHANGE = "ride.events";
    public static final String DLX_EXCHANGE = "ride.events.dlx";
    public static final String MATCHING_INBOX_QUEUE = "matching.inbox";
    public static final String MATCHING_DLQ_QUEUE = "matching.dlq";

    /** Routing key duy nhất matching-service consume (events.md §1: matching.inbox ← trip.requested). */
    public static final String TRIP_REQUESTED_ROUTING_KEY = "trip.requested";

    @Bean
    public TopicExchange rideEventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false); // topic, durable, không auto-delete
    }

    @Bean
    public TopicExchange rideEventsDlxExchange() {
        return new TopicExchange(DLX_EXCHANGE, true, false);
    }

    /** Inbox durable; dead-letter sang ride.events.dlx với rk matching.dlq (events.md §1). */
    @Bean
    public Queue matchingInboxQueue() {
        Map<String, Object> args = Map.of(
                "x-dead-letter-exchange", DLX_EXCHANGE,
                "x-dead-letter-routing-key", MATCHING_DLQ_QUEUE);
        return new Queue(MATCHING_INBOX_QUEUE, true, false, false, args);
    }

    @Bean
    public Binding matchingInboxBinding() {
        return BindingBuilder.bind(matchingInboxQueue()).to(rideEventsExchange()).with(TRIP_REQUESTED_ROUTING_KEY);
    }

    /** DLQ của service này (events.md §1): durable, bind vào ride.events.dlx với rk = tên queue. */
    @Bean
    public Queue matchingDlqQueue() {
        return new Queue(MATCHING_DLQ_QUEUE, true, false, false);
    }

    @Bean
    public Binding matchingDlqBinding() {
        return BindingBuilder.bind(matchingDlqQueue()).to(rideEventsDlxExchange()).with(MATCHING_DLQ_QUEUE);
    }
}
