package com.ridehailing.trip;

import com.ridehailing.trip.event.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T20 — cancel và tranh chấp với accept (spec §10, §20.5; contract §3.3 POST /api/trips/{id}/cancel).
 * Acceptance: cancel/accept đồng thời chỉ một intent thắng; chuyến đã hủy không start được (terminal, mọi
 * lệnh sau bị 409); driver được reconcile đúng; cancel sau ACCEPTED trả 409.
 */
@SpringBootTest(classes = TripServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TripCancelIntegrationTest extends TripCommandTestSupport {

    Resp cancel(UUID tripId, UUID passengerUserId) throws Exception {
        return post("/api/trips/" + tripId + "/cancel", token(passengerUserId, "PASSENGER"), null);
    }

    /** Có reservation nào phía Driver vẫn đang giữ tài xế (RESERVED/CONFIRMED) không. */
    boolean driverStillHeld() {
        synchronized (DRIVER) {
            return DRIVER.reservations.values().stream()
                    .anyMatch(r -> "RESERVED".equals(r.state) || "CONFIRMED".equals(r.state));
        }
    }

    /** Gọi handler T16 với DriverProposed (1 candidate) cho attempt hiện tại; trả lỗi nếu handler ném. */
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

    /** SEARCHING → 200 CANCELLED; tombstone cho mọi reservationId tất định của attempt; proposal tới trễ bị bỏ qua. */
    @Test
    void cancelWhileSearchingTombstonesPossibleReservations() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID tripId = createTrip(passenger);

        Resp r = cancel(tripId, passenger);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).containsEntry("state", "CANCELLED");
        assertThat(tripState(tripId)).isEqualTo("CANCELLED");
        assertThat(sagaPhase(tripId)).isEqualTo("ABORTED");
        assertThat(tripEventTypes(tripId)).containsSubsequence("CANCELLING", "TRIP_CANCELLED");
        assertThat(DRIVER.countCalls("POST:")).isEqualTo(5); // MATCHING_MAX_CANDIDATES reservationId tất định
        synchronized (DRIVER) {
            assertThat(DRIVER.reservations.values()).allMatch(res -> "RELEASED".equals(res.state));
        }

        assertThat(proposeAndCatch(tripId, 1)).isNull(); // stale → bỏ qua, ACK
        assertThat(DRIVER.countCalls("PUT:")).isZero();
        assertThat(driverStillHeld()).isFalse();

        // Ràng buộc một chuyến active (T12-6): sau khi hủy, passenger tạo được chuyến mới.
        assertThat(createTrip(passenger)).isNotEqualTo(tripId);
    }

    /** Reserve đang bay (chưa tới Driver) khi cancel: tombstone tới trước → reserve trễ bị từ chối → không giữ tài xế. */
    @Test
    void cancelBeatsInFlightReserveViaTombstone() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID tripId = createTrip(passenger);
        DRIVER.slowBeforeApplyMs.put("reserve", 500L);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Throwable> listener = pool.submit(() -> proposeAndCatch(tripId, 1));
        Thread.sleep(150); // reserve đang ngủ trong stub, chưa áp dụng

        Resp r = cancel(tripId, passenger);
        listener.get();
        pool.shutdown();

        assertThat(r.body()).containsEntry("state", "CANCELLED");
        assertThat(tripState(tripId)).isEqualTo("CANCELLED");
        assertThat(driverStillHeld()).isFalse();
    }

    /** Reserve ĐÃ áp dụng nhưng listener chưa commit khi cancel: cancel release đúng reservation đó. */
    @Test
    void cancelReleasesReservationMadeByUncommittedListener() throws Exception {
        UUID passenger = UUID.randomUUID();
        UUID tripId = createTrip(passenger);
        DRIVER.delayAfterApplyMs.put("reserve", 500L);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Throwable> listener = pool.submit(() -> proposeAndCatch(tripId, 1));
        Thread.sleep(150);

        Resp r = cancel(tripId, passenger);
        Throwable listenerError = listener.get();
        pool.shutdown();

        assertThat(r.body()).containsEntry("state", "CANCELLED");
        assertThat(listenerError).isNotNull(); // CAS saga thua → rollback (listener thật sẽ retry rồi bỏ qua stale)
        assertThat(tripState(tripId)).isEqualTo("CANCELLED");
        assertThat(driverStillHeld()).isFalse();
    }

    /** DRIVER_OFFERED → release reason CANCELLED → 202 CANCELLED; trip không còn driver. */
    @Test
    void cancelWhileOfferedReleasesTheOfferedReservation() throws Exception {
        Offered o = offeredTrip();

        Resp r = cancel(o.tripId(), o.passengerUserId());

        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body()).containsEntry("state", "CANCELLED");
        StatefulDriverStub.Reservation res = DRIVER.reservation(o.reservationId().toString());
        assertThat(res.state).isEqualTo("RELEASED");
        assertThat(res.terminalReason).isEqualTo("CANCELLED");
        assertThat(tripEventTypes(o.tripId())).containsSubsequence("CANCELLING", "RESERVATION_RELEASED", "TRIP_CANCELLED");
        Map<String, Object> drivers = jdbc.queryForMap("SELECT driver_id, driver_user_id FROM trips WHERE id = ?", o.tripId());
        assertThat(drivers.values()).containsOnlyNulls();

        // Chuyến đã hủy là terminal: accept về sau bị từ chối, Driver không nhận lệnh confirm nào.
        Resp lateAccept = accept(o, o.driverUserId(), o.attempt());
        assertThat(lateAccept.status()).isEqualTo(409);
        assertThat(lateAccept.body()).containsEntry("code", "STATE_CONFLICT");
        assertThat(DRIVER.countCalls("POST:" + o.reservationId() + "/confirm")).isZero();
    }

    /** Cancel sau khi accept thắng → 409 STATE_CONFLICT, assignment giữ nguyên. */
    @Test
    void cancelAfterAcceptedIsRejected() throws Exception {
        Offered o = offeredTrip();
        assertThat(accept(o, o.driverUserId(), o.attempt()).body()).containsEntry("state", "ACCEPTED");

        Resp r = cancel(o.tripId(), o.passengerUserId());

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).containsEntry("code", "STATE_CONFLICT");
        assertThat(tripState(o.tripId())).isEqualTo("ACCEPTED");
        assertThat(DRIVER.reservation(o.reservationId().toString()).state).isEqualTo("CONFIRMED");
    }

    /** Owner khác → 404; driver → 403; hủy lặp lại → 200 CANCELLED (idempotent, không gọi Driver thêm). */
    @Test
    void ownershipRoleAndIdempotency() throws Exception {
        Offered o = offeredTrip();
        assertThat(cancel(o.tripId(), UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(post("/api/trips/" + o.tripId() + "/cancel", token(o.driverUserId(), "DRIVER"), null).status())
                .isEqualTo(403);
        assertThat(DRIVER.countCalls("POST:")).isZero();

        assertThat(cancel(o.tripId(), o.passengerUserId()).body()).containsEntry("state", "CANCELLED");
        long calls = DRIVER.countCalls("POST:");
        Resp again = cancel(o.tripId(), o.passengerUserId());
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body()).containsEntry("state", "CANCELLED");
        assertThat(DRIVER.countCalls("POST:")).isEqualTo(calls);
    }

    /** Release chưa rõ kết quả → 202 CANCELLING (không báo CANCELLED khi chưa chắc driver được giải phóng);
     *  accept trong lúc đó bị 409; gọi cancel lại → CANCELLED. */
    @Test
    void unknownReleaseKeepsCancellingUntilReconciled() throws Exception {
        Offered o = offeredTrip();
        DRIVER.delayBeforeApplyCount.put("release", 1);
        DRIVER.failGet = 1;

        Resp pending = cancel(o.tripId(), o.passengerUserId());
        assertThat(pending.status()).isEqualTo(202);
        assertThat(pending.body()).containsEntry("state", "CANCELLING");
        assertThat(tripState(o.tripId())).isEqualTo("CANCELLING");
        assertThat(accept(o, o.driverUserId(), o.attempt()).status()).isEqualTo(409);

        Resp done = cancel(o.tripId(), o.passengerUserId());
        assertThat(done.body()).containsEntry("state", "CANCELLED");
        assertThat(DRIVER.reservation(o.reservationId().toString()).state).isEqualTo("RELEASED");
    }

    /** C02 — accept và cancel đồng thời (20 vòng, mỗi bên trễ ngẫu nhiên 0–4 ms sau barrier để cả hai bên đều có lúc tới trước): đúng một intent thắng; trạng thái trip luôn khớp Driver;
     *  không có vòng nào trip CANCELLED mà tài xế vẫn bị giữ. */
    @Test
    void concurrentAcceptAndCancelHaveExactlyOneWinner() throws Exception {
        int accepted = 0;
        int cancelled = 0;
        java.util.Random jitter = new java.util.Random();
        for (int round = 0; round < 20; round++) {
            Offered o = offeredTrip();
            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            long acceptDelay = jitter.nextInt(5);
            long cancelDelay = jitter.nextInt(5);
            Future<Resp> acc = pool.submit(() -> {
                barrier.await();
                Thread.sleep(acceptDelay);
                return accept(o, o.driverUserId(), o.attempt());
            });
            Future<Resp> can = pool.submit(() -> {
                barrier.await();
                Thread.sleep(cancelDelay);
                return cancel(o.tripId(), o.passengerUserId());
            });
            Resp a = acc.get();
            Resp c = can.get();
            pool.shutdown();

            String state = tripState(o.tripId());
            String reservation = DRIVER.reservation(o.reservationId().toString()).state;
            if ("ACCEPTED".equals(state)) {
                accepted++;
                assertThat(a.body()).containsEntry("state", "ACCEPTED");
                assertThat(c.status()).isEqualTo(409);
                assertThat(reservation).isEqualTo("CONFIRMED");
            } else {
                cancelled++;
                assertThat(state).isEqualTo("CANCELLED");
                assertThat(c.body()).containsEntry("state", "CANCELLED");
                assertThat(a.status()).isEqualTo(409);
                assertThat(reservation).isEqualTo("RELEASED");
            }
            cleanTables();
            DRIVER.reset();
        }
        assertThat(accepted + cancelled).isEqualTo(20);
        System.out.println("C02 rounds: accept won=" + accepted + ", cancel won=" + cancelled);
    }
}
