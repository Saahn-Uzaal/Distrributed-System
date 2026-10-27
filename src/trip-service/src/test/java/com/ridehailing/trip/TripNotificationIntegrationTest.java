package com.ridehailing.trip;

import com.ridehailing.trip.config.RabbitTopologyConfig;
import com.ridehailing.trip.repo.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T25 — Notifications và trip event history (spec §20.5, contract api-contracts.md §3.4/§3.5):
 * notification ghi tại các transition đã có trong CÙNG transaction với trip_event; GET /api/notifications
 * (chỉ caller, phân trang, created_at DESC) + GET /api/trips/{id}/events (owner/assigned driver,
 * phân trang, version DESC); retry/redelivery không tạo thông báo trùng cho một transition.
 *
 * <p>Acceptance spec §20.5: "Passenger/driver nhận đúng thông báo; không thấy tài nguyên người khác;
 * retry không tạo thông báo trùng cho một transition." PostgreSQL thật + RabbitMQ thật (listener T16
 * chạy bình thường) — publish PaymentSucceeded qua exchange {@code ride.events} rk {@code payment.succeeded},
 * đường đi thật của outbox payment-service.
 */
@SpringBootTest(classes = TripServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Đóng context (listener + outbox worker) khi xong class, không để consumer chạy tiếp khi container dừng.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TripNotificationIntegrationTest extends TripCommandTestSupport {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // 1 attempt duy nhất: reject/offer-expiry → NO_DRIVER ngay (test nhánh completeReject).
        registry.add("app.trip.max-matching-attempts", () -> "1");
    }

    @Autowired
    RabbitTemplate rabbitTemplate;
    @Autowired
    NotificationRepository notificationRepo;

    // ---------- setup/cleanup ----------

    @BeforeEach
    void purgeInboxQueue() {
        // Không lẫn message test trước (trip cũ đã bị cleanTables xóa → handler sẽ fail→DLQ).
        rabbitTemplate.execute(channel -> channel.queuePurge(RabbitTopologyConfig.TRIP_INBOX_QUEUE));
    }

    // ---------- HTTP helpers (GET + start/complete theo pattern T24) ----------

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

    /** offer → accept → start: chuyến IN_PROGRESS với đúng assigned driver (pattern T24). */
    Offered startedTrip() throws Exception {
        Offered o = offeredTrip();
        Resp acc = accept(o, o.driverUserId(), o.attempt());
        assertThat(acc.status()).isEqualTo(202);
        Resp st = start(o.tripId(), o.driverUserId());
        assertThat(st.status()).isEqualTo(200);
        return o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> contentOf(Resp r) {
        return (List<Map<String, Object>>) r.body().get("content");
    }

    /** type → message của một list notification. */
    private static Map<String, String> messagesByType(List<Map<String, Object>> content) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> m : content) {
            out.put((String) m.get("type"), (String) m.get("message"));
        }
        return out;
    }

    // ---------- DB helpers ----------

    private int notificationCount(UUID userId, String type) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = ?", Integer.class, userId, type);
        return n == null ? 0 : n;
    }

    private int tripEventCount(UUID tripId, String type) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trip_events WHERE trip_id = ? AND type = ?", Integer.class, tripId, type);
        return n == null ? 0 : n;
    }

    private int inboxCount(UUID eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE event_id = ?", Integer.class, eventId);
        return n == null ? 0 : n;
    }

    // ---------- publish/receive helpers (envelope theo events.md §2/§3.5 — pattern T24) ----------

    private MessageProperties baseProps(UUID eventId, UUID correlationId, String eventType) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(eventId.toString());          // messageId = eventId (§1: consumer dedup ở tầng AMQP)
        props.setCorrelationId(correlationId.toString());
        props.setType(eventType);
        props.setContentType("application/json");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return props;
    }

    private byte[] paymentEnvelopeBytes(UUID eventId, UUID correlationId, String eventType, UUID tripId,
                                        UUID paymentId, int attempt, long amountVnd) throws Exception {
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
            payload.put("reason", "SIMULATED_DECLINE"); // §3.6
        }
        env.put("payload", payload);
        return JSON.writeValueAsBytes(env);
    }

    /** Publish qua exchange ride.events rk payment.succeeded/payment.failed → routed vào trip.inbox
     *  (topology service declare ở T16, bind {@code payment.#}). */
    private void publishPaymentEvent(String eventType, UUID eventId, UUID correlationId, UUID tripId,
                                     UUID paymentId, int attempt, long amountVnd) throws Exception {
        byte[] body = paymentEnvelopeBytes(eventId, correlationId, eventType, tripId, paymentId, attempt, amountVnd);
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

    // ---------- tests ----------

    /** Acceptance: "Passenger/driver nhận đúng thông báo" — full lifecycle create → offer → accept →
     *  start → complete → PaymentSucceeded. Passenger: TRIP_OFFERED/TRIP_ACCEPTED/TRIP_COMPLETED/
     *  PAYMENT_SUCCEEDED; driver: OFFER_RECEIVED/TRIP_ACCEPTED/TRIP_COMPLETED (không payment). */
    @Test
    void fullLifecycleNotifiesPassengerAndDriver() throws Exception {
        Offered o = startedTrip();
        Resp c = complete(o.tripId(), o.driverUserId());
        assertThat(c.status()).isEqualTo(200);

        long fareVnd = jdbc.queryForObject("SELECT fare_vnd FROM trips WHERE id = ?", Long.class, o.tripId());
        UUID eventId = UUID.randomUUID();
        publishPaymentEvent("PaymentSucceeded", eventId, UUID.randomUUID(), o.tripId(),
                UUID.randomUUID(), 1, fareVnd);
        waitForInboxRow(eventId);

        // Passenger — đúng 4 thông báo, message theo contract/chốt T25.
        Resp pn = get("/api/notifications", token(o.passengerUserId(), "PASSENGER"));
        assertThat(pn.status()).isEqualTo(200);
        List<Map<String, Object>> pcontent = contentOf(pn);
        assertThat(pcontent).hasSize(4);
        Map<String, String> pmessages = messagesByType(pcontent);
        assertThat(pmessages)
                .containsEntry("TRIP_OFFERED", "Tài xế đã sẵn sàng đón bạn.")
                .containsEntry("TRIP_ACCEPTED", "Chuyến đi đã được chấp nhận.")
                .containsEntry("TRIP_COMPLETED", "Chuyến đi đã hoàn thành.")
                .containsEntry("PAYMENT_SUCCEEDED", "Thanh toán thành công.");
        for (Map<String, Object> m : pcontent) {
            assertThat(m.get("readAt")).isNull(); // MVP không có mark-as-read (contract §3.4 chốt)
            assertThat((String) m.get("tripId")).isEqualTo(o.tripId().toString());
        }

        // Driver — đúng 3 thông báo, KHÔNG có PAYMENT_*.
        Resp dn = get("/api/notifications", token(o.driverUserId(), "DRIVER"));
        assertThat(dn.status()).isEqualTo(200);
        List<Map<String, Object>> dcontent = contentOf(dn);
        assertThat(dcontent).hasSize(3);
        Map<String, String> dmessages = messagesByType(dcontent);
        assertThat(dmessages)
                .containsEntry("OFFER_RECEIVED", "Bạn có một chuyến đi mới cần đón khách.")
                .containsEntry("TRIP_ACCEPTED", "Chuyến đi đã được chấp nhận.")
                .containsEntry("TRIP_COMPLETED", "Chuyến đi đã hoàn thành.");

        // DB: notification nằm cùng version với trip_event tương ứng.
        assertThat(notificationCount(o.passengerUserId(), "TRIP_OFFERED")).isEqualTo(1);
        Integer vOffered = jdbc.queryForObject(
                "SELECT version FROM notifications WHERE user_id = ? AND type = 'TRIP_OFFERED'",
                Integer.class, o.passengerUserId());
        Integer evVersion = jdbc.queryForObject(
                "SELECT version FROM trip_events WHERE trip_id = ? AND type = 'DRIVER_OFFERED'",
                Integer.class, o.tripId());
        assertThat(vOffered).isEqualTo(evVersion);
    }

    /** Acceptance: "không thấy tài nguyên người khác" — stranger 404 trên events; notifications
     *  chỉ chứa thông báo của caller (user lạ → list rỗng). */
    @Test
    void strangersCannotSeeEventsOrNotifications() throws Exception {
        Offered o = startedTrip();

        // Assigned driver thấy events.
        Resp dev = get("/api/trips/" + o.tripId() + "/events", token(o.driverUserId(), "DRIVER"));
        assertThat(dev.status()).isEqualTo(200);
        assertThat(contentOf(dev)).isNotEmpty();

        // Driver khác (không assign) → 404, không lộ sự tồn tại trip (§1.2).
        Resp sev = get("/api/trips/" + o.tripId() + "/events", token(UUID.randomUUID(), "DRIVER"));
        assertThat(sev.status()).isEqualTo(404);

        // Passenger khác → notifications rỗng (chỉ thông báo của caller).
        Resp sn = get("/api/notifications", token(UUID.randomUUID(), "PASSENGER"));
        assertThat(sn.status()).isEqualTo(200);
        assertThat(contentOf(sn)).isEmpty();
        assertThat(sn.body().get("totalElements")).isEqualTo(0);
    }

    /** Acceptance: events endpoint phân trang, sắp version DESC — 4 events (TRIP_CREATED v1 →
     *  TRIP_STARTED v4), size=2 → 2 page; envelope như GET /api/trips. */
    @Test
    void eventsEndpointPaginatesVersionDesc() throws Exception {
        Offered o = startedTrip(); // TRIP_CREATED v1, DRIVER_OFFERED v2, TRIP_ACCEPTED v3, TRIP_STARTED v4

        Resp ev0 = get("/api/trips/" + o.tripId() + "/events?page=0&size=2", token(o.passengerUserId(), "PASSENGER"));
        assertThat(ev0.status()).isEqualTo(200);
        Map<String, Object> b0 = ev0.body();
        assertThat(b0.get("totalElements")).isEqualTo(4);
        assertThat(b0.get("totalPages")).isEqualTo(2);
        List<Map<String, Object>> c0 = contentOf(ev0);
        assertThat(c0).hasSize(2);
        assertThat((String) c0.get(0).get("type")).isEqualTo("TRIP_STARTED");   // version 4 — mới nhất trước
        assertThat(c0.get(0).get("version")).isEqualTo(4);
        assertThat((String) c0.get(1).get("type")).isEqualTo("TRIP_ACCEPTED");  // version 3

        Resp ev1 = get("/api/trips/" + o.tripId() + "/events?page=1&size=2", token(o.passengerUserId(), "PASSENGER"));
        List<Map<String, Object>> c1 = contentOf(ev1);
        assertThat(c1).hasSize(2);
        assertThat((String) c1.get(0).get("type")).isEqualTo("DRIVER_OFFERED"); // version 2
        assertThat((String) c1.get(1).get("type")).isEqualTo("TRIP_CREATED");   // version 1

        // Shape item theo contract §3.3: type/version/actorId/summary/createdAt.
        Map<String, Object> first = c0.get(0);
        assertThat(first.keySet()).containsExactlyInAnyOrder("type", "version", "actorId", "summary", "createdAt");
    }

    /** Acceptance: "retry không tạo thông báo trùng cho một transition" — 2 PaymentSucceeded eventId
     *  khác cùng trip/attempt → event thứ hai bị stale guard skip: đúng 1 notification + 1 trip_event,
     *  version bump MỘT lần. */
    @Test
    void duplicatePaymentEventDoesNotDuplicateNotification() throws Exception {
        Offered o = startedTrip();
        Resp c = complete(o.tripId(), o.driverUserId());
        assertThat(c.status()).isEqualTo(200);

        long fareVnd = jdbc.queryForObject("SELECT fare_vnd FROM trips WHERE id = ?", Long.class, o.tripId());
        int versionBefore = tripInt(o.tripId(), "version");

        UUID e1 = UUID.randomUUID();
        publishPaymentEvent("PaymentSucceeded", e1, UUID.randomUUID(), o.tripId(), UUID.randomUUID(), 1, fareVnd);
        waitForInboxRow(e1);

        UUID e2 = UUID.randomUUID(); // redelivery: eventId khác, cùng trip/attempt
        publishPaymentEvent("PaymentSucceeded", e2, UUID.randomUUID(), o.tripId(), UUID.randomUUID(), 1, fareVnd);
        waitForInboxRow(e2);

        assertThat(notificationCount(o.passengerUserId(), "PAYMENT_SUCCEEDED")).isEqualTo(1);
        assertThat(tripEventCount(o.tripId(), "PAYMENT_SUCCEEDED")).isEqualTo(1);
        assertThat(tripInt(o.tripId(), "version")).isEqualTo(versionBefore + 1); // bump đúng một lần
    }

    /** Chốt T25 — dedup theo transition: INSERT ON CONFLICT DO NOTHING trên unique
     *  (user_id, trip_id, version, type) — ghi lại notification của cùng transition không tạo row mới. */
    @Test
    void notificationInsertIsIdempotentPerTransition() throws Exception {
        Offered o = offeredTrip(); // đã có TRIP_OFFERED cho passenger tại version 2
        int vOffered = tripInt(o.tripId(), "version");

        notificationRepo.insert(o.passengerUserId(), o.tripId(), "TRIP_OFFERED", vOffered, "Tài xế đã sẵn sàng đón bạn.");
        notificationRepo.insert(o.driverUserId(), o.tripId(), "OFFER_RECEIVED", vOffered, "Bạn có một chuyến đi mới cần đón khách.");

        assertThat(notificationCount(o.passengerUserId(), "TRIP_OFFERED")).isEqualTo(1);
        assertThat(notificationCount(o.driverUserId(), "OFFER_RECEIVED")).isEqualTo(1);
    }

    /** Cancel từ DRIVER_OFFERED: passenger luôn nhận TRIP_CANCELLED; driver đã offer lúc hủy cũng nhận
     *  (trip row đọc TRƯỚC markCancelled — sau đó driver_user_id bị NULL). */
    @Test
    void cancelFromOfferedNotifiesPassengerAndDriver() throws Exception {
        Offered o = offeredTrip();
        Resp c = post("/api/trips/" + o.tripId() + "/cancel", token(o.passengerUserId(), "PASSENGER"), null);
        assertThat(c.status()).isEqualTo(202);
        assertThat((String) c.body().get("state")).isEqualTo("CANCELLED");

        Resp pn = get("/api/notifications", token(o.passengerUserId(), "PASSENGER"));
        Map<String, String> pmessages = messagesByType(contentOf(pn));
        assertThat(pmessages).containsEntry("TRIP_OFFERED", "Tài xế đã sẵn sàng đón bạn.")
                .containsEntry("TRIP_CANCELLED", "Chuyến đi đã bị hủy.");

        Resp dn = get("/api/notifications", token(o.driverUserId(), "DRIVER"));
        Map<String, String> dmessages = messagesByType(contentOf(dn));
        assertThat(dmessages).containsEntry("OFFER_RECEIVED", "Bạn có một chuyến đi mới cần đón khách.")
                .containsEntry("TRIP_CANCELLED", "Chuyến đi đã bị hủy.");

        // Sau cancel driver_user_id NULL → former driver không còn quyền xem trip/events (§1.2).
        Resp ev = get("/api/trips/" + o.tripId() + "/events", token(o.driverUserId(), "DRIVER"));
        assertThat(ev.status()).isEqualTo(404);
    }

    /** NO_DRIVER (nhánh completeReject, attempt đã hết): passenger nhận notification NO_DRIVER. */
    @Test
    void noDriverAfterExhaustedAttemptNotifiesPassenger() throws Exception {
        Offered o = offeredTrip(); // max-matching-attempts=1 → reject là lần cuối
        Resp rj = post("/api/trips/" + o.tripId() + "/reject", token(o.driverUserId(), "DRIVER"),
                "{\"attempt\":" + o.attempt() + "}");
        assertThat(rj.status()).isEqualTo(202);
        assertThat((String) rj.body().get("state")).isEqualTo("NO_DRIVER");

        Resp pn = get("/api/notifications", token(o.passengerUserId(), "PASSENGER"));
        Map<String, String> pmessages = messagesByType(contentOf(pn));
        assertThat(pmessages).containsEntry("TRIP_OFFERED", "Tài xế đã sẵn sàng đón bạn.")
                .containsEntry("NO_DRIVER", "Không tìm thấy tài xế cho chuyến đi này.");

        // Driver không nhận NO_DRIVER (chốt T25: passenger only).
        Resp dn = get("/api/notifications", token(o.driverUserId(), "DRIVER"));
        assertThat(messagesByType(contentOf(dn)).keySet()).containsExactly("OFFER_RECEIVED");
    }
}
