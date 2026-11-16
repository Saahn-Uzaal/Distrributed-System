package com.ridehailing.trip;

import com.ridehailing.trip.event.EventEnvelope;
import com.ridehailing.trip.service.SagaRecoveryWorker;
import com.ridehailing.trip.service.TripInboxHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T21 — worker phục hồi Saga bền vững (spec §20.5, state-machines.md §2 "Quy tắc worker"):
 * offer timeout, deadline tổng tìm tài xế, orphan confirmation/compensation và restart tại các phase.
 * Worker scheduled bị vô hiệu trong test (interval 1h) — test gọi {@code processDueSagas()} trực tiếp
 * để kiểm soát thời điểm; lease/CAS của worker vẫn chạy thật trên PostgreSQL.
 */
@SpringBootTest(classes = TripServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TripSagaRecoveryIntegrationTest extends TripCommandTestSupport {

    @DynamicPropertySource
    static void sagaWorkerProps(DynamicPropertyRegistry registry) {
        // Worker scheduled không được can thiệp vào test: lần chạy đầu sau 1h khởi động.
        registry.add("app.saga.worker-interval-ms", () -> "3600000");
    }

    @Autowired
    SagaRecoveryWorker worker;

    @Autowired
    com.ridehailing.trip.service.TripCommandService commands;

    @Test
    void staleExpiryDispatchDoesNotReleaseAFreshOffer() throws Exception {
        Offered o = offeredTrip();
        // A worker can be delayed after claim while another worker advances the saga.
        commands.handleOfferExpired(o.tripId());
        assertThat(tripState(o.tripId())).isEqualTo("DRIVER_OFFERED");
        assertThat(DRIVER.countCalls("POST:" + o.reservationId() + "/release")).isZero();
    }

    @Test
    void expiryKeepsDurableIntentUntilReleaseIsKnown() throws Exception {
        Offered o = offeredTrip();
        backdateOfferExpiresAt(o.tripId());
        DRIVER.delayBeforeApplyCount.put("release", 1);
        DRIVER.failGet = 1;
        worker.processDueSagas();

        assertThat(sagaPhase(o.tripId())).isEqualTo("COMPENSATING");
        assertThat(accept(o, o.driverUserId(), o.attempt()).status()).isEqualTo(409);
        DRIVER.delayBeforeApplyCount.clear();
        DRIVER.failGet = 0;
        jdbc.update("UPDATE sagas SET next_retry_at = NULL WHERE trip_id = ?", o.tripId());
        worker.processDueSagas();

        assertThat(tripState(o.tripId())).isEqualTo("SEARCHING");
        assertThat(tripEventTypes(o.tripId())).contains("OFFER_EXPIRED").doesNotContain("OFFER_REJECTED");
        assertThat(DRIVER.reservation(o.reservationId().toString()).terminalReason).isEqualTo("OFFER_EXPIRED");
    }

    @Test
    void lateProposalCannotAbandonPendingDeadlineCleanup() throws Exception {
        UUID tripId = createTrip(UUID.randomUUID());
        jdbc.update("UPDATE sagas SET deadline_at = clock_timestamp() - interval '1 second' WHERE trip_id = ?", tripId);
        DRIVER.delayBeforeApplyCount.put("release", 1);
        DRIVER.failGet = 1;
        worker.processDueSagas();

        assertThat(proposeAndCatch(tripId, 1)).isNull();
        assertThat(tripState(tripId)).isEqualTo("SEARCHING");
        DRIVER.delayBeforeApplyCount.clear();
        DRIVER.failGet = 0;
        jdbc.update("UPDATE sagas SET next_retry_at = NULL WHERE trip_id = ?", tripId);
        worker.processDueSagas();

        assertThat(tripState(tripId)).isEqualTo("NO_DRIVER");
        for (int i = 0; i < 5; i++) {
            var reservation = DRIVER.reservation(TripInboxHandler.deterministicReservationId(tripId, 1, i).toString());
            assertThat(reservation).isNotNull();
            assertThat(reservation.state).isEqualTo("RELEASED");
        }
    }

    @Test
    void proposalAfterDeadlineLeavesCleanupForWorker() throws Exception {
        UUID tripId = createTrip(UUID.randomUUID());
        jdbc.update("UPDATE sagas SET deadline_at = clock_timestamp() - interval '1 second' WHERE trip_id = ?", tripId);
        assertThat(proposeAndCatch(tripId, 1)).isNull();
        worker.processDueSagas();
        assertThat(tripState(tripId)).isEqualTo("NO_DRIVER");
        for (int i = 0; i < 5; i++) {
            assertThat(DRIVER.reservation(TripInboxHandler.deterministicReservationId(tripId, 1, i).toString())).isNotNull();
        }
    }

    Resp reject(Offered o, UUID callerUserId, int attempt) throws Exception {
        return post("/api/trips/" + o.tripId() + "/reject", token(callerUserId, "DRIVER"), "{\"attempt\":" + attempt + "}");
    }

    Resp cancel(UUID tripId, UUID passengerUserId) throws Exception {
        return post("/api/trips/" + tripId + "/cancel", token(passengerUserId, "PASSENGER"), null);
    }

    /** Gọi handler T16 với DriverProposed (1 candidate) cho attempt; trả lỗi nếu handler ném. */
    Throwable proposeAndCatch(UUID tripId, int attempt) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("driverId", UUID.randomUUID().toString());
        candidate.put("driverUserId", UUID.randomUUID().toString());
        candidate.put("distanceM", 300);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("attempt", attempt);
        payload.put("candidates", List.of(candidate));
        EventEnvelope env = new EventEnvelope(UUID.randomUUID(), "DriverProposed", 1, Instant.now(), "matching-service",
                UUID.randomUUID(), null, tripId, null, payload);
        try {
            new TransactionTemplate(txManager).executeWithoutResult(s -> inboxHandler.handle(env));
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /** Payload các TripRequested trong outbox của trip, theo thứ tự ghi. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tripRequestedPayloads(UUID tripId) {
        return jdbc.queryForList("SELECT payload::text FROM outbox WHERE aggregate_id = ? AND event_type = 'TripRequested'"
                        + " ORDER BY created_at, id", String.class, tripId).stream()
                .map(s -> {
                    try {
                        return (Map<String, Object>) JSON.readValue(s.getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
    }

    /** Đẩy offer TTL trong saga context về quá khứ để findActionableDue coi là đến hạn. */
    void backdateOfferExpiresAt(UUID tripId) {
        jdbc.update("UPDATE sagas SET context = jsonb_set(context, '{offerExpiresAt}', ?::jsonb)"
                + " WHERE trip_id = ?", "\"" + Instant.now().minusSeconds(1).toString() + "\"", tripId);
    }

    /** Offer TTL hết hạn không ai phản hồi → worker release OFFER_EXPIRED rồi tìm lại (attempt+1, loại driver). */
    @Test
    void offerExpiryReleasesAndRematches() throws Exception {
        Offered o = offeredTrip();
        backdateOfferExpiresAt(o.tripId());
        DRIVER.expire(o.reservationId().toString());

        worker.processDueSagas();

        StatefulDriverStub.Reservation res = DRIVER.reservation(o.reservationId().toString());
        assertThat(res.state).isIn("RELEASED", "EXPIRED"); // terminal — tài xế được giải phóng
        assertThat(DRIVER.countCalls("POST:" + o.reservationId() + "/release")).isEqualTo(1);

        assertThat(tripState(o.tripId())).isEqualTo("SEARCHING");
        assertThat(tripInt(o.tripId(), "matching_attempt")).isEqualTo(2);
        Map<String, Object> drivers = jdbc.queryForMap(
                "SELECT driver_id, driver_user_id, reservation_id FROM trips WHERE id = ?", o.tripId());
        assertThat(drivers.values()).containsOnlyNulls(); // N22: xóa khi release
        assertThat(sagaPhase(o.tripId())).isEqualTo("WAITING_MATCH");
        assertThat(tripEventTypes(o.tripId())).containsSubsequence("OFFER_EXPIRED", "RESERVATION_RELEASED");

        List<Map<String, Object>> requests = tripRequestedPayloads(o.tripId());
        assertThat(requests).hasSize(2); // attempt 1 lúc tạo + attempt 2 sau expiry
        assertThat(((Number) requests.get(1).get("attempt")).intValue()).isEqualTo(2);
        assertThat(requests.get(1).get("excludedDriverIds")).isEqualTo(List.of(o.driverId().toString()));
    }

    /** Spec §11: worker gọi Driver và phát lại TripRequested bằng correlation của request tạo chuyến. */
    @Test
    void offerExpiryKeepsOriginCorrelationOnDriverCallAndEvent() throws Exception {
        Offered o = offeredTrip();
        backdateOfferExpiresAt(o.tripId());
        DRIVER.expire(o.reservationId().toString());
        UUID origin = jdbc.queryForObject("SELECT correlation_id FROM outbox WHERE aggregate_id = ?"
                + " AND event_type = 'TripRequested'", UUID.class, o.tripId());

        worker.processDueSagas();

        assertThat(DRIVER.correlationIds.get("POST:" + o.reservationId() + "/release"))
                .isEqualTo(origin.toString());
        assertThat(jdbc.queryForList("SELECT correlation_id FROM outbox WHERE aggregate_id = ?"
                + " AND event_type = 'TripRequested'", UUID.class, o.tripId()))
                .hasSize(2).containsOnly(origin);
    }

    /** Contract §5: request reject truyền X-Correlation-ID của nó sang lệnh release ở Driver. */
    @Test
    void rejectForwardsRequestCorrelationToDriver() throws Exception {
        Offered o = offeredTrip();
        String correlationId = UUID.randomUUID().toString();

        Resp r = post("/api/trips/" + o.tripId() + "/reject", token(o.driverUserId(), "DRIVER"),
                "{\"attempt\":" + o.attempt() + "}", Map.of("X-Correlation-ID", correlationId));

        assertThat(r.status()).isEqualTo(202);
        assertThat(DRIVER.correlationIds.get("POST:" + o.reservationId() + "/release")).isEqualTo(correlationId);
    }

    /** Offer hết hạn ở mọi lượt (3 driver khác nhau, không ai phản hồi) → NO_DRIVER terminal, saga ABORTED. */
    @Test
    void offerExpiryOnEveryAttemptEndsWithNoDriver() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID tripId = createTrip(passenger);

        for (int attempt = 1; attempt <= 3; attempt++) {
            Offered o = offer(tripId, passenger);
            assertThat(o.attempt()).isEqualTo(attempt);
            backdateOfferExpiresAt(tripId);
            DRIVER.expire(o.reservationId().toString());
            worker.processDueSagas();
        }

        assertThat(tripState(tripId)).isEqualTo("NO_DRIVER");
        assertThat(sagaPhase(tripId)).isEqualTo("ABORTED");
        assertThat(tripEventTypes(tripId)).endsWith("NO_DRIVER");
        assertThat(tripRequestedPayloads(tripId)).hasSize(3); // attempt 1, 2, 3 — không có attempt 4
    }

    /** Deadline tổng tìm tài xế đã qua mà chưa có offer (matching im lặng/crash) → worker tombstone MỌI
     *  reservationId tất định của attempt hiện tại rồi NO_DRIVER; proposal/reserve tới trễ không hồi sinh. */
    @Test
    void waitingMatchDeadlineTombstonesAndEndsWithNoDriver() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID tripId = createTrip(passenger);

        jdbc.update("UPDATE sagas SET deadline_at = clock_timestamp() - interval '1 second' WHERE trip_id = ?", tripId);

        worker.processDueSagas();

        for (int i = 0; i < 5; i++) { // maxCandidates
            UUID rid = TripInboxHandler.deterministicReservationId(tripId, 1, i);
            StatefulDriverStub.Reservation r = DRIVER.reservation(rid.toString());
            assertThat(r).isNotNull();
            assertThat(r.state).isEqualTo("RELEASED"); // tombstone
        }
        assertThat(tripState(tripId)).isEqualTo("NO_DRIVER");
        assertThat(sagaPhase(tripId)).isEqualTo("ABORTED");
        assertThat(tripEventTypes(tripId)).contains("NO_DRIVER");

        // Proposal tới trễ bị bỏ qua (stale) và không có reserve nào — tombstone không hồi sinh.
        assertThat(proposeAndCatch(tripId, 1)).isNull();
        assertThat(DRIVER.countCalls("PUT:")).isZero();
    }

    /** CONFIRMED không bị giải phóng do offer TTL (invariant 8): worker không làm gì với AWAITING_FINISH. */
    @Test
    void confirmedNotReleasedByOfferTtl() throws Exception {
        Offered o = offeredTrip();
        Resp r = accept(o, o.driverUserId(), o.attempt());
        assertThat(r.body()).containsEntry("state", "ACCEPTED");

        backdateOfferExpiresAt(o.tripId());
        DRIVER.expire(o.reservationId().toString());

        worker.processDueSagas();

        assertThat(DRIVER.countCalls("POST:" + o.reservationId() + "/release")).isZero();
        assertThat(DRIVER.reservation(o.reservationId().toString()).state).isEqualTo("CONFIRMED");
        assertThat(tripState(o.tripId())).isEqualTo("ACCEPTED");
        assertThat(sagaPhase(o.tripId())).isEqualTo("AWAITING_FINISH");
    }

    /** Orphan CONFIRMING (accept timeout, tiến trình chết) → worker resume confirm idempotent rồi ACCEPTED. */
    @Test
    void workerResumesPendingConfirm() throws Exception {
        Offered o = offeredTrip();
        DRIVER.delayBeforeApplyCount.put("confirm", 1);
        DRIVER.failGet = 1;

        Resp pending = accept(o, o.driverUserId(), o.attempt());
        assertThat(pending.status()).isEqualTo(202);
        assertThat(pending.body()).containsEntry("state", "DRIVER_OFFERED");
        assertThat(sagaPhase(o.tripId())).isEqualTo("CONFIRMING");

        // Xóa lỗi mà KHÔNG reset() (reset sẽ xóa luôn reservations).
        DRIVER.delayBeforeApplyCount.remove("confirm");
        DRIVER.failGet = 0;

        worker.processDueSagas();

        assertThat(tripState(o.tripId())).isEqualTo("ACCEPTED");
        assertThat(sagaPhase(o.tripId())).isEqualTo("AWAITING_FINISH");
        assertThat(DRIVER.reservation(o.reservationId().toString()).state).isEqualTo("CONFIRMED");
        // Lần confirm 503 không được parse → chỉ còn body confirm thành công: một commandId duy nhất.
        assertThat(DRIVER.commandBodies).hasSize(1);
    }

    /** Orphan COMPENSATING/REJECT (release timeout) → worker resume release rồi tìm lại attempt+1. */
    @Test
    void workerResumesPendingReject() throws Exception {
        Offered o = offeredTrip();
        DRIVER.delayBeforeApplyCount.put("release", 1);
        DRIVER.failGet = 1;

        Resp pending = reject(o, o.driverUserId(), o.attempt());
        assertThat(pending.status()).isEqualTo(202);
        assertThat(pending.body()).containsEntry("state", "DRIVER_OFFERED");
        assertThat(sagaPhase(o.tripId())).isEqualTo("COMPENSATING");
        assertThat(tripRequestedPayloads(o.tripId())).hasSize(1); // chưa phát attempt 2

        DRIVER.delayBeforeApplyCount.remove("release");
        DRIVER.failGet = 0;

        worker.processDueSagas();

        assertThat(tripState(o.tripId())).isEqualTo("SEARCHING");
        assertThat(tripInt(o.tripId(), "matching_attempt")).isEqualTo(2);
        StatefulDriverStub.Reservation res = DRIVER.reservation(o.reservationId().toString());
        assertThat(res.state).isEqualTo("RELEASED");
        assertThat(res.terminalReason).isEqualTo("REJECTED");
        assertThat(sagaPhase(o.tripId())).isEqualTo("WAITING_MATCH");
        List<Map<String, Object>> requests = tripRequestedPayloads(o.tripId());
        assertThat(requests).hasSize(2);
        assertThat(((Number) requests.get(1).get("attempt")).intValue()).isEqualTo(2);
    }

    /** Orphan COMPENSATING/CANCEL (release timeout) → worker resume release rồi CANCELLED. */
    @Test
    void workerResumesPendingCancel() throws Exception {
        Offered o = offeredTrip();
        DRIVER.delayBeforeApplyCount.put("release", 1);
        DRIVER.failGet = 1;

        Resp pending = cancel(o.tripId(), o.passengerUserId());
        assertThat(pending.status()).isEqualTo(202);
        assertThat(pending.body()).containsEntry("state", "CANCELLING");
        assertThat(tripState(o.tripId())).isEqualTo("CANCELLING");
        assertThat(sagaPhase(o.tripId())).isEqualTo("COMPENSATING");

        DRIVER.delayBeforeApplyCount.remove("release");
        DRIVER.failGet = 0;

        worker.processDueSagas();

        assertThat(tripState(o.tripId())).isEqualTo("CANCELLED");
        assertThat(sagaPhase(o.tripId())).isEqualTo("ABORTED");
        StatefulDriverStub.Reservation res = DRIVER.reservation(o.reservationId().toString());
        assertThat(res.state).isEqualTo("RELEASED");
        assertThat(res.terminalReason).isEqualTo("CANCELLED");
        assertThat(tripEventTypes(o.tripId())).containsSubsequence("CANCELLING", "RESERVATION_RELEASED", "TRIP_CANCELLED");
    }
}
