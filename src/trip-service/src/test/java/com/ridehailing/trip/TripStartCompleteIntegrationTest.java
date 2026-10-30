package com.ridehailing.trip;

import com.ridehailing.trip.service.SagaRecoveryWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T22 — start/complete + finish driver (spec §20.5, contract §3.3/§4.2): state guard chỉ assigned
 * driver, TripCompleted outbox (attempt 1, amountVnd = fare, currency VND, causation null), intent
 * finish bền (saga AWAITING_FINISH + FINISH_ASSIGNMENT) và worker resume khi HTTP chưa rõ.
 * Worker scheduled bị vô hiệu trong test (interval 1h) — test gọi {@code processDueSagas()} trực tiếp
 * để kiểm soát thời điểm; lease/CAS của worker vẫn chạy thật trên PostgreSQL.
 */
@SpringBootTest(classes = TripServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TripStartCompleteIntegrationTest extends TripCommandTestSupport {

    @DynamicPropertySource
    static void sagaWorkerProps(DynamicPropertyRegistry registry) {
        // Worker scheduled không được can thiệp vào test: lần chạy đầu sau 1h khởi động.
        registry.add("app.saga.worker-interval-ms", () -> "3600000");
    }

    @Autowired
    SagaRecoveryWorker worker;

    @Test
    void completeRemainsSuccessfulWhenFinishAndReconcileReturn503() throws Exception {
        Offered o = startedTrip();
        DRIVER.delayBeforeApplyCount.put("finish", 1);
        DRIVER.getFailureStatus = 503;

        Resp result = complete(o.tripId(), o.driverUserId());
        assertThat(result.status()).isEqualTo(200);
        assertThat(tripState(o.tripId())).isEqualTo("COMPLETED");
        assertThat(sagaPhase(o.tripId())).isEqualTo("AWAITING_FINISH");
        assertThat(tripCompletedPayloads(o.tripId())).hasSize(1);

        DRIVER.delayBeforeApplyCount.clear();
        DRIVER.getFailureStatus = 0;
        worker.processDueSagas();
        assertThat(sagaPhase(o.tripId())).isEqualTo("COMPLETED");
        assertThat(DRIVER.reservation(o.reservationId().toString()).terminalReason).isEqualTo("TRIP_COMPLETED");
    }

    @Test
    void completeRemainsSuccessfulWhenFinishErrorHasNoCode() throws Exception {
        Offered o = startedTrip();
        DRIVER.codelessErrorCount.put("finish", 1);

        Resp result = complete(o.tripId(), o.driverUserId());
        assertThat(result.status()).isEqualTo(200);
        assertThat(tripState(o.tripId())).isEqualTo("COMPLETED");
        assertThat(sagaPhase(o.tripId())).isEqualTo("AWAITING_FINISH");
        String lastError = jdbc.queryForObject("SELECT last_error FROM sagas WHERE trip_id = ?", String.class, o.tripId());
        assertThat(lastError).contains("finish pending");
        assertThat(tripCompletedPayloads(o.tripId())).hasSize(1);

        worker.processDueSagas();
        assertThat(sagaPhase(o.tripId())).isEqualTo("COMPLETED");
        assertThat(DRIVER.reservation(o.reservationId().toString()).terminalReason).isEqualTo("TRIP_COMPLETED");
    }

    /** Driver không liên quan không phân biệt được state của trip người khác qua lỗi trả về. */
    @Test
    void strangerDriverLearnsNothingAboutTripState() throws Exception {
        Offered o = startedTrip();
        UUID stranger = UUID.randomUUID();
        for (Resp r : List.of(start(o.tripId(), stranger), complete(o.tripId(), stranger))) {
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.body()).containsEntry("code", "NOT_ASSIGNED_DRIVER");
            assertThat(String.valueOf(r.body().get("details"))).doesNotContain("IN_PROGRESS");
        }
        assertThat(complete(o.tripId(), o.driverUserId()).status()).isEqualTo(200);
        for (Resp r : List.of(start(o.tripId(), stranger), complete(o.tripId(), stranger))) {
            assertThat(r.body()).containsEntry("code", "NOT_ASSIGNED_DRIVER");
        }
    }

    @Test
    void completeByWrongDriverDoesNotCreatePaymentOrFinishIntent() throws Exception {
        Offered o = startedTrip();
        Resp result = complete(o.tripId(), UUID.randomUUID());
        assertThat(result.status()).isEqualTo(409);
        assertThat(result.body()).containsEntry("code", "NOT_ASSIGNED_DRIVER");
        assertThat(tripState(o.tripId())).isEqualTo("IN_PROGRESS");
        assertThat(tripCompletedPayloads(o.tripId())).isEmpty();
        assertThat(DRIVER.countCalls("POST:" + o.reservationId() + "/finish")).isZero();
    }

    Resp start(UUID tripId, UUID callerUserId) throws Exception {
        return post("/api/trips/" + tripId + "/start", token(callerUserId, "DRIVER"), null);
    }

    Resp complete(UUID tripId, UUID callerUserId) throws Exception {
        return post("/api/trips/" + tripId + "/complete", token(callerUserId, "DRIVER"), null);
    }

    /** Payload các TripCompleted trong outbox của trip, theo thứ tự ghi. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tripCompletedPayloads(UUID tripId) {
        return jdbc.queryForList("SELECT payload::text FROM outbox WHERE aggregate_id = ? AND event_type = 'TripCompleted'"
                        + " ORDER BY created_at, id", String.class, tripId).stream()
                .map(s -> {
                    try {
                        return (Map<String, Object>) JSON.readValue(s.getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
    }

    /** offer → accept → start: chuyến IN_PROGRESS với đúng assigned driver. */
    Offered startedTrip() throws Exception {
        Offered o = offeredTrip();
        Resp acc = accept(o, o.driverUserId(), o.attempt());
        assertThat(acc.status()).isEqualTo(202);
        assertThat((String) acc.body().get("state")).isEqualTo("ACCEPTED");
        Resp st = start(o.tripId(), o.driverUserId());
        assertThat(st.status()).isEqualTo(200);
        assertThat((String) st.body().get("state")).isEqualTo("IN_PROGRESS");
        return o;
    }

    /** Assigned driver start → IN_PROGRESS + trip_event TRIP_STARTED; saga giữ AWAITING_FINISH (desired NONE). */
    @Test
    void startByAssignedDriverMovesToInProgress() throws Exception {
        Offered o = offeredTrip();
        Resp acc = accept(o, o.driverUserId(), o.attempt());
        assertThat(acc.status()).isEqualTo(202);

        Resp st = start(o.tripId(), o.driverUserId());
        assertThat(st.status()).isEqualTo(200);
        assertThat((String) st.body().get("tripId")).isEqualTo(o.tripId().toString());
        assertThat((String) st.body().get("state")).isEqualTo("IN_PROGRESS");

        assertThat(tripState(o.tripId())).isEqualTo("IN_PROGRESS");
        assertThat(tripEventTypes(o.tripId())).containsSubsequence("TRIP_ACCEPTED", "TRIP_STARTED");
        assertThat(sagaPhase(o.tripId())).isEqualTo("AWAITING_FINISH");
    }

    /** Driver không phải người được assign start → 409 NOT_ASSIGNED_DRIVER; state không đổi. */
    @Test
    void startByWrongDriverIs409NotAssignedDriver() throws Exception {
        Offered o = offeredTrip();
        Resp acc = accept(o, o.driverUserId(), o.attempt());
        assertThat(acc.status()).isEqualTo(202);

        Resp st = start(o.tripId(), UUID.randomUUID());
        assertThat(st.status()).isEqualTo(409);
        assertThat((String) st.body().get("code")).isEqualTo("NOT_ASSIGNED_DRIVER");
        assertThat(tripState(o.tripId())).isEqualTo("ACCEPTED"); // không đổi state
    }

    /** Start khi trip chưa ACCEPTED (DRIVER_OFFERED) → 409 STATE_CONFLICT. */
    @Test
    void startOnNonAcceptedStateIs409() throws Exception {
        Offered o = offeredTrip(); // DRIVER_OFFERED — chưa accept
        Resp st = start(o.tripId(), o.driverUserId());
        assertThat(st.status()).isEqualTo(409);
        assertThat((String) st.body().get("code")).isEqualTo("STATE_CONFLICT");
        assertThat(tripState(o.tripId())).isEqualTo("DRIVER_OFFERED");
    }

    /** Complete → COMPLETED + payment PENDING/attempt 1 + TripCompleted outbox đúng payload;
     *  finish inline thành công trên stub → saga terminal COMPLETED, driver RELEASED/TRIP_COMPLETED. */
    @Test
    void completePublishesTripCompletedAndReleasesDriver() throws Exception {
        Offered o = startedTrip();
        long fareVnd = jdbc.queryForObject("SELECT fare_vnd FROM trips WHERE id = ?", Long.class, o.tripId());

        Resp c = complete(o.tripId(), o.driverUserId());
        assertThat(c.status()).isEqualTo(200);
        assertThat((String) c.body().get("tripId")).isEqualTo(o.tripId().toString());
        assertThat((String) c.body().get("state")).isEqualTo("COMPLETED");
        assertThat((String) c.body().get("paymentStatus")).isEqualTo("PENDING");

        assertThat(tripState(o.tripId())).isEqualTo("COMPLETED");
        String paymentStatus = jdbc.queryForObject("SELECT payment_status FROM trips WHERE id = ?", String.class, o.tripId());
        assertThat(paymentStatus).isEqualTo("PENDING");
        assertThat(tripInt(o.tripId(), "payment_attempt")).isEqualTo(1);

        List<Map<String, Object>> payloads = tripCompletedPayloads(o.tripId());
        assertThat(payloads).hasSize(1);
        Map<String, Object> p = payloads.get(0);
        assertThat(p.get("tripId")).isEqualTo(o.tripId().toString());
        assertThat(((Number) p.get("attempt")).intValue()).isEqualTo(1);
        assertThat(((Number) p.get("amountVnd")).longValue()).isEqualTo(fareVnd);
        assertThat(p.get("currency")).isEqualTo("VND");

        // causation null — HTTP-triggered (events.md §5); routing key trip.completed (§3.4).
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT causation_id, routing_key FROM outbox WHERE aggregate_id = ? AND event_type = 'TripCompleted'",
                o.tripId());
        assertThat(row.get("causation_id")).isNull();
        assertThat((String) row.get("routing_key")).isEqualTo("trip.completed");

        assertThat(tripEventTypes(o.tripId())).containsSubsequence("TRIP_STARTED", "TRIP_COMPLETED");
        // finish thành công inline → saga terminal COMPLETED, driver được giải phóng.
        assertThat(sagaPhase(o.tripId())).isEqualTo("COMPLETED");
        StatefulDriverStub.Reservation res = DRIVER.reservation(o.reservationId().toString());
        assertThat(res.state).isEqualTo("RELEASED");
        assertThat(res.terminalReason).isEqualTo("TRIP_COMPLETED");
    }

    /** Complete lặp trên COMPLETED → 409 STATE_CONFLICT, không phát intent/event nghiệp vụ mới;
     *  trip COMPLETED cũng không start được (invariant 5). */
    @Test
    void completeRepeatIs409AndNoNewOutboxRow() throws Exception {
        Offered o = startedTrip();
        Resp c1 = complete(o.tripId(), o.driverUserId());
        assertThat(c1.status()).isEqualTo(200);

        Resp c2 = complete(o.tripId(), o.driverUserId());
        assertThat(c2.status()).isEqualTo(409);
        assertThat((String) c2.body().get("code")).isEqualTo("STATE_CONFLICT");

        // Không có row TripCompleted thứ hai, payment_attempt giữ 1.
        assertThat(tripCompletedPayloads(o.tripId())).hasSize(1);
        assertThat(tripInt(o.tripId(), "payment_attempt")).isEqualTo(1);

        Resp st = start(o.tripId(), o.driverUserId());
        assertThat(st.status()).isEqualTo(409);
        assertThat((String) st.body().get("code")).isEqualTo("STATE_CONFLICT");
    }

    /** Finish timeout (HTTP + GET đều mù) → trip vẫn COMPLETED, intent finish bền trong saga;
     *  worker resume với cùng commandId tất định → saga COMPLETED terminal, driver RELEASED. */
    @Test
    void completeFinishFaultKeepsPendingIntentAndWorkerResumes() throws Exception {
        Offered o = startedTrip();

        // Tiêm lỗi: finish đầu không tới được Driver (503 sau read timeout), GET reconcile cũng timeout.
        DRIVER.delayBeforeApplyCount.put("finish", 1);
        DRIVER.failGet = 1;

        Resp c = complete(o.tripId(), o.driverUserId());
        assertThat(c.status()).isEqualTo(200); // trip COMPLETED bất kể kết quả finish
        assertThat((String) c.body().get("state")).isEqualTo("COMPLETED");
        assertThat((String) c.body().get("paymentStatus")).isEqualTo("PENDING");

        // Intent bền: saga AWAITING_FINISH + FINISH_ASSIGNMENT, pending error đã ghi.
        assertThat(tripState(o.tripId())).isEqualTo("COMPLETED");
        assertThat(sagaPhase(o.tripId())).isEqualTo("AWAITING_FINISH");
        String desired = jdbc.queryForObject("SELECT desired_action FROM sagas WHERE trip_id = ?", String.class, o.tripId());
        assertThat(desired).isEqualTo("FINISH_ASSIGNMENT");
        String lastError = jdbc.queryForObject("SELECT last_error FROM sagas WHERE trip_id = ?", String.class, o.tripId());
        assertThat(lastError).isNotBlank();
        assertThat(tripCompletedPayloads(o.tripId())).hasSize(1);

        // Bỏ lỗi → worker resume finish (commandId tất định theo reservation — replay an toàn).
        DRIVER.delayBeforeApplyCount.remove("finish");
        DRIVER.failGet = 0;
        worker.processDueSagas();

        assertThat(sagaPhase(o.tripId())).isEqualTo("COMPLETED");
        StatefulDriverStub.Reservation res = DRIVER.reservation(o.reservationId().toString());
        assertThat(res.state).isEqualTo("RELEASED");
        assertThat(res.terminalReason).isEqualTo("TRIP_COMPLETED");
        // Finish đầu timeout trước khi áp dụng; retry của worker là lần gọi thứ hai.
        assertThat(DRIVER.countCalls("POST:" + o.reservationId() + "/finish")).isEqualTo(2);
    }
}
