package com.ridehailing.payment;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.ridehailing.payment.config.RabbitTopologyConfig;
import com.ridehailing.payment.inbox.DlqRepublisher;
import com.ridehailing.payment.inbox.PaymentInboxListener;
import com.ridehailing.payment.service.TripCompletedHandler;
import com.ridehailing.paymenttest.FaultInjectionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T23 — payment consumer (spec §20.5) trên PostgreSQL thật + RabbitMQ thật (Testcontainers):
 * consume TripCompleted → thu tiền giả lập (success/failure cấu hình dev) → payments unique
 * (trip_id, attempt) + outbox PaymentSucceeded/PaymentFailed.
 *
 * <p>Acceptance spec §20.5: "Duplicate TripCompleted không thu trùng; success/failure đều có
 * event đúng; amount/currency validation; transaction rollback an toàn." events.md §2/§3.4–§3.6/§4/§5 là contract.
 */
@SpringBootTest(classes = {PaymentServiceApplication.class, FaultInjectionConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
// Đóng context (listener container + outbox worker) khi xong class, không để consumer chạy tiếp
// khi container đã dừng.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class PaymentConsumerIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine") // F32: pin patch, cùng tag với compose
            .withUsername("payment_test")
            .withPassword("payment_test_pw");

    /** Cổng host CỐ ĐỊNH cho AMQP (pattern T13/T14): Testcontainers mặc định gán cổng host ngẫu nhiên;
     *  cố định giúp app cấu hình một lần và kết nối lại đúng cổng nếu broker restart. */
    private static final int AMQP_HOST_PORT = freeLocalPort();

    // F32: pin đúng patch khớp compose (rabbitmq:3.13.7-management-alpine).
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
                    DockerImageName.parse("rabbitmq:3.13.7-management-alpine"))
            .withAdminUser("ridehailing_dev")
            .withAdminPassword("rabbit_dev_2026")
            // Chỉ expose AMQP: Testcontainers chờ MỌI cổng exposed có ánh xạ, mà bên dưới chỉ bind 5672.
            .withExposedPorts(5672)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", AMQP_HOST_PORT),
                            new ExposedPort(5672))));

    private static int freeLocalPort() {
        try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> String.valueOf(AMQP_HOST_PORT));
        registry.add("spring.rabbitmq.username", () -> "ridehailing_dev");
        registry.add("spring.rabbitmq.password", () -> "rabbit_dev_2026");
        // Backoff ngắn cho test (mặc định production 1s/3s/10s).
        registry.add("app.inbox.retry-backoff-ms", () -> "300,700,1500");
        // Outbox worker chạy nhanh trong test (mặc định production 1000 ms).
        registry.add("app.outbox.publish-interval-ms", () -> "200");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private com.ridehailing.payment.service.PaymentSimulator simulator;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Queue trip-service declare ở T16 (bind `payment.#` theo F02) — test mô phỏng topology đó để
     *  outbox publish mandatory=true không bị broker returned. */
    private static final String TRIP_INBOX_QUEUE = "trip.inbox";

    // ---------- setup/cleanup ----------

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM inbox_events");
        jdbc.update("DELETE FROM payments");
        jdbc.update("DELETE FROM outbox");
        declareTripInbox();
        // spring-rabbit 4.x/amqp-client 5: RabbitTemplate không còn purgeQueue, Channel dùng queuePurge.
        rabbitTemplate.execute(channel -> channel.queuePurge(TRIP_INBOX_QUEUE)); // không lẫn message test trước
    }

    /** Mô phỏng topology T16/F02 (test-only): trip-service declare trip.inbox bind `payment.#`.
     *  Không có binding này, outbox publish mandatory=true bị broker returned → PENDING mãi. */
    private void declareTripInbox() {
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclare(RabbitTopologyConfig.EVENTS_EXCHANGE, "topic", true);
            channel.queueDeclare(TRIP_INBOX_QUEUE, true, false, false, null);
            channel.queueBind(TRIP_INBOX_QUEUE, RabbitTopologyConfig.EVENTS_EXCHANGE, "payment.#");
            return null;
        });
    }

    // ---------- DB helpers ----------

    private int inboxCount(UUID eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE event_id = ?", Integer.class, eventId);
        return n == null ? 0 : n;
    }

    private int paymentCount(UUID tripId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE trip_id = ?", Integer.class, tripId);
        return n == null ? 0 : n;
    }

    private String paymentStatus(UUID tripId, int attempt) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE trip_id = ? AND attempt = ?",
                String.class, tripId, attempt);
    }

    private String paymentFailureCode(UUID tripId, int attempt) {
        return jdbc.queryForObject("SELECT failure_code FROM payments WHERE trip_id = ? AND attempt = ?",
                String.class, tripId, attempt);
    }

    private long paymentAmountVnd(UUID tripId, int attempt) {
        Long v = jdbc.queryForObject("SELECT amount_vnd FROM payments WHERE trip_id = ? AND attempt = ?",
                Long.class, tripId, attempt);
        return v == null ? -1L : v;
    }

    private UUID paymentRowId(UUID tripId, int attempt) {
        return jdbc.queryForObject("SELECT id FROM payments WHERE trip_id = ? AND attempt = ?",
                UUID.class, tripId, attempt);
    }

    private int outboxCount(UUID tripId, UUID causationEventId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND causation_id = ?",
                Integer.class, tripId, causationEventId);
        return n == null ? 0 : n;
    }

    // ---------- publish/receive helpers (envelope theo events.md §2/§3.4) ----------

    private MessageProperties baseProps(UUID eventId, UUID correlationId, String eventType) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(eventId.toString());          // messageId = eventId (§1: consumer dedup ở tầng AMQP)
        props.setCorrelationId(correlationId.toString());
        props.setType(eventType);
        props.setContentType("application/json");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return props;
    }

    /** Envelope TripCompleted theo events.md §3.4 — causation null (HTTP-triggered, §5),
     *  aggregateVersion optional (bỏ qua ở đây). */
    private byte[] envelopeBytes(UUID eventId, UUID correlationId, UUID tripId, int attempt,
                                long amountVnd, String currency) throws Exception {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", eventId.toString());
        env.put("eventType", "TripCompleted");
        env.put("schemaVersion", 1);
        env.put("occurredAt", Instant.now().toString()); // ISO-8601 UTC
        env.put("producer", "trip-service");
        env.put("correlationId", correlationId.toString());
        env.put("aggregateId", tripId.toString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("attempt", attempt);
        payload.put("amountVnd", amountVnd);
        payload.put("currency", currency);
        env.put("payload", payload);
        return JSON.writeValueAsBytes(env);
    }

    /** Publish TripCompleted qua exchange ride.events rk trip.completed (đường đi thật của T22). */
    private void publishTripCompleted(UUID eventId, UUID correlationId, UUID tripId, int attempt,
                                     long amountVnd, String currency) throws Exception {
        byte[] body = envelopeBytes(eventId, correlationId, tripId, attempt, amountVnd, currency);
        rabbitTemplate.send(RabbitTopologyConfig.EVENTS_EXCHANGE, RabbitTopologyConfig.TRIP_COMPLETED_ROUTING_KEY,
                new Message(body, baseProps(eventId, correlationId, "TripCompleted")));
    }

    private void waitForInboxRow(UUID eventId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (inboxCount(eventId) >= 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no inbox row for eventId " + eventId + " within 30 s");
    }

    /** Chờ outbox payment-service của trip (causation = eventId TripCompleted) thành SENT; trả id. */
    private UUID waitForOutboxSent(UUID tripId, UUID causationEventId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            List<UUID> ids = jdbc.query(
                    "SELECT id FROM outbox WHERE aggregate_id = ? AND causation_id = ? AND status = 'SENT'",
                    (rs, i) -> rs.getObject("id", UUID.class), tripId, causationEventId);
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no SENT outbox row for trip " + tripId
                + " (causation " + causationEventId + ") within 30 s");
    }

    /** Nhận message từ trip.inbox khớp messageId; timeout 30 s. */
    private Message receiveFromTripInbox(String messageId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Message msg = rabbitTemplate.receive(TRIP_INBOX_QUEUE, 1_000);
            if (msg == null) {
                continue;
            }
            if (messageId.equals(msg.getMessageProperties().getMessageId())) {
                return msg;
            }
        }
        throw new AssertionError("no trip.inbox message with messageId " + messageId + " within 30 s");
    }

    /** Nhận message từ payment.dlq khớp messageId; timeout 30 s (đủ cho backoff test 300/700/1500 ms). */
    private Message receiveFromDlq(String messageId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Message msg = rabbitTemplate.receive(RabbitTopologyConfig.PAYMENT_DLQ_QUEUE, 1_000);
            if (msg == null) {
                continue;
            }
            if (messageId.equals(msg.getMessageProperties().getMessageId())) {
                return msg;
            }
        }
        throw new AssertionError("no DLQ message with messageId " + messageId + " within 30 s");
    }

    /** Assert không có message DLQ nào mang messageId này trong 5 s. */
    private void assertNoDlqMessage(String messageId) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Message msg = rabbitTemplate.receive(RabbitTopologyConfig.PAYMENT_DLQ_QUEUE, 1_000);
            if (msg != null && messageId.equals(msg.getMessageProperties().getMessageId())) {
                throw new AssertionError("unexpected DLQ message with messageId " + messageId);
            }
        }
    }

    /** spring-amqp 4.x: delivery mode của message nhận từ broker ở getReceivedDeliveryMode(). */
    private void assertPersistent(Message msg) {
        assertThat(msg.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    // ---------- log assertions (structured JSON, spec §11) ----------

    /** Bắt log của CẢ listener (inbox_retry/inbox_dlq/...) và handler (duplicate_payment_skipped/...). */
    private record LogCapture(ListAppender<ILoggingEvent> listenerAppender,
                             ListAppender<ILoggingEvent> handlerAppender) {
        long count(String correlationId, String eventType, String result) {
            return countIn(listenerAppender, correlationId, eventType, result)
                    + countIn(handlerAppender, correlationId, eventType, result);
        }

        private static long countIn(ListAppender<ILoggingEvent> appender, String correlationId,
                                   String eventType, String result) {
            return new ArrayList<>(appender.list).stream().filter(e ->
                    correlationId.equals(e.getMDCPropertyMap().get("correlationId"))
                            && eventType.equals(e.getMDCPropertyMap().get("eventType"))
                            && result.equals(e.getMDCPropertyMap().get("result"))).count();
        }
    }

    private LogCapture attachLogCapture() {
        ListAppender<ILoggingEvent> listener = new ListAppender<>();
        listener.start();
        ((Logger) LoggerFactory.getLogger(PaymentInboxListener.class)).addAppender(listener);
        ListAppender<ILoggingEvent> handler = new ListAppender<>();
        handler.start();
        ((Logger) LoggerFactory.getLogger(TripCompletedHandler.class)).addAppender(handler);
        return new LogCapture(listener, handler);
    }

    private void detachLogCapture(LogCapture capture) {
        ((Logger) LoggerFactory.getLogger(PaymentInboxListener.class)).detachAppender(capture.listenerAppender());
        ((Logger) LoggerFactory.getLogger(TripCompletedHandler.class)).detachAppender(capture.handlerAppender());
    }

    // ---------- tests ----------

    /** Acceptance: "success/failure đều có event đúng" (phần success) — thu tiền thành công →
     *  payments SUCCEEDED + PaymentSucceeded {tripId, paymentId, attempt, amountVnd} (§3.5),
     *  causationId = eventId TripCompleted (§5), message persistent trên trip.inbox. */
    @org.junit.jupiter.api.Test
    void tripCompletedProducesPaymentSucceeded() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        simulator.setFailRate(0); // deterministic success

        publishTripCompleted(eventId, correlationId, tripId, 1, 32_000L, "VND");
        waitForInboxRow(eventId);

        assertThat(paymentStatus(tripId, 1)).isEqualTo("SUCCEEDED");
        assertThat(paymentFailureCode(tripId, 1)).isNull();
        assertThat(paymentAmountVnd(tripId, 1)).isEqualTo(32_000L);

        UUID outboxEventId = waitForOutboxSent(tripId, eventId);
        Message msg = receiveFromTripInbox(outboxEventId.toString());
        assertThat(msg.getMessageProperties().getType()).isEqualTo("PaymentSucceeded");
        assertThat(msg.getMessageProperties().getCorrelationId()).isEqualTo(correlationId.toString());
        assertPersistent(msg);

        @SuppressWarnings("unchecked")
        Map<String, Object> env = JSON.readValue(msg.getBody(), Map.class);
        // §5: causationId = eventId của TripCompleted; aggregateVersion optional — payment không
        // đọc Trip DB nên envelope KHÔNG chứa field này (chốt T23).
        assertThat(env.get("causationId")).isEqualTo(eventId.toString());
        assertThat(env).doesNotContainKey("aggregateVersion");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) env.get("payload");
        assertThat(payload.get("tripId")).isEqualTo(tripId.toString());
        assertThat(((Number) payload.get("attempt")).intValue()).isEqualTo(1);
        assertThat(((Number) payload.get("amountVnd")).longValue()).isEqualTo(32_000L);
        // paymentId trong event khớp row payments (id UUID).
        UUID paymentId = UUID.fromString((String) payload.get("paymentId"));
        assertThat(paymentRowId(tripId, 1)).isEqualTo(paymentId);

        assertNoDlqMessage(eventId.toString());
    }

    /** Acceptance: "Duplicate TripCompleted không thu trùng" — redelivery cùng eventId → dedup §4.1:
     *  đúng 1 row payments (unique trip/attempt), 1 row outbox, 1 row inbox_events. */
    @org.junit.jupiter.api.Test
    void duplicateTripCompletedDoesNotChargeTwice() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        simulator.setFailRate(0);

        publishTripCompleted(eventId, correlationId, tripId, 1, 32_000L, "VND");
        waitForInboxRow(eventId);

        // Mô phỏng at-least-once redelivery: publish lại envelope cùng eventId.
        LogCapture capture = attachLogCapture();
        try {
            publishTripCompleted(eventId, correlationId, tripId, 1, 32_000L, "VND");
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                if (capture.count(correlationId.toString(), "inbox_duplicate", "success") >= 1) {
                    break;
                }
                Thread.sleep(100);
            }
        } finally {
            detachLogCapture(capture);
        }

        assertThat(inboxCount(eventId)).isEqualTo(1);      // dedup record duy nhất
        assertThat(paymentCount(tripId)).isEqualTo(1);    // không thu trùng (trip, attempt)
        assertThat(outboxCount(tripId, eventId)).isEqualTo(1); // một event PaymentSucceeded duy nhất

        assertNoDlqMessage(eventId.toString());
    }

    /** Acceptance: "success/failure đều có event đúng" (phần failure) — decline giả lập →
     *  payments FAILED + failure_code SIMULATED_DECLINE + PaymentFailed {tripId, paymentId, attempt, reason} (§3.6). */
    @org.junit.jupiter.api.Test
    void simulatedDeclineEmitsPaymentFailed() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        simulator.setFailRate(1); // deterministic decline

        publishTripCompleted(eventId, correlationId, tripId, 1, 32_000L, "VND");
        waitForInboxRow(eventId);

        assertThat(paymentStatus(tripId, 1)).isEqualTo("FAILED");
        assertThat(paymentFailureCode(tripId, 1)).isEqualTo(TripCompletedHandler.REASON_SIMULATED_DECLINE);
        // Amount vẫn được ghi — payment đã thử thu đúng số tiền (spec §7 payments.amount_vnd).
        assertThat(paymentAmountVnd(tripId, 1)).isEqualTo(32_000L);

        UUID outboxEventId = waitForOutboxSent(tripId, eventId);
        Message msg = receiveFromTripInbox(outboxEventId.toString());
        assertThat(msg.getMessageProperties().getType()).isEqualTo("PaymentFailed");
        assertPersistent(msg);

        @SuppressWarnings("unchecked")
        Map<String, Object> env = JSON.readValue(msg.getBody(), Map.class);
        assertThat(env.get("causationId")).isEqualTo(eventId.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) env.get("payload");
        assertThat(payload.get("tripId")).isEqualTo(tripId.toString());
        assertThat(((Number) payload.get("attempt")).intValue()).isEqualTo(1);
        assertThat(payload.get("reason")).isEqualTo(TripCompletedHandler.REASON_SIMULATED_DECLINE);

        // Không có row PaymentSucceeded nào cho trip này.
        Integer succeeded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'PaymentSucceeded'",
                Integer.class, tripId);
        assertThat(succeeded).isZero();

        assertNoDlqMessage(eventId.toString());
    }

    /** Acceptance: "amount/currency validation" — amountVnd ≤ 0 hoặc currency ≠ VND → envelope
     *  malformed → republish DLQ kèm x-dlq-reason=malformed_envelope, ACK gốc; không có payments/outbox. */
    @org.junit.jupiter.api.Test
    void malformedAmountOrCurrencyGoesToDlqWithReason() throws Exception {
        UUID tripId = UUID.randomUUID();

        // Case 1: amountVnd âm.
        UUID eventIdBadAmount = UUID.randomUUID();
        publishTripCompleted(eventIdBadAmount, UUID.randomUUID(), tripId, 1, -5L, "VND");
        Message dlq1 = receiveFromDlq(eventIdBadAmount.toString());
        Object reason1 = dlq1.getMessageProperties().getHeader(DlqRepublisher.DLQ_REASON_HEADER);
        assertThat(reason1).isEqualTo("malformed_envelope");
        // Republish chủ động (§6a): KHÔNG có x-death (broker không dead-letter message này).
        assertThat(dlq1.getMessageProperties().getHeaders()).doesNotContainKey("x-death");

        // Case 2: currency khác VND.
        UUID eventIdBadCurrency = UUID.randomUUID();
        publishTripCompleted(eventIdBadCurrency, UUID.randomUUID(), tripId, 1, 32_000L, "USD");
        Message dlq2 = receiveFromDlq(eventIdBadCurrency.toString());
        Object reason2 = dlq2.getMessageProperties().getHeader(DlqRepublisher.DLQ_REASON_HEADER);
        assertThat(reason2)
                .isEqualTo("malformed_envelope");

        // ACK gốc + không xử lý: không có inbox row, payments hay outbox.
        assertThat(inboxCount(eventIdBadAmount)).isZero();
        assertThat(inboxCount(eventIdBadCurrency)).isZero();
        assertThat(paymentCount(tripId)).isZero();
    }

    /** Event hợp lệ nhưng consumer không xử lý (eventType PaymentSucceeded) → unsupported_event_type,
     *  republish DLQ kèm reason; không retry như lỗi mạng. */
    @org.junit.jupiter.api.Test
    void unsupportedEventTypeGoesToDlqWithReason() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", eventId.toString());
        env.put("eventType", "PaymentSucceeded"); // event của payment-service — payment không consume
        env.put("schemaVersion", 1);
        env.put("occurredAt", Instant.now().toString());
        env.put("producer", "payment-service");
        env.put("correlationId", correlationId.toString());
        env.put("aggregateId", tripId.toString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("attempt", 1);
        payload.put("amountVnd", 32_000L);
        env.put("payload", payload);

        rabbitTemplate.send(RabbitTopologyConfig.EVENTS_EXCHANGE, RabbitTopologyConfig.TRIP_COMPLETED_ROUTING_KEY,
                new Message(JSON.writeValueAsBytes(env), baseProps(eventId, correlationId, "PaymentSucceeded")));

        Message dlq = receiveFromDlq(eventId.toString());
        Object reasonType = dlq.getMessageProperties().getHeader(DlqRepublisher.DLQ_REASON_HEADER);
        assertThat(reasonType).isEqualTo("unsupported_event_type");
        assertThat(inboxCount(eventId)).isZero();
        assertThat(paymentCount(tripId)).isZero();
    }

    /** Acceptance: "transaction rollback an toàn" — transient failure 2 lần rồi thành công:
     *  attempt 3 commit; đúng 1 row payments + 1 row outbox (rollback không để lại state dở dang). */
    @org.junit.jupiter.api.Test
    void transientFailureRetriesThenSucceeds() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        simulator.setFailRate(0);
        FaultInjectionConfig.FAIL_COUNTS.put(eventId, 2); // attempt 1 và 2 fail

        LogCapture capture = attachLogCapture();
        try {
            publishTripCompleted(eventId, correlationId, tripId, 1, 32_000L, "VND");
            UUID outboxEventId = waitForOutboxSent(tripId, eventId);
            receiveFromTripInbox(outboxEventId.toString()); // rút message để không lẫn test khác

            assertThat(FaultInjectionConfig.ATTEMPTS.get(eventId)).isEqualTo(3); // 2 fail + 1 success
            assertThat(capture.count(correlationId.toString(), "inbox_retry", "failure")).isEqualTo(2);
            assertThat(capture.count(correlationId.toString(), "inbox_processed", "success")).isEqualTo(1);

            assertThat(inboxCount(eventId)).isEqualTo(1);
            assertThat(paymentCount(tripId)).isEqualTo(1);   // rollback 2 lần không để row dở dang
            assertThat(outboxCount(tripId, eventId)).isEqualTo(1);
            assertThat(paymentStatus(tripId, 1)).isEqualTo("SUCCEEDED");

            assertNoDlqMessage(eventId.toString()); // retry thành công — không vào DLQ
        } finally {
            detachLogCapture(capture);
        }
    }

    /** Acceptance: "transaction rollback an toàn" (hết lượt) — fail hết 4 execution →
     *  reject(requeue=false) → DLQ chỉ x-death, KHÔNG x-dlq-reason; mọi attempt rollback →
     *  không có payments/outbox/inbox row (§4.3/§6b). */
    @org.junit.jupiter.api.Test
    void retryExhaustedGoesToDlqWithoutReasonHeader() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        FaultInjectionConfig.FAIL_COUNTS.put(eventId, 99); // mọi attempt fail

        LogCapture capture = attachLogCapture();
        try {
            publishTripCompleted(eventId, correlationId, tripId, 1, 32_000L, "VND");
            Message dlq = receiveFromDlq(eventId.toString());

            // Broker dead-letter: có x-death, KHÔNG có x-dlq-reason (khác đường republish chủ động §6a).
            Object xDeath = dlq.getMessageProperties().getHeader("x-death");
            assertThat(xDeath).isNotNull();
            assertThat(dlq.getMessageProperties().getHeaders()).doesNotContainKey(DlqRepublisher.DLQ_REASON_HEADER);
            assertPersistent(dlq);

            // Rollback 4 lần → không có payments, outbox hay inbox row.
            assertThat(paymentCount(tripId)).isZero();
            assertThat(inboxCount(eventId)).isZero();
            Integer pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ?", Integer.class, tripId);
            assertThat(pending).isZero();

            // Attempt đầu + 3 retry = 4 execution: đúng 3 log inbox_retry failure + 1 give-up.
            assertThat(capture.count(correlationId.toString(), "inbox_retry", "failure")).isEqualTo(3);
            assertThat(capture.count(correlationId.toString(), "inbox_dlq", "retry_exhausted")).isEqualTo(1);
        } finally {
            detachLogCapture(capture);
        }
    }
}
