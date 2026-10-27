package com.ridehailing.trip.service;

import com.ridehailing.trip.client.DriverReservationClient;
import com.ridehailing.trip.event.EventEnvelope;
import com.ridehailing.trip.event.InboxMessageValidator.Candidate;
import com.ridehailing.trip.event.InboxMessageValidator.DriverProposedPayload;
import com.ridehailing.trip.event.InboxMessageValidator.NoDriverFoundPayload;
import com.ridehailing.trip.event.InboxMessageValidator.PaymentFailedPayload;
import com.ridehailing.trip.event.InboxMessageValidator.PaymentSucceededPayload;
import com.ridehailing.trip.inbox.InboxEventHandler;
import com.ridehailing.trip.repo.OutboxRepository;
import com.ridehailing.trip.repo.SagaRepository;
import com.ridehailing.trip.repo.TripEventRepository;
import com.ridehailing.trip.repo.TripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ridehailing.trip.logging.AuditLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * T16/T24 — trip nhận proposal và reserve (spec §20.4) + payment result (spec §20.5):
 * consume {@code DriverProposed}/{@code NoDriverFound} từ queue {@code trip.inbox}, thử reserve
 * candidate theo thứ tự qua internal API driver-service, lưu reservationId rồi cập nhật state trip;
 * hết candidate/NoDriverFound → retry attempt+1 hoặc NO_DRIVER. T24: consume
 * {@code PaymentSucceeded}/{@code PaymentFailed} (events.md §3.5/§3.6) → guard attempt/state,
 * CAS payment_status PENDING → SUCCEEDED/FAILED + trip_event PAYMENT_*; state COMPLETED giữ nguyên.
 *
 * <p>Chốt thiết kế (events.md §3.2/§3.3/§4, state-machines.md §1/§2, api-contracts.md §4.1/§4.2):
 * <ul>
 *   <li><b>Stale-event guard (§4.4):</b> trip phải ở {@code SEARCHING} với {@code matching_attempt == attempt},
 *       ngược lại → log + bỏ qua, ACK (event hợp lệ nhưng cũ — không vào DLQ).</li>
 *   <li><b>Không giữ DB lock khi gọi HTTP:</b> mọi đọc trước, mọi HTTP giữa, mọi ghi sau — trong transaction
 *       duy nhất của listener cùng dedup insert (§4.2). CAS {@code version+phase} trên saga/trips serialize
 *       intent; 0 rows → RuntimeException → rollback + retry bounded.</li>
 *   <li><b>Idempotent theo eventId:</b> reservationId/commandId deterministic (UUID v3 từ tripId|attempt|index)
 *       nên redelivery sau crash replay idempotent phía driver (§4.1: cùng commandId+hash → replay 201).
 *       Replay với expiresAt mới → hash khác → 409 COMMAND_CONFLICT → reconcile bằng GET trên CÙNG
 *       reservationId; state RESERVED khớp trip/driver/attempt → coi thành công.</li>
 *   <li><b>Timeout HTTP không suy ra thất bại:</b> đọc lại cùng reservationId (GET); 200 RESERVED khớp →
 *       success; 404 → retry PUT MỘT lần với commandId cũ + expiresAt mới; vẫn mù → RuntimeException
 *       (listener retry bounded chạy lại toàn flow — deterministic id giữ an toàn).</li>
 *   <li><b>Hết candidate hoặc NoDriverFound đúng attempt:</b> attempt &lt; MAX_MATCHING_ATTEMPTS và chưa quá
 *       deadline → SEARCHING→SEARCHING attempt+1, saga next_retry_at = now + MATCHING_RETRY_DELAY_MS,
 *       outbox PENDING TripRequested attempt mới (causationId = eventId event này — §5), excludedDriverIds
 *       giữ nguyên; ngược lại → NO_DRIVER terminal + trip_event + saga ABORTED.</li>
 * </ul>
 */
@Component
public class TripInboxHandler implements InboxEventHandler {

    public static final String ROUTING_KEY_TRIP_REQUESTED = "trip.requested"; // events.md §3.1

    private static final Logger log = LoggerFactory.getLogger(TripInboxHandler.class);

    private final TripRepository tripRepo;
    private final SagaRepository sagaRepo;
    private final OutboxRepository outboxRepo;
    private final TripEventRepository eventRepo;
    private final NotificationService notifications;
    private final DriverReservationClient reservationClient;
    private final ObjectMapper json;
    private final long offerTtlSeconds;
    private final int maxMatchingAttempts;
    private final long matchingRetryDelayMs;
    private final int maxCandidates;

    public TripInboxHandler(TripRepository tripRepo, SagaRepository sagaRepo, OutboxRepository outboxRepo,
                           TripEventRepository eventRepo, NotificationService notifications,
                           DriverReservationClient reservationClient,
                           ObjectMapper json,
                           @Value("${app.trip.offer-ttl-seconds:30}") long offerTtlSeconds,
                           @Value("${app.trip.max-matching-attempts:3}") int maxMatchingAttempts,
                           @Value("${app.trip.matching-retry-delay-ms:5000}") long matchingRetryDelayMs,
                           @Value("${app.trip.max-candidates:5}") int maxCandidates) {
        this.tripRepo = tripRepo;
        this.sagaRepo = sagaRepo;
        this.outboxRepo = outboxRepo;
        this.eventRepo = eventRepo;
        this.notifications = notifications;
        this.reservationClient = reservationClient;
        this.json = json;
        this.offerTtlSeconds = Math.max(1, offerTtlSeconds);
        this.maxMatchingAttempts = Math.max(1, maxMatchingAttempts);
        this.matchingRetryDelayMs = Math.max(0, matchingRetryDelayMs);
        this.maxCandidates = Math.max(1, maxCandidates);
    }

    /** Chạy trong transaction của listener (cùng inbox dedup insert). Lỗi transient ném RuntimeException
     *  → nhánh retry bounded của listener (events.md §4.3). */
    @Override
    public void handle(EventEnvelope envelope) {
        // Payload typed parse lại từ envelope — an toàn vì listener chỉ gọi handler sau khi
        // validator đã kiểm tra schema (events.md §2).
        DriverProposedPayload dp = "DriverProposed".equals(envelope.eventType()) ? parseDriverProposed(envelope) : null;
        NoDriverFoundPayload ndf = "NoDriverFound".equals(envelope.eventType()) ? parseNoDriverFound(envelope) : null;
        PaymentSucceededPayload ps = "PaymentSucceeded".equals(envelope.eventType()) ? parsePaymentSucceeded(envelope) : null;
        PaymentFailedPayload pf = "PaymentFailed".equals(envelope.eventType()) ? parsePaymentFailed(envelope) : null;

        // T24 — payment result: guard attempt/state rồi CAS payment_status + trip_event.
        if (ps != null || pf != null) {
            handlePaymentResult(envelope, ps, pf);
            return;
        }

        UUID tripId = envelope.aggregateId();
        int attempt = dp != null ? dp.attempt() : (ndf != null ? ndf.attempt() : -1);
        if (dp == null && ndf == null) {
            throw new IllegalStateException("Unhandled event type: " + envelope.eventType());
        }

        // 1) Đọc trip row (SELECT thuần — không giữ lock). Thiếu row = inconsistency → retry→DLQ visible.
        TripRepository.TripRow trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new IllegalStateException("trip row missing for event " + envelope.eventId()));

        // 2) Stale-event guard (events.md §4.4): state phải SEARCHING đúng attempt → log + bỏ qua, ACK.
        if (!"SEARCHING".equals(trip.state()) || trip.matchingAttempt() != attempt) {
            audit("stale_event_skipped", tripId, attempt,
                    "stale event skipped eventType={} tripId={} attempt={} currentState={} currentAttempt={}",
                    envelope.eventType(), tripId, attempt, trip.state(), trip.matchingAttempt());
            return;
        }

        // 3) Saga row phải tồn tại và ở WAITING_MATCH (state-machines.md §2). Inconsistency → visible fail.
        SagaRepository.SagaRow saga = sagaRepo.findByTripId(tripId)
                .orElseThrow(() -> new IllegalStateException("saga row missing for trip " + tripId));
        if (!"WAITING_MATCH".equals(saga.phase())) {
            if ("COMPENSATING".equals(saga.phase())
                    && "MATCH_DEADLINE".equals(readContext(saga.contextJson()).get("intent"))) {
                audit("stale_event_skipped", tripId, attempt,
                        "matching event skipped during durable deadline cleanup tripId={}", tripId);
                return;
            }
            throw new IllegalStateException("saga not in WAITING_MATCH: phase=" + saga.phase() + " trip=" + tripId);
        }

        // After the matching deadline, stop reserving and leave cleanup to the durable worker.
        // Ending the saga here would abandon reservations whose release is still pending.
        boolean pastDeadline = sagaRepo.isPastDeadline(tripId);
        if (pastDeadline) {
            // Keep WAITING_MATCH actionable. Only the recovery worker may end the saga after
            // tombstoning every possible reservation, including a reserve still in flight.
            audit("matching_deadline_pending", tripId, attempt,
                    "matching deadline cleanup delegated to saga worker tripId={}", tripId);
            return;
        }

        // T21: MỘT expiresAt cho toàn lượt handle — mọi PUT reserve của lượt này dùng cùng giá trị
        // nên replay sau timeout giữ nguyên request hash (§4.1) và saga context lưu đúng hạn offer
        // để worker giám sát TTL (state-machines.md §2 WAITING_DRIVER).
        Instant offerExpiresAt = freshExpiresAt();

        // 4) Thử reserve candidate theo thứ tự (DriverProposed); NoDriverFound không có candidate.
        List<Candidate> candidates = dp != null && !pastDeadline ? dp.candidates() : List.of();
        // T16 fix (T4): phòng thủ — bỏ driver nằm trong excludedDriverIds kể cả khi matching không lọc.
        java.util.Set<UUID> excluded = java.util.Set.copyOf(excludedFromContext(saga.contextJson()));
        if (!excluded.isEmpty()) {
            candidates = candidates.stream().filter(c -> !excluded.contains(c.driverId())).toList();
        }
        // T20: chỉ thử tối đa maxCandidates (= MATCHING_MAX_CANDIDATES) — cancel khi SEARCHING tombstone đúng
        // tập reservationId tất định (trip, attempt, 0..maxCandidates-1), nên không được vượt quá tập đó.
        if (candidates.size() > maxCandidates) {
            candidates = candidates.subList(0, maxCandidates);
        }
        Candidate winner = null;
        UUID winningReservationId = null;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            Optional<UUID> reserved = tryReserveCandidate(trip, attempt, c, i, offerExpiresAt);
            if (reserved.isPresent()) {
                winner = c;
                winningReservationId = reserved.get();
                break; // giữ được chỗ — dừng ngay (§3.2)
            }
        }

        // 5) Ghi kết quả trong transaction này (§4.2). Mọi CAS fail → RuntimeException → rollback + retry.
        if (winner != null) {
            applyDriverOffered(trip, saga, attempt, winner, winningReservationId, offerExpiresAt);
        } else {
            applyRetryOrNoDriver(envelope, trip, saga, attempt, pastDeadline);
        }
    }

    /** Thử reserve MỘT candidate; trả reservationId khi thành công (kể cả reconcile sau timeout),
     *  empty khi conflict → thử candidate kế tiếp. Timeout mù sau reconcile → RuntimeException. */
    private Optional<UUID> tryReserveCandidate(TripRepository.TripRow trip, int attempt, Candidate c, int index,
                                              Instant offerExpiresAt) {
        UUID reservationId = deterministicReservationId(trip.id(), attempt, index);
        UUID commandId = deterministicCommandId(trip.id(), attempt, index);

        Map<String, Object> body = reserveBody(trip, attempt, c, commandId, offerExpiresAt);
        try {
            DriverReservationClient.ReserveResult r = reservationClient.reserve(reservationId, body);
            if (r.status() == 201) {
                return Optional.of(reservationId);
            }
            if ("COMMAND_CONFLICT".equals(r.errorCode())) {
                // Redelivery sau rollback: cùng commandId nhưng expiresAt mới → hash khác (§4.1).
                // Reconcile bằng GET trên CÙNG reservationId — RESERVED khớp trip/driver/attempt = thành công.
                return reconcileAfterConflict(reservationId, trip.id(), c.driverId(), attempt);
            }
            // DRIVER_NOT_AVAILABLE / TOMBSTONED_RESERVATION / STALE_VERSION → candidate kế tiếp (§3.2).
            log.warn("reserve conflict code={} reservationId={} driverId={}; trying next candidate",
                    r.errorCode(), reservationId, c.driverId());
            return Optional.empty();
        } catch (RestClientException transportFailure) {
            // Acceptance: "timeout đọc lại cùng reservation" — KHÔNG suy ra thất bại từ lỗi transport.
            // Spring 7 RestClient ném RestClientException khi read-timeout (extract response fail),
            // không chỉ ResourceAccessException → catch rộng cho mọi lỗi mạng/timeout.
            log.warn("reserve timeout; reconciling via GET reservationId={}", reservationId);
            if (confirmReserved(reservationClient.get(reservationId), trip.id(), c.driverId(), attempt)) {
                return Optional.of(reservationId);
            }
            // 404/mismatch: retry PUT MỘT lần với CÙNG commandId + CÙNG expiresAt (§4.1: commandId và
            // payload ổn định khi retry → replay idempotent phía driver nếu PUT đầu đã tới).
            Map<String, Object> body2 = reserveBody(trip, attempt, c, commandId, offerExpiresAt);
            try {
                DriverReservationClient.ReserveResult r2 = reservationClient.reserve(reservationId, body2);
                if (r2.status() == 201) {
                    return Optional.of(reservationId);
                }
                if ("COMMAND_CONFLICT".equals(r2.errorCode())) {
                    return reconcileAfterConflict(reservationId, trip.id(), c.driverId(), attempt);
                }
                log.warn("reserve conflict after timeout-retry code={} reservationId={}; trying next candidate",
                        r2.errorCode(), reservationId);
                return Optional.empty();
            } catch (RestClientException transportFailure2) {
                // Vẫn mù sau 2 PUT + GET: đọc lại lần cuối; còn không rõ → RuntimeException
                // (listener retry bounded chạy lại toàn flow — deterministic id giữ an toàn).
                log.warn("reserve still ambiguous after second timeout; final GET reservationId={}", reservationId);
                if (confirmReserved(reservationClient.get(reservationId), trip.id(), c.driverId(), attempt)) {
                    return Optional.of(reservationId);
                }
                throw new IllegalStateException(
                        "reserve outcome unknown for reservationId=" + reservationId + " driverId=" + c.driverId());
            }
        }
    }

    /** COMMAND_CONFLICT → GET reconcile: RESERVED khớp trip/driver/attempt = thành công (hash lệch do expiresAt mới). */
    private Optional<UUID> reconcileAfterConflict(UUID reservationId, UUID tripId, UUID driverId, int attempt) {
        log.info("COMMAND_CONFLICT; reconciling via GET reservationId={}", reservationId);
        if (confirmReserved(reservationClient.get(reservationId), tripId, driverId, attempt)) {
            return Optional.of(reservationId);
        }
        throw new IllegalStateException(
                "COMMAND_CONFLICT and GET does not confirm matching RESERVED state for reservationId=" + reservationId);
    }

    /** GET reconcile: 200 RESERVED khớp identity → true; 404/mismatch/terminal → false. */
    private boolean confirmReserved(Optional<DriverReservationClient.ReservationStatus> st,
                                    UUID tripId, UUID driverId, int attempt) {
        return st.isPresent() && "RESERVED".equals(st.get().state())
                && st.get().tripId().equals(tripId)
                && st.get().driverId().equals(driverId)
                && st.get().attempt() == attempt;
    }

    /** Body PUT RESERVE theo contract §4.2: commandId/commandVersion + field nghiệp vụ từ trips row. */
    private Map<String, Object> reserveBody(TripRepository.TripRow trip, int attempt, Candidate c,
                                            UUID commandId, Instant expiresAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandId", commandId.toString());
        body.put("commandVersion", 1);
        body.put("tripId", trip.id().toString());
        body.put("driverId", c.driverId().toString());
        body.put("attempt", attempt);
        body.put("expiresAt", expiresAt.toString()); // ISO-8601 UTC; phải nằm trong (now, now+OFFER_TTL_MAX] (§4.1)
        Map<String, Object> pickup = new LinkedHashMap<>();
        pickup.put("latitude", trip.pickupLat());
        pickup.put("longitude", trip.pickupLng());
        body.put("pickup", pickup);
        Map<String, Object> destination = new LinkedHashMap<>();
        destination.put("latitude", trip.destinationLat());
        destination.put("longitude", trip.destinationLng());
        body.put("destination", destination);
        body.put("distanceM", trip.distanceM());
        body.put("fareVnd", trip.fareVnd());
        return body;
    }

    /** expiresAt = now (truncate giây) + OFFER_TTL_SECONDS — luôn trong (now, now+120s] với default 30 (§4.1). */
    private Instant freshExpiresAt() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(offerTtlSeconds);
    }

    /** Deterministic UUID v3 — ổn định qua mọi redelivery/replay của cùng (trip, attempt, index) (§4.1).
     *  Public cho test/worker: T20 cancel và deadline handler (T21) tính lại đúng tập
     *  reservationId có thể có của attempt hiện tại — một nguồn duy nhất. */
    public static UUID deterministicReservationId(UUID tripId, int attempt, int index) {
        return UUID.nameUUIDFromBytes(("RESERVE|" + tripId + "|" + attempt + "|" + index)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static UUID deterministicCommandId(UUID tripId, int attempt, int index) {
        return UUID.nameUUIDFromBytes(("CMD-RESERVE|" + tripId + "|" + attempt + "|" + index)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** SEARCHING → DRIVER_OFFERED (state-machines.md §1): saga WAITING_DRIVER + trips driver fields + trip_event.
     *  T21: lưu {@code offerExpiresAt} vào context — worker Saga dùng nó để giám sát offer TTL
     *  (đến hạn mà chưa accept/reject → release OFFER_EXPIRED rồi tìm lại hoặc NO_DRIVER). */
    private void applyDriverOffered(TripRepository.TripRow trip, SagaRepository.SagaRow saga, int attempt,
                                    Candidate winner, UUID reservationId, Instant offerExpiresAt) {
        int newVersion = trip.version() + 1;
        // T19 fix: giữ context cũ (excludedDriverIds, lastRejection...) — trước đây map mới làm mất
        // danh sách driver đã từ chối, nên lượt matching sau có thể đề nghị lại chính họ.
        Map<String, Object> context = readContext(saga.contextJson());
        context.put("reservationId", reservationId.toString());
        context.put("driverId", winner.driverId().toString());
        context.put("driverUserId", winner.driverUserId().toString());
        context.put("attempt", attempt);
        context.put("offerExpiresAt", offerExpiresAt.toString());

        int sagaRows = sagaRepo.casFromWaitingMatch(trip.id(), saga.version(), "WAITING_DRIVER", null, null, serialize(context));
        if (sagaRows != 1) {
            throw new IllegalStateException("saga CAS failed for trip " + trip.id());
        }
        int tripRows = tripRepo.markDriverOffered(trip.id(), "SEARCHING", attempt,
                winner.driverId(), winner.driverUserId(), reservationId);
        if (tripRows != 1) {
            throw new IllegalStateException("trips CAS failed for trip " + trip.id());
        }
        eventRepo.insert(trip.id(), "DRIVER_OFFERED", newVersion, null,
                "Driver offered: driverId=" + winner.driverId() + " attempt=" + attempt);

        // T25 — notification cùng transaction với trip_event (chốt T25): passenger TRIP_OFFERED,
        // driver OFFER_RECEIVED (spec flow bước 4: reserve thành công → notify cả hai).
        notifications.notify(trip.passengerId(), trip.id(), "TRIP_OFFERED", newVersion);
        notifications.notify(winner.driverUserId(), trip.id(), "OFFER_RECEIVED", newVersion);

        audit("trip_driver_offered", trip.id(), attempt, "driver offered tripId={} attempt={} driverId={} reservationId={}",
                trip.id(), attempt, winner.driverId(), reservationId);
    }

    /** Hết candidate/NoDriverFound đúng attempt (events.md §3.2/§3.3): retry attempt+1 hoặc NO_DRIVER terminal. */
    private void applyRetryOrNoDriver(EventEnvelope envelope, TripRepository.TripRow trip,
                                      SagaRepository.SagaRow saga, int attempt, boolean pastDeadline) {
        Instant now = Instant.now();
        // T16 fix (T2): deadline theo đồng hồ DB (cùng nguồn với mọi CAS khác — R04), không so Instant.now() JVM.
        boolean exhausted = attempt >= maxMatchingAttempts || pastDeadline;

        if (exhausted) {
            // NO_DRIVER do Trip quyết định (spec §10): terminal, không phát event mới.
            int newVersion = trip.version() + 1;
            int tripRows = tripRepo.markNoDriver(trip.id(), "SEARCHING", attempt);
            if (tripRows != 1) {
                throw new IllegalStateException("trips CAS failed for trip " + trip.id());
            }
            eventRepo.insert(trip.id(), "NO_DRIVER", newVersion, null,
                    "No driver found after " + attempt + " attempts");
            notifications.notify(trip.passengerId(), trip.id(), "NO_DRIVER", newVersion);
            int sagaRows = sagaRepo.casFromWaitingMatch(trip.id(), saga.version(), "ABORTED", null, null, saga.contextJson());
            if (sagaRows != 1) {
                throw new IllegalStateException("saga CAS failed for trip " + trip.id());
            }
            audit("no_driver_terminal", trip.id(), attempt, "trip NO_DRIVER tripId={} attempts={} pastDeadline={}",
                    trip.id(), attempt, pastDeadline);
        } else {
            // SEARCHING → SEARCHING: attempt+1, next_retry_at = now + MATCHING_RETRY_DELAY_MS, outbox TripRequested mới.
            int nextAttempt = attempt + 1;
            int newVersion = trip.version() + 1;
            Instant nextRetryAt = now.plusMillis(matchingRetryDelayMs);

            int tripRows = tripRepo.bumpMatchingAttempt(trip.id(), "SEARCHING", attempt);
            if (tripRows != 1) {
                throw new IllegalStateException("trips CAS failed for trip " + trip.id());
            }
            // excludedDriverIds giữ nguyên (§3.2: không có candidate bị loại ở nhánh này).
            int sagaRows = sagaRepo.casFromWaitingMatch(trip.id(), saga.version(), "WAITING_MATCH", nextRetryAt, null, saga.contextJson());
            if (sagaRows != 1) {
                throw new IllegalStateException("saga CAS failed for trip " + trip.id());
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tripId", trip.id().toString());
            payload.put("attempt", nextAttempt);
            Map<String, Object> pickup = new LinkedHashMap<>();
            pickup.put("latitude", trip.pickupLat());
            pickup.put("longitude", trip.pickupLng());
            payload.put("pickup", pickup);
            payload.put("excludedDriverIds", excludedFromContext(saga.contextJson()));

            // causationId = eventId của event này (events.md §5); aggregateVersion = trips.version mới.
            // T16 fix (T1): row outbox chỉ được publish sau MATCHING_RETRY_DELAY_MS (next_attempt_at) — trước đây
            // ghi ngay nên độ trễ không có tác dụng và matching bị gọi lại tức thì khi chưa có ai rảnh.
            outboxRepo.insertPendingDelayed(UUID.randomUUID(), "TripRequested", trip.id(), serialize(payload),
                    envelope.correlationId(), envelope.eventId(), now, newVersion, ROUTING_KEY_TRIP_REQUESTED,
                    matchingRetryDelayMs);

            audit("searching_next_attempt", trip.id(), nextAttempt,
                    "matching retry scheduled tripId={} nextAttempt={} nextRetryAt={}", trip.id(), nextAttempt, nextRetryAt);
        }
    }

    /** T24 — consume PaymentSucceeded/PaymentFailed (events.md §3.5/§3.6, state-machines.md §5):
     *  guard attempt/state (§4.4), CAS payment_status PENDING → SUCCEEDED/FAILED + trip_event PAYMENT_*.
     *  State COMPLETED giữ nguyên dù FAILED (invariant 7). */
    private void handlePaymentResult(EventEnvelope envelope, PaymentSucceededPayload ps, PaymentFailedPayload pf) {
        UUID tripId = envelope.aggregateId();
        int attempt = ps != null ? ps.attempt() : pf.attempt();
        String newStatus = ps != null ? "SUCCEEDED" : "FAILED";

        // 1) Đọc trip row (SELECT thuần — không giữ lock). Thiếu row = inconsistency → retry→DLQ visible.
        TripRepository.TripRow trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new IllegalStateException("trip row missing for event " + envelope.eventId()));

        // 2) Stale-event guard (events.md §4.4): payment result chỉ hợp lệ cho trip COMPLETED đúng attempt
        //    với payment_status PENDING — ngược lại → log + bỏ qua, ACK (event hợp lệ nhưng cũ/trùng,
        //    không vào DLQ). MVP: FAILED là terminal, không có attempt mới nên đây cũng chặn event trùng.
        if (!"COMPLETED".equals(trip.state()) || trip.paymentAttempt() != attempt
                || !"PENDING".equals(trip.paymentStatus())) {
            audit("stale_payment_event_skipped", tripId, attempt,
                    "stale payment event skipped eventType={} tripId={} attempt={} currentState={} paymentStatus={}",
                    envelope.eventType(), tripId, attempt, trip.state(), trip.paymentStatus());
            return;
        }

        // 3) CAS fail (race với event trùng eventId khác) → RuntimeException → rollback + retry bounded
        //    (§4.3); redelivery sẽ bị stale guard bỏ qua vì payment_status đã terminal.
        Optional<Integer> v = tripRepo.markPaymentResult(tripId, newStatus, attempt);
        if (v.isEmpty()) {
            throw new IllegalStateException("payment CAS failed for trip " + tripId);
        }

        eventRepo.insert(tripId, ps != null ? "PAYMENT_SUCCEEDED" : "PAYMENT_FAILED", v.get(), null,
                ps != null ? "Payment succeeded: paymentId=" + ps.paymentId() + " attempt=" + attempt
                          : "Payment failed: paymentId=" + pf.paymentId() + " reason=" + pf.reason());
        // T25 — notification cùng transaction (chốt T25): passenger nhận kết quả thanh toán.
        notifications.notify(trip.passengerId(), tripId, ps != null ? "PAYMENT_SUCCEEDED" : "PAYMENT_FAILED", v.get());

        audit("payment_result_applied", tripId, attempt,
                "payment result applied eventType={} tripId={} attempt={} status={}",
                envelope.eventType(), tripId, attempt, newStatus);
    }

    /** T16 fix (T5, quy ước L05): MDC tạm thời; trong transaction của listener chỉ ghi SAU commit. */
    private void audit(String eventType, UUID tripId, int attempt, String message, Object... args) {
        AuditLog.info(log, AuditLog.mdc("tripId", tripId.toString(), "attempt", String.valueOf(attempt),
                "eventType", eventType, "result", "SUCCESS"), message, args);
    }

    /** Toàn bộ saga context dạng map có thứ tự (giữ mọi key hiện có). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readContext(String contextJson) {
        try {
            Map<String, Object> ctx = json.readValue(
                    (contextJson == null ? "{}" : contextJson).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    LinkedHashMap.class);
            return ctx == null ? new LinkedHashMap<>() : ctx;
        } catch (Exception e) {
            throw new IllegalStateException("saga context unreadable: " + e.getMessage(), e);
        }
    }

    /** Đọc excludedDriverIds từ saga context (khởi tạo [] ở T16; T19 thêm driver đã từ chối). */
    private List<UUID> excludedFromContext(String contextJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> ctx = json.readValue(contextJson.getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.class);
            if (ctx.get("excludedDriverIds") instanceof List<?> list) {
                return list.stream().map(o -> UUID.fromString((String) o)).toList();
            }
        } catch (Exception e) {
            throw new IllegalStateException("saga context unreadable: " + e.getMessage(), e);
        }
        return List.of();
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize saga/outbox payload", e);
        }
    }

    /** Payload typed từ envelope đã validate — parse lại an toàn vì validator đã kiểm tra schema. */
    private DriverProposedPayload parseDriverProposed(EventEnvelope envelope) {
        Map<String, Object> p = envelope.payload();
        UUID tripId = UUID.fromString((String) p.get("tripId"));
        int attempt = ((Number) p.get("attempt")).intValue();
        @SuppressWarnings("unchecked")
        List<Candidate> candidates = ((List<?>) p.get("candidates")).stream().map(o -> {
            Map<?, ?> m = (Map<?, ?>) o;
            return new Candidate(UUID.fromString((String) m.get("driverId")),
                    UUID.fromString((String) m.get("driverUserId")),
                    ((Number) m.get("distanceM")).longValue());
        }).toList();
        if (!tripId.equals(envelope.aggregateId())) {
            throw new IllegalStateException("payload tripId != aggregateId for event " + envelope.eventId());
        }
        return new DriverProposedPayload(tripId, attempt, candidates);
    }

    private NoDriverFoundPayload parseNoDriverFound(EventEnvelope envelope) {
        Map<String, Object> p = envelope.payload();
        UUID tripId = UUID.fromString((String) p.get("tripId"));
        int attempt = ((Number) p.get("attempt")).intValue();
        if (!tripId.equals(envelope.aggregateId())) {
            throw new IllegalStateException("payload tripId != aggregateId for event " + envelope.eventId());
        }
        return new NoDriverFoundPayload(tripId, attempt, (String) p.get("reason"));
    }

    private PaymentSucceededPayload parsePaymentSucceeded(EventEnvelope envelope) {
        Map<String, Object> p = envelope.payload();
        UUID tripId = UUID.fromString((String) p.get("tripId"));
        if (!tripId.equals(envelope.aggregateId())) {
            throw new IllegalStateException("payload tripId != aggregateId for event " + envelope.eventId());
        }
        return new PaymentSucceededPayload(tripId, UUID.fromString((String) p.get("paymentId")),
                ((Number) p.get("attempt")).intValue(), ((Number) p.get("amountVnd")).longValue());
    }

    private PaymentFailedPayload parsePaymentFailed(EventEnvelope envelope) {
        Map<String, Object> p = envelope.payload();
        UUID tripId = UUID.fromString((String) p.get("tripId"));
        if (!tripId.equals(envelope.aggregateId())) {
            throw new IllegalStateException("payload tripId != aggregateId for event " + envelope.eventId());
        }
        return new PaymentFailedPayload(tripId, UUID.fromString((String) p.get("paymentId")),
                ((Number) p.get("attempt")).intValue(), (String) p.get("reason"));
    }
}
