package com.ridehailing.trip;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T18 — accept chuyến (spec §20.5, contract §3.3 POST /api/trips/{id}/accept).
 * Acceptance: sai driver bị chặn; accept lặp không thêm assignment; confirm timeout dùng GET reconcile,
 * không xác nhận giả.
 */
@SpringBootTest(classes = TripServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TripAcceptIntegrationTest extends TripCommandTestSupport {

    /** Offered driver accept → Driver confirm (version 2) → trip ACCEPTED, saga AWAITING_FINISH, trip_event. */
    @Test
    void offeredDriverAcceptConfirmsReservationAndMarksTripAccepted() throws Exception {
        Offered o = offeredTrip();

        Resp r = accept(o, o.driverUserId(), o.attempt());

        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body()).containsEntry("state", "ACCEPTED").containsEntry("tripId", o.tripId().toString());
        assertThat(tripState(o.tripId())).isEqualTo("ACCEPTED");
        assertThat(sagaPhase(o.tripId())).isEqualTo("AWAITING_FINISH");
        assertThat(sagaContext(o.tripId())).doesNotContainKey("intent");
        assertThat(DRIVER.reservation(o.reservationId().toString()).state).isEqualTo("CONFIRMED");
        assertThat(tripEventTypes(o.tripId())).endsWith("TRIP_ACCEPTED");
        Map<String, Object> confirm = DRIVER.commandBodies.get(0);
        assertThat(((Number) confirm.get("commandVersion")).intValue()).isEqualTo(2);
        assertThat(confirm).containsEntry("tripId", o.tripId().toString())
                .containsEntry("driverId", o.driverId().toString());
    }

    /** Sai driver / sai attempt → 409 NOT_OFFERED_DRIVER; passenger → 403; trip lạ → 404; thiếu attempt → 400.
     *  Không lệnh nào tới Driver và state không đổi. */
    @Test
    void wrongCallerOrAttemptIsRejectedWithoutTouchingDriver() throws Exception {
        Offered o = offeredTrip();

        Resp other = accept(o, UUID.randomUUID(), o.attempt());
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.body()).containsEntry("code", "NOT_OFFERED_DRIVER");

        Resp wrongAttempt = accept(o, o.driverUserId(), o.attempt() + 1);
        assertThat(wrongAttempt.status()).isEqualTo(409);
        assertThat(wrongAttempt.body()).containsEntry("code", "NOT_OFFERED_DRIVER");

        Resp passenger = post("/api/trips/" + o.tripId() + "/accept", token(o.passengerUserId(), "PASSENGER"),
                "{\"attempt\":" + o.attempt() + "}");
        assertThat(passenger.status()).isEqualTo(403);

        Resp unknown = post("/api/trips/" + UUID.randomUUID() + "/accept", token(o.driverUserId(), "DRIVER"),
                "{\"attempt\":1}");
        assertThat(unknown.status()).isEqualTo(404);

        Resp missing = post("/api/trips/" + o.tripId() + "/accept", token(o.driverUserId(), "DRIVER"), "{}");
        assertThat(missing.status()).isEqualTo(400);

        assertThat(DRIVER.countCalls("POST:")).isZero();
        assertThat(tripState(o.tripId())).isEqualTo("DRIVER_OFFERED");
        assertThat(sagaPhase(o.tripId())).isEqualTo("WAITING_DRIVER");
    }

    /** Accept lặp lại (tuần tự) → 202 ACCEPTED cả hai lần, chỉ một lệnh confirm, một TRIP_ACCEPTED. */
    @Test
    void repeatedAcceptIsIdempotent() throws Exception {
        Offered o = offeredTrip();

        assertThat(accept(o, o.driverUserId(), o.attempt()).body()).containsEntry("state", "ACCEPTED");
        assertThat(accept(o, o.driverUserId(), o.attempt()).body()).containsEntry("state", "ACCEPTED");

        assertThat(DRIVER.countCalls("POST:" + o.reservationId() + "/confirm")).isEqualTo(1);
        assertThat(tripEventTypes(o.tripId()).stream().filter("TRIP_ACCEPTED"::equals)).hasSize(1);
    }

    /** 5 accept đồng thời của cùng driver → tất cả 202 ACCEPTED, một assignment, một TRIP_ACCEPTED. */
    @Test
    void concurrentAcceptsProduceOneAssignment() throws Exception {
        Offered o = offeredTrip();
        int n = 5;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<Resp>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                barrier.await();
                return accept(o, o.driverUserId(), o.attempt());
            }));
        }
        pool.shutdown();
        for (Future<Resp> f : results) {
            Resp r = f.get();
            assertThat(r.status()).isEqualTo(202);
            assertThat(r.body()).containsEntry("state", "ACCEPTED");
        }
        assertThat(tripState(o.tripId())).isEqualTo("ACCEPTED");
        assertThat(tripEventTypes(o.tripId()).stream().filter("TRIP_ACCEPTED"::equals)).hasSize(1);
        assertThat(DRIVER.reservation(o.reservationId().toString()).state).isEqualTo("CONFIRMED");
    }

    /** Driver ĐÃ confirm nhưng trả lời muộn hơn read-timeout → Trip đọc lại bằng GET thấy CONFIRMED → ACCEPTED. */
    @Test
    void confirmTimeoutIsReconciledByGetNotAssumed() throws Exception {
        Offered o = offeredTrip();
        DRIVER.delayAfterApplyMs.put("confirm", 1_500L);

        Resp r = accept(o, o.driverUserId(), o.attempt());

        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body()).containsEntry("state", "ACCEPTED");
        assertThat(DRIVER.calls).contains("GET:" + o.reservationId());
        assertThat(tripState(o.tripId())).isEqualTo("ACCEPTED");
    }

    /** Confirm không tới được Driver và GET cũng timeout → KHÔNG xác nhận giả: 202 DRIVER_OFFERED, saga giữ
     *  intent (CONFIRMING). Gọi accept lại khi Driver ổn → tiếp tục với cùng commandId → ACCEPTED. */
    @Test
    void unknownConfirmOutcomeKeepsIntentAndResumesOnRetry() throws Exception {
        Offered o = offeredTrip();
        DRIVER.delayBeforeApplyCount.put("confirm", 1);
        DRIVER.failGet = 1;

        Resp first = accept(o, o.driverUserId(), o.attempt());
        assertThat(first.status()).isEqualTo(202);
        assertThat(first.body()).containsEntry("state", "DRIVER_OFFERED");
        assertThat(tripState(o.tripId())).isEqualTo("DRIVER_OFFERED");
        assertThat(sagaPhase(o.tripId())).isEqualTo("CONFIRMING");
        assertThat(DRIVER.reservation(o.reservationId().toString()).state).isEqualTo("RESERVED");

        Resp retry = accept(o, o.driverUserId(), o.attempt());
        assertThat(retry.body()).containsEntry("state", "ACCEPTED");
        assertThat(tripState(o.tripId())).isEqualTo("ACCEPTED");
        long distinctCommandIds = DRIVER.commandBodies.stream().map(b -> b.get("commandId")).distinct().count();
        assertThat(distinctCommandIds).isEqualTo(1); // retry dùng lại đúng commandId
    }

    /** Offer đã hết hạn phía Driver → confirm bị từ chối → 409 STATE_CONFLICT, saga về WAITING_DRIVER,
     *  trip vẫn DRIVER_OFFERED (xử lý offer hết hạn tự động thuộc T21). */
    @Test
    void expiredOfferIsRejectedWithStateConflict() throws Exception {
        Offered o = offeredTrip();
        DRIVER.expire(o.reservationId().toString());

        Resp r = accept(o, o.driverUserId(), o.attempt());

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).containsEntry("code", "STATE_CONFLICT");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) r.body().get("details");
        assertThat(details).containsEntry("state", "DRIVER_OFFERED");
        assertThat(tripState(o.tripId())).isEqualTo("DRIVER_OFFERED");
        assertThat(sagaPhase(o.tripId())).isEqualTo("WAITING_DRIVER");
        assertThat(sagaContext(o.tripId())).doesNotContainKey("intent");
    }
}
