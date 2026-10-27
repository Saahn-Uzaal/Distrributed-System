package com.ridehailing.trip.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * T13/T16 — topology declare bởi service khi khởi động (events.md §1): Spring AMQP declarables,
 * RabbitAdmin auto-configured declare idempotent mỗi lần start.
 *
 * trip-service là producer của TripRequested (§3.1) VÀ consumer của DriverProposed/NoDriverFound
 * (§3.2/§3.3) → declare exchange + inbox queue {@code trip.inbox} (bind {@code matching.#} và
 * {@code payment.#}) + DLQ queue CỦA MÌNH; không declare thay service khác (matching.inbox thuộc
 * matching-service, payment.inbox thuộc payment-service). PaymentSucceeded/PaymentFailed tới
 * trip.inbox trước T24 sẽ bị republish DLQ với {@code x-dlq-reason: unsupported_event_type} —
 * đúng contract (§2), không cần xử lý đặc biệt.
 */
@Configuration
public class RabbitTopologyConfig {

    public static final String EVENTS_EXCHANGE = "ride.events";
    public static final String DLX_EXCHANGE = "ride.events.dlx";
    public static final String TRIP_INBOX_QUEUE = "trip.inbox";
    public static final String TRIP_DLQ_QUEUE = "trip.dlq";

    /** Routing keys trip-service consume (events.md §1: trip.inbox ← matching.#, payment.#). */
    public static final String MATCHING_EVENTS_ROUTING_KEY = "matching.#";
    public static final String PAYMENT_EVENTS_ROUTING_KEY = "payment.#";

    @Bean
    public TopicExchange rideEventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false); // topic, durable, không auto-delete
    }

    @Bean
    public TopicExchange rideEventsDlxExchange() {
        return new TopicExchange(DLX_EXCHANGE, true, false);
    }

    /** Inbox durable; dead-letter sang ride.events.dlx với rk trip.dlq (events.md §1). */
    @Bean
    public Queue tripInboxQueue() {
        Map<String, Object> args = Map.of(
                "x-dead-letter-exchange", DLX_EXCHANGE,
                "x-dead-letter-routing-key", TRIP_DLQ_QUEUE);
        return new Queue(TRIP_INBOX_QUEUE, true, false, false, args);
    }

    @Bean
    public Binding tripInboxMatchingBinding() {
        return BindingBuilder.bind(tripInboxQueue()).to(rideEventsExchange()).with(MATCHING_EVENTS_ROUTING_KEY);
    }

    @Bean
    public Binding tripInboxPaymentBinding() {
        return BindingBuilder.bind(tripInboxQueue()).to(rideEventsExchange()).with(PAYMENT_EVENTS_ROUTING_KEY);
    }

    /** DLQ của service này (events.md §1): durable, bind vào ride.events.dlx với rk = tên queue. */
    @Bean
    public Queue tripDlqQueue() {
        return new Queue(TRIP_DLQ_QUEUE, true, false, false);
    }

    @Bean
    public Binding tripDlqBinding() {
        return BindingBuilder.bind(tripDlqQueue()).to(rideEventsDlxExchange()).with(TRIP_DLQ_QUEUE);
    }
}
