package com.ridehailing.trip;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
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
 * T19 — reject và tìm lại (spec §20.5, contract §3.3 POST /api/trips/{id}/reject).
 * Acceptance: từ chối giải phóng driver TRƯỚC offer mới; không chọn lại người vừa từ chối;
 * hết ứng viên/lượt → NO_DRIVER.
 */
@SpringBootTest(classes = TripServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TripRejectIntegrationTest extends TripCommandTestSupport {

    Resp reject(Offered o, UUID callerUserId, int attempt) throws Exception {
        return post("/api/trips/" + o.tripId() + "/reject", token(callerUserId, "DRIVER"), "{\"attempt\":" + attempt + "}");
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

    /** Reject → release REJECTED (version 3) → SEARCHING attempt 2, driver bị loại, TripRequested attempt 2. */
    @Test
    void rejectReleasesReservationThenRequestsNewMatchExcludingDriver() throws Exception {
        Offered o = offeredTrip();

        Resp r = reject(o, o.driverUserId(), o.attempt());

        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body()).containsEntry("state", "SEARCHING").containsEntry("matchingAttempt", 2);
        StatefulDriverStub.Reservation res = DRIVER.reservation(o.reservationId().toString());
        assertThat(res.state).isEqualTo("RELEASED");
        assertThat(res.terminalReason).isEqualTo("REJECTED");
        assertThat(((Number) DRIVER.commandBodies.get(0).get("commandVersion")).intValue()).isEqualTo(3);

        assertThat(tripState(o.tripId())).isEqualTo("SEARCHING");
        assertThat(tripInt(o.tripId(), "matching_attempt")).isEqualTo(2);
        Map<String, Object> drivers = jdbc.queryForMap(
                "SELECT driver_id, driver_user_id, reservation_id FROM trips WHERE id = ?", o.tripId());
        assertThat(drivers.values()).containsOnlyNulls(); // chốt N22: xóa khi reject
        assertThat(sagaPhase(o.tripId())).isEqualTo("WAITING_MATCH");
        assertThat(tripEventTypes(o.tripId())).containsSubsequence("OFFER_REJECTED", "RESERVATION_RELEASED");

        List<Map<String, Object>> requests = tripRequestedPayloads(o.tripId());
        assertThat(requests).hasSize(2); // attempt 1 lúc tạo + attempt 2 sau reject
        assertThat(((Number) requests.get(1).get("attempt")).intValue()).isEqualTo(2);
        assertThat(requests.get(1).get("excludedDriverIds")).isEqualTo(List.of(o.driverId().toString()));
    }

    /** Danh sách loại trừ được giữ qua các lượt offer (sửa lỗi T16 làm mất context) và tích lũy. */
    @Test
    void excludedDriversAccumulateAcrossAttempts() throws Exception {
        Offered first = offeredTrip();
        reject(first, first.driverUserId(), 1);

        Offered second = offer(first.tripId(), first.passengerUserId()); // attempt 2, driver khác
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(sagaContext(first.tripId()).get("excludedDriverIds")).isEqualTo(List.of(first.driverId().toString()));

        Resp r = reject(second, second.driverUserId(), 2);
        assertThat(r.body()).containsEntry("state", "SEARCHING").containsEntry("matchingAttempt", 3);
        List<Map<String, Object>> requests = tripRequestedPayloads(first.tripId());
        assertThat(requests.get(requests.size() - 1).get("excludedDriverIds"))
                .isEqualTo(List.of(first.driverId().toString(), second.driverId().toString()));
    }

    /** Reject ở lượt cuối (MAX_MATCHING_ATTEMPTS = 3) → NO_DRIVER, saga ABORTED, không phát TripRequested mới. */
    @Test
    void rejectOnLastAttemptEndsWithNoDriver() throws Exception {
        Offered o = offeredTrip();
        reject(o, o.driverUserId(), 1);
        Offered o2 = offer(o.tripId(), o.passengerUserId());
        reject(o2, o2.driverUserId(), 2);
        Offered o3 = offer(o.tripId(), o.passengerUserId());

        Resp r = reject(o3, o3.driverUserId(), 3);

        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body()).containsEntry("state", "NO_DRIVER").containsEntry("matchingAttempt", 3);
        assertThat(tripState(o.tripId())).isEqualTo("NO_DRIVER");
        assertThat(sagaPhase(o.tripId())).isEqualTo("ABORTED");
        assertThat(tripEventTypes(o.tripId())).endsWith("NO_DRIVER");
        assertThat(DRIVER.reservation(o3.reservationId().toString()).state).isEqualTo("RELEASED");
        assertThat(tripRequestedPayloads(o.tripId())).hasSize(3); // attempt 1, 2, 3 — không có attempt 4
    }

    /** Sai driver/attempt → NOT_OFFERED_DRIVER; trip không có offer → STATE_CONFLICT; passenger → 403. */
    @Test
    void wrongCallerOrStateIsRejected() throws Exception {
        Offered o = offeredTrip();
        assertThat(reject(o, UUID.randomUUID(), 1).body()).containsEntry("code", "NOT_OFFERED_DRIVER");
        assertThat(reject(o, o.driverUserId(), 2).body()).containsEntry("code", "NOT_OFFERED_DRIVER");
        assertThat(post("/api/trips/" + o.tripId() + "/reject", token(o.passengerUserId(), "PASSENGER"),
                "{\"attempt\":1}").status()).isEqualTo(403);

        UUID passenger = UUID.randomUUID();
        UUID searching = createTrip(passenger);
        Resp r = post("/api/trips/" + searching + "/reject", token(UUID.randomUUID(), "DRIVER"), "{\"attempt\":1}");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).containsEntry("code", "STATE_CONFLICT");
        assertThat(DRIVER.countCalls("POST:")).isZero();
    }

    /** Reject lặp lại → cùng kết quả, chỉ một lệnh release, chỉ một TripRequested attempt 2. */
    @Test
    void repeatedRejectIsIdempotent() throws Exception {
        Offered o = offeredTrip();
        Resp first = reject(o, o.driverUserId(), 1);
        Resp again = reject(o, o.driverUserId(), 1);

        assertThat(again.status()).isEqualTo(202);
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(DRIVER.countCalls("POST:" + o.reservationId() + "/release")).isEqualTo(1);
        assertThat(tripRequestedPayloads(o.tripId())).hasSize(2);
    }

    /** Release không rõ kết quả → KHÔNG tìm lại (driver chưa chắc đã được giải phóng): 202 DRIVER_OFFERED,
     *  chưa có TripRequested mới. Gọi lại khi Driver ổn → hoàn tất, chỉ một attempt 2. */
    @Test
    void unknownReleaseOutcomeDoesNotRematchUntilConfirmed() throws Exception {
        Offered o = offeredTrip();
        DRIVER.delayBeforeApplyCount.put("release", 1);
        DRIVER.failGet = 1;

        Resp pending = reject(o, o.driverUserId(), 1);
        assertThat(pending.status()).isEqualTo(202);
        assertThat(pending.body()).containsEntry("state", "DRIVER_OFFERED");
        assertThat(sagaPhase(o.tripId())).isEqualTo("COMPENSATING");
        assertThat(tripRequestedPayloads(o.tripId())).hasSize(1); // chưa phát attempt 2

        Resp done = reject(o, o.driverUserId(), 1);
        assertThat(done.body()).containsEntry("state", "SEARCHING").containsEntry("matchingAttempt", 2);
        assertThat(tripRequestedPayloads(o.tripId())).hasSize(2);
    }

    /** Driver đã release nhưng trả lời muộn → GET thấy RELEASED → tìm lại bình thường. */
    @Test
    void releaseTimeoutIsReconciledByGet() throws Exception {
        Offered o = offeredTrip();
        DRIVER.delayAfterApplyMs.put("release", 1_500L);

        Resp r = reject(o, o.driverUserId(), 1);

        assertThat(r.body()).containsEntry("state", "SEARCHING");
        assertThat(DRIVER.calls).contains("GET:" + o.reservationId());
    }

    /** Offer đã hết hạn phía Driver: release trả EXPIRED (terminal) → vẫn tìm lại được. */
    @Test
    void rejectOfExpiredOfferStillRematches() throws Exception {
        Offered o = offeredTrip();
        DRIVER.expire(o.reservationId().toString());

        Resp r = reject(o, o.driverUserId(), 1);

        assertThat(r.body()).containsEntry("state", "SEARCHING").containsEntry("matchingAttempt", 2);
        assertThat(DRIVER.reservation(o.reservationId().toString()).state).isEqualTo("EXPIRED");
    }

    /** Accept và reject đồng thời của cùng driver → đúng một intent thắng; kết quả nhất quán với Driver. */
    @Test
    void concurrentAcceptAndRejectHaveSingleWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            Offered o = offeredTrip();
            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Resp> acc = pool.submit(() -> {
                barrier.await();
                return accept(o, o.driverUserId(), 1);
            });
            Future<Resp> rej = pool.submit(() -> {
                barrier.await();
                return reject(o, o.driverUserId(), 1);
            });
            Resp a = acc.get();
            Resp r = rej.get();
            pool.shutdown();

            String state = tripState(o.tripId());
            String reservation = DRIVER.reservation(o.reservationId().toString()).state;
            if ("ACCEPTED".equals(state)) {
                assertThat(reservation).isEqualTo("CONFIRMED");
                assertThat(r.status()).isEqualTo(409);
            } else {
                assertThat(state).isEqualTo("SEARCHING");
                assertThat(reservation).isEqualTo("RELEASED");
                assertThat(a.status()).isEqualTo(409);
            }
            cleanTables();
            DRIVER.reset();
        }
    }
}
