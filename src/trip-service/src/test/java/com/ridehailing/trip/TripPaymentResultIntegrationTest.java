package com.ridehailing.trip;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ridehailing.trip.config.RabbitTopologyConfig;
import com.ridehailing.trip.inbox.DlqRepublisher;
import com.ridehailing.trip.inbox.InboxEventHandler;
import com.ridehailing.trip.inbox.TripInboxListener;
import com.ridehailing.trip.service.TripInboxHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T24 — trip nhận payment result (spec §20.5, events.md §3.5/§3.6): consume
 * {@code PaymentSucceeded}/{@code PaymentFailed} từ queue {@code trip.inbox} → guard attempt/state
 * (§4.4) + CAS {@code payment_status PENDING → SUCCEEDED/FAILED} + trip_event PAYMENT_*; state
 * COMPLETED giữ nguyên dù FAILED (invariant 7); event stale/trùng bị skip ACK, KHÔNG vào DLQ.
 *
 * <p>Acceptance spec §20.5: "Trip COMPLETED giữ nguyên khi payment FAILED; event duplicate/stale
 * không sửa kết quả sai; ownership GET đúng." PostgreSQL thật + RabbitMQ thật (listener T16 chạy
 * bình thường) — publish qua exchange {@code ride.events} rk {@code payment.succeeded}/{@code payment.failed},
 * đường đi thật của outbox payment-service.
 */
@SpringBootTest(classes = {TripServiceApplication.class, TripPaymentResultIntegrationTest.FaultInjectionConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Đóng context (listener + outbox worker) khi xong class, không để consumer chạy tiếp khi container dừng.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TripPaymentResultIntegrationTest extends TripCommandTestSupport {

    @DynamicPropertySource
    static void inboxRetryProps(DynamicPropertyRegistry registry) {
        // Backoff ngắn cho test (mặc định production 1s/3s/10s).
        registry.add("app.inbox.retry-backoff-ms", () -> "100,200,400");
    }

    @Autowired
    RabbitTemplate rabbitTemplate;

    // ---------- setup/cleanup ----------

    @BeforeEach
    void purgeInboxQueue() {
        // spring-rabbit 4.x/amqp-client 5: RabbitTemplate không còn purgeQueue, Channel dùng queuePurge.
        // Không lẫn message test trước (trip cũ đã bị cleanTables xóa → handler sẽ fail→DLQ).
        rabbitTemplate.execute(channel -> channel.queuePurge(RabbitTopologyConfig.TRIP_INBOX_QUEUE));
    }

    // ---------- HTTP helpers (GET + start/complete theo pattern T22) ----------

    Resp get(String path, String bearer) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            req.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), parse(resp.body()));
    }

    Resp start(UUID tripId, UUID callerUserId) throws Exception {
        return post("/api/trips/" + tripId + "/start", token(callerUserId, "DRIVER"), null);
    }

    Resp complete(UUID tripId, UUID callerUserId) throws Exception {
        return post("/api/trips/" + tripId + "/complete", token(callerUserId, "DRIVER"), null);
    }

    /** offer → accept → start: chuyến IN_PROGRESS với đúng assigned driver (pattern T22). */
    Offered startedTrip() throws Exception {
        Offered o = offeredTrip();
        Resp acc = accept(o, o.driverUserId(), o.attempt());
        assertThat(acc.status()).isEqualTo(202);
        Resp st = start(o.tripId(), o.driverUserId());
        assertThat(st.status()).isEqualTo(200);
        return o;
    }

    // ---------- DB helpers ----------

    private int inboxCount(UUID eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE event_id = ?", Integer.class, eventId);
        return n == null ? 0 : n;
    }

    private String tripPaymentStatus(UUID tripId) {
        return jdbc.queryForObject("SELECT payment_status FROM trips WHERE id = ?", String.class, tripId);
    }

    private int tripEventCount(UUID tripId, String type) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trip_events WHERE trip_id = ? AND type = ?", Integer.class, tripId, type);
        return n == null ? 0 : n;
    }

    private List<Integer> tripEventVersions(UUID tripId, String type) {
        return jdbc.queryForList(
                "SELECT version FROM trip_events WHERE trip_id = ? AND type = ? ORDER BY id",
                Integer.class, tripId, type);
    }

    // ---------- publish/receive helpers (envelope theo events.md §2/§3.5/§3.6) ----------

    private MessageProperties baseProps(UUID eventId, UUID correlationId, String eventType) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(eventId.toString());          // messageId = eventId (§1: consumer dedup ở tầng AMQP)
        props.setCorrelationId(correlationId.toString());
        props.setType(eventType);
        props.setContentType("application/json");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return props;
    }

    /** Envelope PaymentSucceeded/PaymentFailed theo events.md §3.5/§3.6 — aggregateVersion bỏ qua
     *  (payment không đọc trip DB, chốt T23); causationId null (event phát từ outbox payment). */
    private byte[] paymentEnvelopeBytes(UUID eventId, UUID correlationId, String eventType, UUID tripId,
                                        UUID paymentId, int attempt, long amountVnd, String reason) throws Exception {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("eventId", eventId.toString());
        env.put("eventType", eventType);
        env.put("schemaVersion", 1);
        env.put("occurredAt", Instant.now().toString()); // ISO-8601 UTC
        env.put("producer", "payment-service");
        env.put("correlationId", correlationId.toString());
        env.put("aggregateId", tripId.toString());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("paymentId", paymentId.toString());
        payload.put("attempt", attempt);
        if ("PaymentSucceeded".equals(eventType)) {
            payload.put("amountVnd", amountVnd); // §3.5
        } else {
            payload.put("reason", reason);       // §3.6 — mã kỹ thuật/giả lập (vd SIMULATED_DECLINE)
        }
        env.put("payload", payload);
        return JSON.writeValueAsBytes(env);
    }

    /** Publish qua exchange ride.events rk payment.succeeded/payment.failed → routed vào trip.inbox
     *  (topology service declare ở T16, bind {@code payment.#}). */
    private void publishPaymentEvent(String eventType, UUID eventId, UUID correlationId, UUID tripId,
                                     UUID paymentId, int attempt, long amountVnd, String reason) throws Exception {
        byte[] body = paymentEnvelopeBytes(eventId, correlationId, eventType, tripId, paymentId, attempt, amountVnd, reason);
        String rk = "PaymentSucceeded".equals(eventType) ? "payment.succeeded" : "payment.failed";
        rabbitTemplate.send(RabbitTopologyConfig.EVENTS_EXCHANGE, rk,
                new Message(body, baseProps(eventId, correlationId, eventType)));
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

    /** Nhận message từ trip.dlq khớp messageId; timeout 30 s (đủ cho backoff test 100/200/400 ms). */
    private Message receiveFromDlq(String messageId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Message msg = rabbitTemplate.receive(RabbitTopologyConfig.TRIP_DLQ_QUEUE, 1_000);
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
            Message msg = rabbitTemplate.receive(RabbitTopologyConfig.TRIP_DLQ_QUEUE, 1_000);
            if (msg != null && messageId.equals(msg.getMessageProperties().getMessageId())) {
                throw new AssertionError("unexpected DLQ message with messageId " + messageId);
            }
        }
    }

    // ---------- log assertions (structured JSON, spec §11) ----------

    /** Bắt log của listener (inbox_retry/inbox_processed/...) để đếm retry bounded. */
    private record LogCapture(ListAppender<ILoggingEvent> listenerAppender) {
        long count(String correlationId, String eventType, String result) {
            return new ArrayList<>(listenerAppender.list).stream().filter(e ->
                    correlationId.equals(e.getMDCPropertyMap().get("correlationId"))
                            && eventType.equals(e.getMDCPropertyMap().get("eventType"))
                            && result.equals(e.getMDCPropertyMap().get("result"))).count();
        }
    }

    private LogCapture attachLogCapture() {
        ListAppender<ILoggingEvent> listener = new ListAppender<>();
        listener.start();
        ((Logger) LoggerFactory.getLogger(TripInboxListener.class)).addAppender(listener);
        return new LogCapture(listener);
    }

    private void detachLogCapture(LogCapture capture) {
        ((Logger) LoggerFactory.getLogger(TripInboxListener.class)).detachAppender(capture.listenerAppender());
    }

    // ---------- tests ----------

    /** Acceptance: "Trip COMPLETED giữ nguyên khi payment FAILED" (phần success) — PaymentSucceeded
     *  đúng attempt → payment_status SUCCEEDED + trip_event PAYMENT_SUCCEEDED tại version+1; state
     *  COMPLETED không đổi; ownership GET passenger thấy paymentStatus, người lạ 404. */
    @Test
    void paymentSucceededUpdatesPaymentStatusAndTripEvent() throws Exception {
        Offered o = startedTrip();
        Resp c = complete(o.tripId(), o.driverUserId());
        assertThat(c.status()).isEqualTo(200);
        assertThat((String) c.body().get("state")).isEqualTo("COMPLETED");

        int versionBefore = tripInt(o.tripId(), "version");
        long fareVnd = jdbc.queryForObject("SELECT fare_vnd FROM trips WHERE id = ?", Long.class, o.tripId());

        UUID eventId = UUID.randomUUID();
        publishPaymentEvent("PaymentSucceeded", eventId, UUID.randomUUID(), o.tripId(),
                UUID.randomUUID(), 1, fareVnd, null);
        waitForInboxRow(eventId);

        assertThat(tripState(o.tripId())).isEqualTo("COMPLETED"); // state giữ nguyên (invariant 7)
        assertThat(tripPaymentStatus(o.tripId())).isEqualTo("SUCCEEDED");
        assertThat(tripInt(o.tripId(), "version")).isEqualTo(versionBefore + 1); // version+1 (chốt T24)

        List<Integer> versions = tripEventVersions(o.tripId(), "PAYMENT_SUCCEEDED");
        assertThat(versions).containsExactly(versionBefore + 1);

        // Ownership GET: passenger thấy paymentStatus SUCCEEDED; người không liên quan → 404.
        Resp getOwner = get("/api/trips/" + o.tripId(), token(o.passengerUserId(), "PASSENGER"));
        assertThat(getOwner.status()).isEqualTo(200);
        assertThat((String) getOwner.body().get("paymentStatus")).isEqualTo("SUCCEEDED");

        Resp getStranger = get("/api/trips/" + o.tripId(), token(UUID.randomUUID(), "PASSENGER"));
        assertThat(getStranger.status()).isEqualTo(404);

        assertNoDlqMessage(eventId.toString());
    }

    /** Acceptance: "Trip COMPLETED giữ nguyên khi payment FAILED" — PaymentFailed → payment_status
     *  FAILED, state COMPLETED không đổi (invariant 7), trip_event PAYMENT_FAILED tại version+1. */
    @Test
    void paymentFailedKeepsCompletedState() throws Exception {
        Offered o = startedTrip();
        Resp c = complete(o.tripId(), o.driverUserId());
        assertThat(c.status()).isEqualTo(200);

        int versionBefore = tripInt(o.tripId(), "version");

        UUID eventId = UUID.randomUUID();
        publishPaymentEvent("PaymentFailed", eventId, UUID.randomUUID(), o.tripId(),
                UUID.randomUUID(), 1, 0L, "SIMULATED_DECLINE");
        waitForInboxRow(eventId);

        assertThat(tripState(o.tripId())).isEqualTo("COMPLETED"); // FAILED không đổi state (invariant 7)
        assertThat(tripPaymentStatus(o.tripId())).isEqualTo("FAILED");
        assertThat(tripInt(o.tripId(), "version")).isEqualTo(versionBefore + 1);

        List<Integer> versions = tripEventVersions(o.tripId(), "PAYMENT_FAILED");
        assertThat(versions).containsExactly(versionBefore + 1);
        assertThat(tripEventCount(o.tripId(), "PAYMENT_SUCCEEDED")).isZero(); // không có event success nào

        Resp getOwner = get("/api/trips/" + o.tripId(), token(o.passengerUserId(), "PASSENGER"));
        assertThat(getOwner.status()).isEqualTo(200);
        assertThat((String) getOwner.body().get("paymentStatus")).isEqualTo("FAILED");

        assertNoDlqMessage(eventId.toString());
    }

    /** Acceptance: "event duplicate/stale không sửa kết quả sai" — hai PaymentSucceeded eventId khác
     *  cùng trip/attempt → cả hai có inbox row (dedup theo eventId), nhưng MỘT trip_event duy nhất và
     *  version chỉ bump một lần; event thứ hai bị stale guard skip ACK, không vào DLQ. */
    @Test
    void duplicatePaymentEventDoesNotChangeResultTwice() throws Exception {
        Offered o = startedTrip();
        Resp c = complete(o.tripId(), o.driverUserId());
        assertThat(c.status()).isEqualTo(200);

        int versionBefore = tripInt(o.tripId(), "version");
        long fareVnd = jdbc.queryForObject("SELECT fare_vnd FROM trips WHERE id = ?", Long.class, o.tripId());

        UUID eventId1 = UUID.randomUUID();
        UUID eventId2 = UUID.randomUUID();
        publishPaymentEvent("PaymentSucceeded", eventId1, UUID.randomUUID(), o.tripId(),
                UUID.randomUUID(), 1, fareVnd, null);
        waitForInboxRow(eventId1);
        // Mô phỏng event trùng nghiệp vụ (eventId khác — không phải redelivery AMQP cùng eventId).
        publishPaymentEvent("PaymentSucceeded", eventId2, UUID.randomUUID(), o.tripId(),
                UUID.randomUUID(), 1, fareVnd, null);
        waitForInboxRow(eventId2);

        assertThat(inboxCount(eventId1)).isEqualTo(1);
        assertThat(inboxCount(eventId2)).isEqualTo(1); // dedup record cho cả hai eventId
        assertThat(tripEventCount(o.tripId(), "PAYMENT_SUCCEEDED")).isEqualTo(1); // chỉ áp dụng MỘT lần
        assertThat(tripInt(o.tripId(), "version")).isEqualTo(versionBefore + 1);   // version bump đúng một lần
        assertThat(tripPaymentStatus(o.tripId())).isEqualTo("SUCCEEDED");

        assertNoDlqMessage(eventId2.toString()); // stale → skip ACK, KHÔNG DLQ (§4.4)
    }

    /** Acceptance: "event duplicate/stale không sửa kết quả sai" — PaymentFailed attempt=2 trong khi
     *  payment_attempt trip là 1 → inbox row tồn tại nhưng state/payment_status không đổi, không có
     *  trip_event PAYMENT_*, version không bump. */
    @Test
    void staleAttemptPaymentEventIsSkipped() throws Exception {
        Offered o = startedTrip();
        Resp c = complete(o.tripId(), o.driverUserId());
        assertThat(c.status()).isEqualTo(200);

        int versionBefore = tripInt(o.tripId(), "version");

        UUID eventId = UUID.randomUUID();
        publishPaymentEvent("PaymentFailed", eventId, UUID.randomUUID(), o.tripId(),
                UUID.randomUUID(), 2, 0L, "SIMULATED_DECLINE"); // attempt lệch (trip ở attempt 1)
        waitForInboxRow(eventId);

        assertThat(tripState(o.tripId())).isEqualTo("COMPLETED");
        assertThat(tripPaymentStatus(o.tripId())).isEqualTo("PENDING"); // không đổi
        assertThat(tripInt(o.tripId(), "version")).isEqualTo(versionBefore); // không bump
        assertThat(tripEventCount(o.tripId(), "PAYMENT_FAILED")).isZero();
        assertThat(tripEventCount(o.tripId(), "PAYMENT_SUCCEEDED")).isZero();

        assertNoDlqMessage(eventId.toString());
    }

    /** Envelope PaymentSucceeded sai schema (amountVnd âm) → malformed_envelope: republish DLQ kèm
     *  x-dlq-reason, KHÔNG x-death (§6a); ACK gốc — không có inbox row, state không đổi. */
    @Test
    void malformedPaymentEventGoesToDlqWithReason() throws Exception {
        Offered o = startedTrip();
        Resp c = complete(o.tripId(), o.driverUserId());
        assertThat(c.status()).isEqualTo(200);

        UUID eventId = UUID.randomUUID();
        publishPaymentEvent("PaymentSucceeded", eventId, UUID.randomUUID(), o.tripId(),
                UUID.randomUUID(), 1, -5L, null); // amountVnd âm → malformed (§3.5: nguyên >0)

        Message dlq = receiveFromDlq(eventId.toString());
        Object reason = dlq.getMessageProperties().getHeader(DlqRepublisher.DLQ_REASON_HEADER);
        assertThat(reason).isEqualTo("malformed_envelope");
        // Republish chủ động (§6a): KHÔNG có x-death (broker không dead-letter message này).
        assertThat(dlq.getMessageProperties().getHeaders()).doesNotContainKey("x-death");

        // ACK gốc + không xử lý: không inbox row, payment_status giữ PENDING.
        assertThat(inboxCount(eventId)).isZero();
        assertThat(tripPaymentStatus(o.tripId())).isEqualTo("PENDING");
    }

    /** Acceptance (rollback an toàn) — transient failure 2 lần rồi thành công: attempt 3 commit;
     *  đúng 1 inbox row + 1 trip_event PAYMENT_SUCCEEDED, version bump một lần, không vào DLQ. */
    @Test
    void transientFailureRetriesThenSucceeds() throws Exception {
        Offered o = startedTrip();
        Resp c = complete(o.tripId(), o.driverUserId());
        assertThat(c.status()).isEqualTo(200);

        int versionBefore = tripInt(o.tripId(), "version");
        long fareVnd = jdbc.queryForObject("SELECT fare_vnd FROM trips WHERE id = ?", Long.class, o.tripId());

        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        FaultInjectionConfig.FAIL_COUNTS.put(eventId, 2); // attempt 1 và 2 fail

        LogCapture capture = attachLogCapture();
        try {
            publishPaymentEvent("PaymentSucceeded", eventId, correlationId, o.tripId(),
                    UUID.randomUUID(), 1, fareVnd, null);
            waitForInboxRow(eventId);

            assertThat(FaultInjectionConfig.ATTEMPTS.get(eventId)).isEqualTo(3); // 2 fail + 1 success
            assertThat(capture.count(correlationId.toString(), "inbox_retry", "failure")).isEqualTo(2);
            assertThat(capture.count(correlationId.toString(), "inbox_processed", "success")).isEqualTo(1);

            assertThat(tripPaymentStatus(o.tripId())).isEqualTo("SUCCEEDED");
            assertThat(inboxCount(eventId)).isEqualTo(1); // rollback 2 lần không để row dở dang
            assertThat(tripEventCount(o.tripId(), "PAYMENT_SUCCEEDED")).isEqualTo(1);
            assertThat(tripInt(o.tripId(), "version")).isEqualTo(versionBefore + 1);

            assertNoDlqMessage(eventId.toString()); // retry thành công — không vào DLQ
        } finally {
            detachLogCapture(capture);
        }
    }

    /** Fault injection test-only (spec §13: chỉ bật trong test). Wrapper handler thật
     *  ({@link TripInboxHandler}): ném RuntimeException cho N lần đầu theo eventId (FAIL_COUNTS),
     *  đếm MỌI invocation (ATTEMPTS) để chứng minh "handler chạy đúng số lần" và rollback an toàn.
     *  Bean @Primary chỉ tồn tại trong context test này (khai báo explicit qua classes=...). */
    @TestConfiguration
    static class FaultInjectionConfig {

        static final Map<UUID, Integer> FAIL_COUNTS = new ConcurrentHashMap<>();
        static final Map<UUID, Integer> ATTEMPTS = new ConcurrentHashMap<>();

        /** Fail after real writes to verify rollback of payment status + trip event + inbox together. */
        @Bean
        @Primary
        InboxEventHandler faultInjectedHandler(TripInboxHandler real) {
            return envelope -> {
                int attempt = ATTEMPTS.merge(envelope.eventId(), 1, Integer::sum);
                int failN = FAIL_COUNTS.getOrDefault(envelope.eventId(), 0);
                real.handle(envelope);
                if (attempt <= failN) {
                    throw new TransientInboxException("simulated transient failure " + attempt + "/" + failN);
                }
            };
        }

        static final class TransientInboxException extends RuntimeException {
            TransientInboxException(String message) {
                super(message);
            }
        }
    }
}
