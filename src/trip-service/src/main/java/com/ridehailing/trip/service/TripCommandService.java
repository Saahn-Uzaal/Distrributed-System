package com.ridehailing.trip.service;

import com.ridehailing.trip.client.DriverReservationClient;
import com.ridehailing.trip.exception.InvalidInputException;
import com.ridehailing.trip.exception.NotAssignedDriverException;
import com.ridehailing.trip.exception.NotOfferedDriverException;
import com.ridehailing.trip.exception.StateConflictException;
import com.ridehailing.trip.exception.TripNotFoundException;
import com.ridehailing.trip.logging.AuditLog;
import com.ridehailing.trip.repo.OutboxRepository;
import com.ridehailing.trip.repo.SagaRepository;
import com.ridehailing.trip.repo.TripEventRepository;
import com.ridehailing.trip.repo.TripRepository;
import com.ridehailing.trip.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * T18–T22 — lệnh của người dùng lên chuyến: accept (T18), reject (T19), cancel (T20), start/complete
 * + finish driver (T22).
 *
 * <p>Mẫu chung cho mọi lệnh (spec §10, state-machines.md §2):
 * <ol>
 *   <li><b>Tx1 (ngắn):</b> khóa row saga ({@code FOR UPDATE}), kiểm tra quyền + state, ghi <em>intent</em>
 *       vào saga rồi commit. Khóa này phân xử accept / reject / cancel đồng thời: chỉ MỘT intent thắng.</li>
 *   <li><b>HTTP</b> tới internal API driver-service — KHÔNG giữ khóa DB. commandId tất định theo reservation
 *       nên gửi lại an toàn. Timeout/5xx không bị coi là thất bại: đọc lại bằng GET trên cùng reservationId.</li>
 *   <li><b>Tx2:</b> khóa lại saga, ghi kết quả (trip state, trip_events, saga phase). Kết quả chưa rõ →
 *       intent vẫn nằm trong saga; gọi lại cùng lệnh sẽ tiếp tục từ đó (worker tự động là T21).</li>
 * </ol>
 *
 * <p>commandVersion với Driver (contract §4.1): reserve = 1 (T16), confirm = 2, release = 3. Confirm đến
 * trễ sau khi đã release nhận STALE_VERSION và không bao giờ tạo lại assignment.
 */
@Service
public class TripCommandService {

    static final int CONFIRM_COMMAND_VERSION = 2;
    static final int RELEASE_COMMAND_VERSION = 3;

    /** T22 — commandVersion finish (contract §4.1 chốt: reserve=1, confirm=2, release=3, finish=4). */
    static final int FINISH_COMMAND_VERSION = 4;

    /** Routing key TripCompleted (events.md §1/§3.4): payment.inbox bind {@code trip.completed}. */
    public static final String ROUTING_KEY_TRIP_COMPLETED = "trip.completed";

    private static final Logger log = LoggerFactory.getLogger(TripCommandService.class);

    /** Kết quả trả cho controller: HTTP status + body. */
    public record CommandOutcome(int httpStatus, Map<String, Object> body) {
    }

    private final TripRepository tripRepo;
    private final SagaRepository sagaRepo;
    private final TripEventRepository eventRepo;
    private final OutboxRepository outboxRepo;
    private final NotificationService notifications;
    private final DriverReservationClient driverClient;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final int maxMatchingAttempts;
    private final int maxCandidates;

    public TripCommandService(TripRepository tripRepo, SagaRepository sagaRepo, TripEventRepository eventRepo,
                              OutboxRepository outboxRepo, NotificationService notifications,
                              DriverReservationClient driverClient, ObjectMapper json,
                              PlatformTransactionManager transactionManager,
                              @Value("${app.trip.max-matching-attempts:3}") int maxMatchingAttempts,
                              @Value("${app.trip.max-candidates:5}") int maxCandidates) {
        this.tripRepo = tripRepo;
        this.sagaRepo = sagaRepo;
        this.eventRepo = eventRepo;
        this.outboxRepo = outboxRepo;
        this.notifications = notifications;
        this.driverClient = driverClient;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
        this.maxMatchingAttempts = Math.max(1, maxMatchingAttempts);
        this.maxCandidates = Math.max(1, maxCandidates);
    }

    // =====================================================================================
    // T18 — accept
    // =====================================================================================

    /** Việc cần làm sau Tx1 của accept: đã xong (idempotent) hoặc cần confirm reservation. */
    private record AcceptPlan(String doneState, UUID reservationId, UUID driverId) {
    }

    private enum ConfirmStatus { CONFIRMED, REJECTED, UNKNOWN }

    private record ConfirmResult(ConfirmStatus status, String detail) {
    }

    /**
     * POST /api/trips/{id}/accept (contract §3.3). Luôn 202 với state sau thao tác:
     * ACCEPTED (confirm xong) hoặc DRIVER_OFFERED (kết quả confirm chưa rõ — gọi lại để tiếp tục).
     */
    public CommandOutcome accept(UUID tripId, AuthenticatedUser driver, Integer attempt) {
        requireAttempt(attempt);
        AcceptPlan plan = tx.execute(s -> planAccept(tripId, driver.userId(), attempt));
        if (plan.doneState() != null) {
            return outcome(202, tripId, plan.doneState(), null);
        }

        ConfirmResult result = confirmWithReconcile(plan.reservationId(), tripId, plan.driverId(), attempt);
        switch (result.status()) {
            case CONFIRMED -> {
                String state = tx.execute(s -> completeAccept(tripId, attempt, driver.userId(), plan.reservationId()));
                return outcome(202, tripId, state, null);
            }
            case REJECTED -> {
                tx.executeWithoutResult(s -> abandonAccept(tripId, result.detail()));
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("tripId", tripId.toString());
                details.put("state", "DRIVER_OFFERED");
                details.put("reservationState", result.detail());
                throw new StateConflictException("Offer is no longer valid (expired or released).", details);
            }
            default -> {
                tx.executeWithoutResult(s -> sagaRepo.recordPendingError(tripId, "confirm pending: " + result.detail()));
                audit("trip_accept_pending", tripId, "FAILURE",
                        "accept pending reconcile tripId={} reservationId={} detail={}",
                        tripId, plan.reservationId(), result.detail());
                return outcome(202, tripId, "DRIVER_OFFERED", null);
            }
        }
    }

    /** Tx1 của accept: khóa saga, kiểm tra offered driver + attempt, ghi intent ACCEPT (WAITING_DRIVER → CONFIRMING). */
    private AcceptPlan planAccept(UUID tripId, UUID caller, int attempt) {
        SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                .orElseThrow(() -> new TripNotFoundException("Trip not found."));
        TripRepository.TripRow trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException("Trip not found."));

        // Idempotent: cùng driver/attempt gửi lại sau khi đã ACCEPTED → không tạo assignment mới.
        if ("ACCEPTED".equals(trip.state()) && caller.equals(trip.driverUserId()) && trip.matchingAttempt() == attempt) {
            return new AcceptPlan("ACCEPTED", null, null);
        }
        if (!"DRIVER_OFFERED".equals(trip.state())) {
            throw stateConflict(trip, "Trip is not waiting for a driver to accept.");
        }
        if (!caller.equals(trip.driverUserId()) || trip.matchingAttempt() != attempt) {
            throw notOffered(tripId, attempt);
        }

        Map<String, Object> ctx = readContext(saga.contextJson());
        switch (saga.phase()) {
            case "WAITING_DRIVER" -> {
                ctx.put("intent", "ACCEPT");
                sagaRepo.updateLocked(tripId, "CONFIRMING", "CONFIRM_ASSIGNMENT", null, writeContext(ctx));
                audit("trip_accept_intent", tripId, "SUCCESS", "accept intent recorded tripId={} attempt={}",
                        tripId, attempt);
            }
            case "CONFIRMING" -> {
                if (!"ACCEPT".equals(ctx.get("intent"))) {
                    throw stateConflict(trip, "Another action is in progress for this trip.");
                }
                // Intent ACCEPT đã ghi (lần gọi trước timeout) → tiếp tục confirm với cùng commandId.
            }
            default -> throw stateConflict(trip, "Trip is being " + describePending(ctx) + "; accept is not allowed.");
        }
        return new AcceptPlan(null, uuid(ctx, "reservationId"), uuid(ctx, "driverId"));
    }

    /** Gọi confirm và phân loại kết quả; timeout/5xx → đọc lại bằng GET, không xác nhận giả.
     *  T21: worker saga tái dùng hàm này cho intent ACCEPT treo — commandId tất định theo reservation
     *  nên replay an toàn (§4.1), và saga row lock serialize với request song song. */
    public ConfirmResult confirmWithReconcile(UUID reservationId, UUID tripId, UUID driverId, int attempt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandId", deterministicId("CMD-CONFIRM|" + reservationId).toString());
        body.put("commandVersion", CONFIRM_COMMAND_VERSION);
        body.put("tripId", tripId.toString());
        body.put("driverId", driverId.toString());
        body.put("attempt", attempt);
        try {
            DriverReservationClient.CommandResult r = driverClient.confirm(reservationId, body);
            if (r.ok()) {
                return new ConfirmResult(ConfirmStatus.CONFIRMED, r.state());
            }
            // 409 STATE_CONFLICT / STALE_VERSION / COMMAND_CONFLICT, 404: đọc state thật để quyết định.
            return classifyConfirm(reservationId, tripId, driverId, attempt, r.errorCode());
        } catch (RestClientException transport) {
            log.warn("confirm outcome unknown ({}); reconciling via GET reservationId={}", transport.toString(), reservationId);
            try {
                Optional<DriverReservationClient.ReservationStatus> st = driverClient.get(reservationId);
                if (st.isPresent() && matches(st.get(), tripId, driverId, attempt)
                        && "RESERVED".equals(st.get().state())) {
                    // Confirm chưa tới Driver: gửi lại MỘT lần với cùng commandId (an toàn nhờ dedup §4.1).
                    DriverReservationClient.CommandResult r2 = driverClient.confirm(reservationId, body);
                    return r2.ok() ? new ConfirmResult(ConfirmStatus.CONFIRMED, r2.state())
                            : classifyConfirm(reservationId, tripId, driverId, attempt, r2.errorCode());
                }
                return classify(st, tripId, driverId, attempt);
            } catch (RestClientException stillUnknown) {
                return new ConfirmResult(ConfirmStatus.UNKNOWN, stillUnknown.toString());
            }
        }
    }

    /**
     * Driver trả 4xx cho confirm: đó là quyết định cuối của Driver. Chỉ coi là thành công khi GET cho thấy
     * reservation ĐÃ CONFIRMED khớp identity (ví dụ replay sau timeout); còn lại là bị từ chối — kể cả khi row
     * vẫn RESERVED vì offer vừa hết hạn mà expiry worker chưa chạy (Driver kiểm tra hạn theo đồng hồ DB).
     */
    private ConfirmResult classifyConfirm(UUID reservationId, UUID tripId, UUID driverId, int attempt, String code) {
        try {
            Optional<DriverReservationClient.ReservationStatus> st = driverClient.get(reservationId);
            if (st.isPresent() && matches(st.get(), tripId, driverId, attempt) && "CONFIRMED".equals(st.get().state())) {
                return new ConfirmResult(ConfirmStatus.CONFIRMED, "CONFIRMED");
            }
            String state = st.map(DriverReservationClient.ReservationStatus::state).orElse("NOT_FOUND");
            return new ConfirmResult(ConfirmStatus.REJECTED, "RESERVED".equals(state) ? code : state);
        } catch (RestClientException e) {
            return new ConfirmResult(ConfirmStatus.UNKNOWN, code + " then " + e);
        }
    }

    /** CONFIRMED khớp identity → đã confirm; RESERVED → chưa rõ; còn lại (terminal/404/lệch) → offer không còn. */
    private static ConfirmResult classify(Optional<DriverReservationClient.ReservationStatus> st,
                                          UUID tripId, UUID driverId, int attempt) {
        if (st.isEmpty()) {
            return new ConfirmResult(ConfirmStatus.REJECTED, "NOT_FOUND");
        }
        if (!matches(st.get(), tripId, driverId, attempt)) {
            return new ConfirmResult(ConfirmStatus.REJECTED, "MISMATCH");
        }
        return switch (st.get().state()) {
            case "CONFIRMED" -> new ConfirmResult(ConfirmStatus.CONFIRMED, "CONFIRMED");
            case "RESERVED" -> new ConfirmResult(ConfirmStatus.UNKNOWN, "RESERVED");
            default -> new ConfirmResult(ConfirmStatus.REJECTED, st.get().state());
        };
    }

    private static boolean matches(DriverReservationClient.ReservationStatus st, UUID tripId, UUID driverId, int attempt) {
        return tripId.equals(st.tripId()) && driverId.equals(st.driverId()) && st.attempt() == attempt;
    }

    /** Tx2 của accept sau khi Driver xác nhận: DRIVER_OFFERED → ACCEPTED, saga → AWAITING_FINISH. */
    private String completeAccept(UUID tripId, int attempt, UUID driverUserId, UUID reservationId) {
        SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                .orElseThrow(() -> new IllegalStateException("saga row missing for trip " + tripId));
        TripRepository.TripRow trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new IllegalStateException("trip row missing " + tripId));
        if ("ACCEPTED".equals(trip.state())) {
            return "ACCEPTED"; // request song song cùng driver đã hoàn tất trước
        }
        Map<String, Object> ctx = readContext(saga.contextJson());
        if (!"CONFIRMING".equals(saga.phase()) || !"ACCEPT".equals(ctx.get("intent"))) {
            throw new IllegalStateException("confirm succeeded but saga is " + saga.phase() + " for trip " + tripId);
        }
        int version = tripRepo.markAccepted(tripId, attempt)
                .orElseThrow(() -> new IllegalStateException("trips CAS DRIVER_OFFERED→ACCEPTED failed for " + tripId));
        ctx.remove("intent");
        // Chốt T18: sau ACCEPTED saga ở AWAITING_FINISH (desired NONE) — assignment hiệu lực, chờ complete (T22).
        sagaRepo.updateLocked(tripId, "AWAITING_FINISH", "NONE", null, writeContext(ctx));
        eventRepo.insert(tripId, "TRIP_ACCEPTED", version, driverUserId, "Driver accepted attempt " + attempt);
        // T25 — notification cùng transaction (chốt T25): cả passenger và driver.
        notifications.notify(trip.passengerId(), tripId, "TRIP_ACCEPTED", version);
        notifications.notify(driverUserId, tripId, "TRIP_ACCEPTED", version);
        audit("trip_accepted", tripId, "SUCCESS", "trip accepted tripId={} attempt={} reservationId={}",
                tripId, attempt, reservationId);
        return "ACCEPTED";
    }

    /** Confirm bị Driver từ chối (offer hết hạn/đã release): bỏ intent, saga về WAITING_DRIVER. */
    private void abandonAccept(UUID tripId, String reservationState) {
        SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                .orElseThrow(() -> new IllegalStateException("saga row missing for trip " + tripId));
        Map<String, Object> ctx = readContext(saga.contextJson());
        if ("CONFIRMING".equals(saga.phase()) && "ACCEPT".equals(ctx.get("intent"))) {
            ctx.remove("intent");
            sagaRepo.updateLocked(tripId, "WAITING_DRIVER", "NONE",
                    "confirm rejected by driver-service: " + reservationState, writeContext(ctx));
        }
        audit("trip_accept_rejected", tripId, "FAILURE", "accept rejected tripId={} reservationState={}",
                tripId, reservationState);
    }

    // =====================================================================================
    // T19 — reject + tìm lại
    // =====================================================================================

    /** Việc cần làm sau Tx1 của reject: đã xong (idempotent) hoặc cần release reservation. */
    private record RejectPlan(String doneState, Integer doneAttempt, UUID reservationId, UUID driverId) {
    }

    enum ReleaseStatus { RELEASED, REFUSED, UNKNOWN }

    record ReleaseResult(ReleaseStatus status, String detail) {
    }

    /**
     * POST /api/trips/{id}/reject (contract §3.3). Release đúng reservation (reason REJECTED); CHỈ sau khi
     * Driver xác nhận terminal mới: tăng attempt, thêm driver vào excludedDriverIds, phát TripRequested mới
     * (hoặc NO_DRIVER nếu hết lượt/quá deadline). 202 {tripId, state, matchingAttempt}.
     */
    public CommandOutcome reject(UUID tripId, AuthenticatedUser driver, Integer attempt, UUID correlationId) {
        requireAttempt(attempt);
        RejectPlan plan = tx.execute(s -> planReject(tripId, driver.userId(), attempt));
        if (plan.doneState() != null) {
            return outcome(202, tripId, plan.doneState(), plan.doneAttempt());
        }
        ReleaseResult released = releaseWithReconcile(plan.reservationId(), "REJECTED");
        switch (released.status()) {
            case RELEASED -> {
                return tx.execute(s -> completeReject(tripId, attempt, driver.userId(), plan.driverId(),
                        plan.reservationId(), correlationId, "REJECTED"));
            }
            case REFUSED -> throw new IllegalStateException("driver-service refused release of offered reservation "
                    + plan.reservationId() + ": " + released.detail());
            default -> {
                tx.executeWithoutResult(s -> sagaRepo.recordPendingError(tripId, "release pending: " + released.detail()));
                audit("trip_reject_pending", tripId, "FAILURE",
                        "reject pending reconcile tripId={} reservationId={} detail={}",
                        tripId, plan.reservationId(), released.detail());
                return outcome(202, tripId, "DRIVER_OFFERED", attempt);
            }
        }
    }

    /** Tx1 của reject: khóa saga, kiểm tra offered driver + attempt, ghi intent REJECT (WAITING_DRIVER → COMPENSATING). */
    private RejectPlan planReject(UUID tripId, UUID caller, int attempt) {
        SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                .orElseThrow(() -> new TripNotFoundException("Trip not found."));
        TripRepository.TripRow trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException("Trip not found."));
        Map<String, Object> ctx = readContext(saga.contextJson());

        // Idempotent: cùng driver gửi lại reject cho attempt vừa từ chối xong → trả state hiện tại.
        if (ctx.get("lastRejection") instanceof Map<?, ?> last
                && caller.toString().equals(last.get("driverUserId"))
                && last.get("attempt") instanceof Number n && n.intValue() == attempt) {
            return new RejectPlan(trip.state(), trip.matchingAttempt(), null, null);
        }
        if (!"DRIVER_OFFERED".equals(trip.state())) {
            throw stateConflict(trip, "Trip has no pending offer to reject.");
        }
        if (!caller.equals(trip.driverUserId()) || trip.matchingAttempt() != attempt) {
            throw notOffered(tripId, attempt);
        }
        switch (saga.phase()) {
            case "WAITING_DRIVER" -> {
                ctx.put("intent", "REJECT");
                sagaRepo.updateLocked(tripId, "COMPENSATING", "RELEASE_RESERVATION", null, writeContext(ctx));
                audit("trip_reject_intent", tripId, "SUCCESS", "reject intent recorded tripId={} attempt={}",
                        tripId, attempt);
            }
            case "COMPENSATING" -> {
                if (!"REJECT".equals(ctx.get("intent"))) {
                    throw stateConflict(trip, "Trip is being " + describePending(ctx) + "; reject is not allowed.");
                }
                // Intent REJECT đã ghi (lần gọi trước chưa rõ kết quả) → tiếp tục release với cùng commandId.
            }
            default -> throw stateConflict(trip, "Trip is being " + describePending(ctx) + "; reject is not allowed.");
        }
        return new RejectPlan(null, null, uuid(ctx, "reservationId"), uuid(ctx, "driverId"));
    }

    /**
     * Release một reservation (contract §4.2, version 3, commandId tất định theo reservation + reason).
     * RELEASED/EXPIRED đều là terminal = đã giải phóng. Timeout/5xx → GET state, không suy ra kết quả.
     */
    ReleaseResult releaseWithReconcile(UUID reservationId, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandId", deterministicId("CMD-RELEASE|" + reservationId + "|" + reason).toString());
        body.put("commandVersion", RELEASE_COMMAND_VERSION);
        body.put("reason", reason);
        try {
            return classifyRelease(reservationId, driverClient.release(reservationId, body));
        } catch (RestClientException transport) {
            log.warn("release outcome unknown ({}); reconciling via GET reservationId={}",
                    transport.toString(), reservationId);
            try {
                Optional<String> state = driverClient.getState(reservationId);
                if (state.isPresent() && isTerminal(state.get())) {
                    return new ReleaseResult(ReleaseStatus.RELEASED, state.get());
                }
                // Chưa terminal (hoặc release chưa tới): gửi lại MỘT lần với cùng commandId.
                return classifyRelease(reservationId, driverClient.release(reservationId, body));
            } catch (RestClientException stillUnknown) {
                return new ReleaseResult(ReleaseStatus.UNKNOWN, stillUnknown.toString());
            }
        }
    }

    private ReleaseResult classifyRelease(UUID reservationId, DriverReservationClient.CommandResult r) {
        if (r.ok()) {
            return new ReleaseResult(ReleaseStatus.RELEASED, r.state());
        }
        // STALE_VERSION / COMMAND_CONFLICT: một release khác (version cao hơn hoặc reason khác) đã tới trước —
        // chỉ coi là xong nếu reservation thật sự terminal.
        try {
            Optional<String> state = driverClient.getState(reservationId);
            if (state.isPresent() && isTerminal(state.get())) {
                return new ReleaseResult(ReleaseStatus.RELEASED, state.get());
            }
            return new ReleaseResult(ReleaseStatus.REFUSED, r.errorCode() + " state=" + state.orElse("NOT_FOUND"));
        } catch (RestClientException e) {
            return new ReleaseResult(ReleaseStatus.UNKNOWN, r.errorCode() + " then " + e);
        }
    }

    private static boolean isTerminal(String state) {
        return "RELEASED".equals(state) || "EXPIRED".equals(state);
    }

    /** Tx2 của reject / offer-expiry sau khi release đã terminal: SEARCHING attempt+1 + TripRequested
     *  mới, hoặc NO_DRIVER. reason = {@code REJECTED} (người dùng từ chối) hoặc {@code OFFER_EXPIRED}
     *  (worker T21 — offer TTL hết hạn không ai phản hồi); trip_event tương ứng OFFER_REJECTED/OFFER_EXPIRED.
     *  Guard chấp nhận intent REJECT/OFFER_EXPIRED trong COMPENSATING đúng reservation/attempt;
     *  request song song đã hoàn tất trước → trả idempotent. */
    private CommandOutcome completeReject(UUID tripId, int attempt, UUID driverUserId, UUID driverId,
                                          UUID reservationId, UUID correlationId, String reason) {
        SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                .orElseThrow(() -> new IllegalStateException("saga row missing for trip " + tripId));
        TripRepository.TripRow trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new IllegalStateException("trip row missing " + tripId));
        Map<String, Object> ctx = readContext(saga.contextJson());
        boolean userReject = "COMPENSATING".equals(saga.phase()) && "REJECT".equals(ctx.get("intent"));
        boolean offerExpired = "COMPENSATING".equals(saga.phase()) && "OFFER_EXPIRED".equals(ctx.get("intent"));
        if ((!userReject && !offerExpired) || trip.matchingAttempt() != attempt
                || !reservationId.toString().equals(ctx.get("reservationId"))) {
            // Request song song cùng driver đã hoàn tất reject trước, hoặc intent khác thắng.
            return outcome(202, tripId, trip.state(), trip.matchingAttempt());
        }

        List<String> excluded = new ArrayList<>();
        if (ctx.get("excludedDriverIds") instanceof List<?> list) {
            list.forEach(o -> excluded.add(String.valueOf(o)));
        }
        if (!excluded.contains(driverId.toString())) {
            excluded.add(driverId.toString());
        }
        // offerExpiresAt là key per-offer — bỏ khi offer này kết thúc (offer mới ghi giá trị mới ở T16).
        for (String key : List.of("intent", "reservationId", "driverId", "driverUserId", "attempt", "offerExpiresAt")) {
            ctx.remove(key);
        }
        ctx.put("excludedDriverIds", excluded);
        if (userReject) {
            Map<String, Object> lastRejection = new LinkedHashMap<>();
            lastRejection.put("driverUserId", driverUserId.toString());
            lastRejection.put("attempt", attempt);
            ctx.put("lastRejection", lastRejection);
        }

        boolean exhausted = attempt >= maxMatchingAttempts || sagaRepo.isPastDeadline(tripId);
        String eventType = "OFFER_EXPIRED".equals(reason) ? "OFFER_EXPIRED" : "OFFER_REJECTED";
        if (exhausted) {
            int version = tripRepo.markNoDriverAfterRelease(tripId, attempt)
                    .orElseThrow(() -> new IllegalStateException("trips CAS DRIVER_OFFERED→NO_DRIVER failed for " + tripId));
            eventRepo.insert(tripId, eventType, version, driverUserId, reason + " attempt " + attempt);
            eventRepo.insert(tripId, "RESERVATION_RELEASED", version, null, "Reservation " + reservationId + " released");
            eventRepo.insert(tripId, "NO_DRIVER", version, null, "No driver after " + attempt + " attempts");
            notifications.notify(trip.passengerId(), tripId, "NO_DRIVER", version);
            sagaRepo.updateLocked(tripId, "ABORTED", "NONE", null, writeContext(ctx));
            audit("trip_rejected_no_driver", tripId, "SUCCESS",
                    "offer " + reason.toLowerCase() + ", trip NO_DRIVER tripId={} attempt={}", tripId, attempt);
            return outcome(202, tripId, "NO_DRIVER", attempt);
        }

        int nextAttempt = attempt + 1;
        int version = tripRepo.markSearchingAfterRelease(tripId, attempt)
                .orElseThrow(() -> new IllegalStateException("trips CAS DRIVER_OFFERED→SEARCHING failed for " + tripId));
        eventRepo.insert(tripId, eventType, version, driverUserId, reason + " attempt " + attempt);
        eventRepo.insert(tripId, "RESERVATION_RELEASED", version, null, "Reservation " + reservationId + " released");
        sagaRepo.updateLocked(tripId, "WAITING_MATCH", "NONE", null, writeContext(ctx));

        Map<String, Object> pickup = new LinkedHashMap<>();
        pickup.put("latitude", trip.pickupLat());
        pickup.put("longitude", trip.pickupLng());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("attempt", nextAttempt);
        payload.put("pickup", pickup);
        payload.put("excludedDriverIds", excluded);
        outboxRepo.insertPending(UUID.randomUUID(), "TripRequested", tripId, writeContext(payload), correlationId,
                null, Instant.now(), version, TripInboxHandler.ROUTING_KEY_TRIP_REQUESTED);
        audit("trip_rejected_rematch", tripId, "SUCCESS",
                "offer rejected, re-matching tripId={} nextAttempt={} excludedCount={}",
                tripId, nextAttempt, excluded.size());
        return outcome(202, tripId, "SEARCHING", nextAttempt);
    }

    // =====================================================================================
    // T20 — cancel + tranh chấp với accept
    // =====================================================================================

    /** Việc cần làm sau Tx1 của cancel: đã xong (idempotent) hoặc release các reservation đích. */
    private record CancelPlan(String doneState, List<UUID> targets, boolean fromSearching) {
    }

    /**
     * POST /api/trips/{id}/cancel (contract §3.3, chỉ owner passenger). Thắng/thua accept được phân xử bằng
     * khóa saga trong Tx1. Mọi reservation có thể dính tới trip được release (reason CANCELLED) TRƯỚC khi
     * trip thành CANCELLED — không bao giờ "CANCELLED rồi bỏ quên driver" (spec §10).
     * <ul>
     *   <li>SEARCHING: listener T16 có thể đang reserve dở → release (tạo tombstone) cho MỌI reservationId
     *       tất định của attempt hiện tại; reserve tới trễ nhận TOMBSTONED_RESERVATION. Xong inline → 200.</li>
     *   <li>DRIVER_OFFERED (saga WAITING_DRIVER): release reservation đang offer. Xong inline → 202 CANCELLED.</li>
     *   <li>Kết quả release chưa rõ → 202 CANCELLING; gọi lại cancel để tiếp tục.</li>
     *   <li>Accept đã thắng (CONFIRMING/ACCEPTED/IN_PROGRESS) hoặc trip terminal khác → 409 STATE_CONFLICT;
     *       đã CANCELLED → 200 CANCELLED (idempotent).</li>
     * </ul>
     */
    public CommandOutcome cancel(UUID tripId, AuthenticatedUser passenger) {
        CancelPlan plan = tx.execute(s -> planCancel(tripId, passenger.userId()));
        if (plan.doneState() != null) {
            return outcome(200, tripId, plan.doneState(), null);
        }
        List<String> pending = new ArrayList<>();
        for (UUID reservationId : plan.targets()) {
            ReleaseResult r = releaseWithReconcile(reservationId, "CANCELLED");
            if (r.status() != ReleaseStatus.RELEASED) {
                pending.add(reservationId + ":" + r.status() + ":" + r.detail());
            }
        }
        if (!pending.isEmpty()) {
            tx.executeWithoutResult(s -> sagaRepo.recordPendingError(tripId, "cancel release pending: " + pending));
            audit("trip_cancel_pending", tripId, "FAILURE", "cancel pending reconcile tripId={} pending={}",
                    tripId, pending);
            return outcome(202, tripId, "CANCELLING", null);
        }
        String state = tx.execute(s -> completeCancel(tripId, passenger.userId()));
        return outcome(plan.fromSearching() ? 200 : 202, tripId, state, null);
    }

    /** Tx1 của cancel: khóa saga, kiểm tra owner + state, ghi intent CANCEL (→ COMPENSATING) và trip CANCELLING. */
    private CancelPlan planCancel(UUID tripId, UUID caller) {
        SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                .orElseThrow(() -> new TripNotFoundException("Trip not found."));
        TripRepository.TripRow trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException("Trip not found."));
        if (!caller.equals(trip.passengerId())) {
            throw new TripNotFoundException("Trip not found."); // không lộ trip của người khác (§1.2)
        }
        Map<String, Object> ctx = readContext(saga.contextJson());
        switch (trip.state()) {
            case "CANCELLED" -> {
                return new CancelPlan("CANCELLED", List.of(), false);
            }
            case "CANCELLING" -> {
                if (!"COMPENSATING".equals(saga.phase()) || !"CANCEL".equals(ctx.get("intent"))) {
                    throw new IllegalStateException("trip CANCELLING but saga is " + saga.phase());
                }
                return new CancelPlan(null, uuids(ctx.get("cancelTargets")), "SEARCHING".equals(ctx.get("cancelFrom")));
            }
            case "SEARCHING" -> {
                if (!"WAITING_MATCH".equals(saga.phase())) {
                    throw stateConflict(trip, "Trip is being processed; retry cancel shortly.");
                }
                int attempt = trip.matchingAttempt();
                List<UUID> targets = new ArrayList<>();
                for (int i = 0; i < maxCandidates; i++) {
                    targets.add(TripInboxHandler.deterministicReservationId(tripId, attempt, i));
                }
                startCancel(trip, ctx, targets, "SEARCHING");
                return new CancelPlan(null, targets, true);
            }
            case "DRIVER_OFFERED" -> {
                switch (saga.phase()) {
                    case "WAITING_DRIVER" -> {
                        List<UUID> targets = List.of(uuid(ctx, "reservationId"));
                        startCancel(trip, ctx, targets, "DRIVER_OFFERED");
                        return new CancelPlan(null, targets, false);
                    }
                    case "CONFIRMING" -> throw stateConflict(trip,
                            "The driver is accepting this trip; cancel is only allowed before accept wins.");
                    default -> throw stateConflict(trip,
                            "Trip is being " + describePending(ctx) + "; retry cancel shortly.");
                }
            }
            default -> throw stateConflict(trip, "Trip can only be cancelled before a driver accepts it.");
        }
    }

    private void startCancel(TripRepository.TripRow trip, Map<String, Object> ctx, List<UUID> targets, String from) {
        ctx.put("intent", "CANCEL");
        ctx.put("cancelFrom", from);
        ctx.put("cancelTargets", targets.stream().map(UUID::toString).toList());
        sagaRepo.updateLocked(trip.id(), "COMPENSATING", "RELEASE_RESERVATION", null, writeContext(ctx));
        int version = tripRepo.markCancelling(trip.id(), from)
                .orElseThrow(() -> new IllegalStateException("trips CAS " + from + "→CANCELLING failed for " + trip.id()));
        eventRepo.insert(trip.id(), "CANCELLING", version, trip.passengerId(), "Passenger cancelled from " + from);
        audit("trip_cancel_intent", trip.id(), "SUCCESS", "cancel intent recorded tripId={} from={} targets={}",
                trip.id(), from, targets.size());
    }

    /** Tx2 của cancel sau khi mọi reservation đích đã terminal: CANCELLING → CANCELLED, saga ABORTED. */
    private String completeCancel(UUID tripId, UUID passengerUserId) {
        SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                .orElseThrow(() -> new IllegalStateException("saga row missing for trip " + tripId));
        TripRepository.TripRow trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new IllegalStateException("trip row missing " + tripId));
        if ("CANCELLED".equals(trip.state())) {
            return "CANCELLED"; // request song song đã hoàn tất trước
        }
        Map<String, Object> ctx = readContext(saga.contextJson());
        if (!"COMPENSATING".equals(saga.phase()) || !"CANCEL".equals(ctx.get("intent"))) {
            throw new IllegalStateException("cancel releases done but saga is " + saga.phase() + " for trip " + tripId);
        }
        int version = tripRepo.markCancelled(tripId)
                .orElseThrow(() -> new IllegalStateException("trips CAS CANCELLING→CANCELLED failed for " + tripId));
        if ("DRIVER_OFFERED".equals(ctx.get("cancelFrom"))) {
            eventRepo.insert(tripId, "RESERVATION_RELEASED", version, null,
                    "Reservation " + ctx.get("reservationId") + " released (cancel)");
        }
        eventRepo.insert(tripId, "TRIP_CANCELLED", version, passengerUserId, "Trip cancelled by passenger");
        // T25 — notification cùng transaction (chốt T25): passenger luôn; driver nếu đã offer lúc hủy
        // (trip row đọc TRƯỚC markCancelled — sau đó driver_user_id bị NULL).
        notifications.notify(passengerUserId, tripId, "TRIP_CANCELLED", version);
        if (trip.driverUserId() != null) {
            notifications.notify(trip.driverUserId(), tripId, "TRIP_CANCELLED", version);
        }
        ctx.remove("intent");
        sagaRepo.updateLocked(tripId, "ABORTED", "NONE", null, writeContext(ctx));
        audit("trip_cancelled", tripId, "SUCCESS", "trip cancelled tripId={} from={}", tripId, ctx.get("cancelFrom"));
        return "CANCELLED";
    }

    private static List<UUID> uuids(Object raw) {
        List<UUID> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            list.forEach(o -> out.add(UUID.fromString(String.valueOf(o))));
        }
        return out;
    }

    // =====================================================================================
    // T21 — saga recovery: resume intent treo, offer TTL, deadline tổng (worker SagaRecoveryWorker)
    // =====================================================================================

    /** Intent ACCEPT treo đọc dưới khóa saga; null nếu không còn việc pending. */
    private record PendingConfirm(UUID reservationId, UUID driverId, UUID driverUserId, int attempt) {
    }

    /**
     * T21 — resume intent ACCEPT treo (request accept timeout / tiến trình chết giữa chừng):
     * gọi lại {@link #confirmWithReconcile} + {@code completeAccept}/{@code abandonAccept} đúng như
     * request path. commandId tất định theo reservation → replay idempotent phía driver; khóa row saga
     * serialize với request song song (double-confirm cùng commandId → driver dedup §4.1).
     */
    public void resumePendingConfirm(UUID tripId) {
        PendingConfirm p = tx.execute(s -> {
            SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                    .orElseThrow(() -> new IllegalStateException("saga row missing for trip " + tripId));
            if (!"CONFIRMING".equals(saga.phase())) {
                return null; // intent đã hoàn tất hoặc action khác thắng
            }
            Map<String, Object> ctx = readContext(saga.contextJson());
            if (!"ACCEPT".equals(ctx.get("intent"))) {
                return null;
            }
            return new PendingConfirm(uuid(ctx, "reservationId"), uuid(ctx, "driverId"),
                    uuid(ctx, "driverUserId"), ((Number) ctx.get("attempt")).intValue());
        });
        if (p == null) {
            return;
        }
        ConfirmResult result = confirmWithReconcile(p.reservationId(), tripId, p.driverId(), p.attempt());
        switch (result.status()) {
            case CONFIRMED -> tx.execute(s -> completeAccept(tripId, p.attempt(), p.driverUserId(), p.reservationId()));
            case REJECTED -> tx.executeWithoutResult(s -> abandonAccept(tripId, result.detail()));
            default -> tx.executeWithoutResult(s -> sagaRepo.recordPendingError(tripId, "confirm pending: " + result.detail()));
        }
    }

    /** Intent bù trừ treo đọc dưới khóa saga; null nếu không còn việc pending. */
    private record PendingCompensation(String intent, String reason, UUID reservationId, UUID driverId,
                                       UUID driverUserId, int attempt, UUID passengerUserId, List<UUID> targets) {
    }

    /**
     * T21 — resume intent REJECT/CANCEL/OFFER_EXPIRED/MATCH_DEADLINE treo: release lại các
     * reservation đích với cùng commandId tất định rồi {@code completeReject}/{@code completeCancel}.
     * Chưa terminal → ghi pending error, chu kỳ worker kế tiếp thử lại sau lease hết hạn.
     */
    public void resumePendingCompensation(UUID tripId) {
        PendingCompensation plan = tx.execute(s -> readPendingCompensation(tripId));
        if (plan == null) {
            return;
        }
        boolean allTerminal = true;
        for (UUID reservationId : plan.targets()) {
            ReleaseResult r = releaseWithReconcile(reservationId, plan.reason());
            if (r.status() != ReleaseStatus.RELEASED) {
                allTerminal = false;
                break;
            }
        }
        if (!allTerminal) {
            tx.executeWithoutResult(s -> sagaRepo.recordPendingError(tripId,
                    "compensation release pending: " + plan.targets()));
            return;
        }
        if ("REJECT".equals(plan.intent()) || "OFFER_EXPIRED".equals(plan.intent())) {
            // correlationId mới, causation null — timeout nội bộ không có event upstream (events.md §5).
            tx.execute(s -> completeReject(tripId, plan.attempt(), plan.driverUserId(), plan.driverId(),
                    plan.reservationId(), UUID.randomUUID(), plan.reason()));
        } else if ("MATCH_DEADLINE".equals(plan.intent())) {
            tx.executeWithoutResult(s -> completeDeadlineNoDriver(tripId));
        } else {
            tx.execute(s -> completeCancel(tripId, plan.passengerUserId()));
        }
    }

    private PendingCompensation readPendingCompensation(UUID tripId) {
        SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                .orElseThrow(() -> new IllegalStateException("saga row missing for trip " + tripId));
        if (!"COMPENSATING".equals(saga.phase())) {
            return null;
        }
        Map<String, Object> ctx = readContext(saga.contextJson());
        String intent = (String) ctx.get("intent");
        TripRepository.TripRow trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new IllegalStateException("trip row missing " + tripId));
        if ("REJECT".equals(intent) || "OFFER_EXPIRED".equals(intent)) {
            return new PendingCompensation(intent, "REJECT".equals(intent) ? "REJECTED" : "OFFER_EXPIRED", uuid(ctx, "reservationId"),
                    uuid(ctx, "driverId"), uuid(ctx, "driverUserId"), ((Number) ctx.get("attempt")).intValue(),
                    trip.passengerId(), List.of(uuid(ctx, "reservationId")));
        }
        if ("CANCEL".equals(intent)) {
            return new PendingCompensation("CANCEL", "CANCELLED", null, null, null, 0,
                    trip.passengerId(), uuids(ctx.get("cancelTargets")));
        }
        if ("MATCH_DEADLINE".equals(intent)) {
            return new PendingCompensation(intent, "CANCELLED", null, null, null, trip.matchingAttempt(),
                    trip.passengerId(), uuids(ctx.get("deadlineTargets")));
        }
        return null;
    }

    /** Thông tin offer đọc dưới khóa saga; null nếu saga không còn WAITING_DRIVER chờ phản hồi. */
    private record OfferInfo(UUID reservationId, UUID driverId, UUID driverUserId, int attempt) {
    }

    /**
     * T21 — offer TTL hết hạn mà không ai accept/reject: release reason {@code OFFER_EXPIRED} rồi tìm lại
     * (SEARCHING attempt+1, excluded += driver) hoặc NO_DRIVER — cùng đường với reject. Chỉ chạy khi saga
     * vẫn WAITING_DRIVER không intent; reservation CONFIRMED KHÔNG bị giải phóng bằng cách này
     * (invariant 8: phía driver từ chối release CONFIRMED với reason khác CANCELLED → REFUSED, bỏ qua).
     */
    public void handleOfferExpired(UUID tripId) {
        OfferInfo info = tx.execute(s -> {
            SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                    .orElseThrow(() -> new IllegalStateException("saga row missing for trip " + tripId));
            Map<String, Object> ctx = readContext(saga.contextJson());
            if (!"WAITING_DRIVER".equals(saga.phase()) || ctx.get("intent") != null
                    || !sagaRepo.isOfferExpired(tripId)) {
                return null; // accept/reject/cancel đã thắng trước
            }
            TripRepository.TripRow trip = tripRepo.findById(tripId)
                    .orElseThrow(() -> new IllegalStateException("trip row missing " + tripId));
            if (!"DRIVER_OFFERED".equals(trip.state())) {
                return null;
            }
            ctx.put("intent", "OFFER_EXPIRED");
            sagaRepo.updateLocked(tripId, "COMPENSATING", "RELEASE_RESERVATION", null, writeContext(ctx));
            return new OfferInfo(uuid(ctx, "reservationId"), uuid(ctx, "driverId"),
                    uuid(ctx, "driverUserId"), ((Number) ctx.get("attempt")).intValue());
        });
        if (info == null) {
            return;
        }
        ReleaseResult r = releaseWithReconcile(info.reservationId(), "OFFER_EXPIRED");
        switch (r.status()) {
            case RELEASED -> tx.execute(s -> completeReject(tripId, info.attempt(), info.driverUserId(),
                    info.driverId(), info.reservationId(), UUID.randomUUID(), "OFFER_EXPIRED"));
            case REFUSED -> log.info("offer-expired release refused for trip {} (reservation no longer releasable): {}",
                    tripId, r.detail());
            default -> tx.executeWithoutResult(s -> sagaRepo.recordPendingError(tripId,
                    "offer expiry release pending: " + r.detail()));
        }
    }

    /**
     * T21 — deadline tổng tìm tài xế đã qua mà chưa có offer (matching im lặng/crash): release MỌI
     * reservationId tất định của attempt hiện tại (tombstone cho reserve đang bay, giống cancel-from-SEARCHING
     * ở T20) rồi NO_DRIVER + saga ABORTED. Reserve tới trễ nhận 409 TOMBSTONED_RESERVATION — không hồi sinh.
     */
    public void handleWaitingMatchDeadline(UUID tripId) {
        boolean planned = Boolean.TRUE.equals(tx.execute(s -> {
            SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                    .orElseThrow(() -> new IllegalStateException("saga row missing for trip " + tripId));
            if (!"WAITING_MATCH".equals(saga.phase()) || !sagaRepo.isPastDeadline(tripId)) {
                return false;
            }
            TripRepository.TripRow trip = tripRepo.findById(tripId)
                    .orElseThrow(() -> new IllegalStateException("trip row missing " + tripId));
            if (!"SEARCHING".equals(trip.state())) {
                return false;
            }
            Map<String, Object> ctx = readContext(saga.contextJson());
            List<String> targets = new ArrayList<>();
            for (int i = 0; i < maxCandidates; i++) {
                targets.add(TripInboxHandler.deterministicReservationId(tripId, trip.matchingAttempt(), i).toString());
            }
            ctx.put("intent", "MATCH_DEADLINE");
            ctx.put("deadlineTargets", targets);
            sagaRepo.updateLocked(tripId, "COMPENSATING", "RELEASE_RESERVATION", null, writeContext(ctx));
            return true;
        }));
        if (!planned) {
            return;
        }
        resumePendingCompensation(tripId);
    }

    /** Tx: NO_DRIVER terminal + trip_event + saga ABORTED; idempotent nếu worker khác đã làm. */
    private void completeDeadlineNoDriver(UUID tripId) {
        SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                .orElseThrow(() -> new IllegalStateException("saga row missing for trip " + tripId));
        TripRepository.TripRow trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new IllegalStateException("trip row missing " + tripId));
        if ("NO_DRIVER".equals(trip.state())) {
            // Worker khác đã hoàn tất cùng intent — chỉ đảm bảo saga ABORTED.
            if (!"ABORTED".equals(saga.phase())) {
                sagaRepo.updateLocked(tripId, "ABORTED", "NONE", null, writeContext(readContext(saga.contextJson())));
            }
            return;
        }
        if (!"SEARCHING".equals(trip.state()) || !"COMPENSATING".equals(saga.phase())
                || !"MATCH_DEADLINE".equals(readContext(saga.contextJson()).get("intent"))) {
            throw new IllegalStateException("deadline cleanup: trip " + tripId + " is " + trip.state()
                    + " with saga phase " + saga.phase());
        }
        int version = trip.version() + 1;
        int rows = tripRepo.markNoDriver(tripId, "SEARCHING", trip.matchingAttempt());
        if (rows != 1) {
            throw new IllegalStateException("trips CAS SEARCHING→NO_DRIVER failed for " + tripId);
        }
        eventRepo.insert(tripId, "NO_DRIVER", version, null, "No driver found after matching deadline");
        notifications.notify(trip.passengerId(), tripId, "NO_DRIVER", version);
        sagaRepo.updateLocked(tripId, "ABORTED", "NONE", null, writeContext(readContext(saga.contextJson())));
        audit("no_driver_deadline", tripId, "SUCCESS",
                "matching deadline passed with no offer; trip NO_DRIVER tripId={}", tripId);
    }

    // =====================================================================================
    // T22 — start / complete + finish driver (durable intent FINISH_ASSIGNMENT)
    // =====================================================================================

    /**
     * POST /api/trips/{id}/start (contract §3.3): chỉ assigned driver, state phải ACCEPTED.
     * 200 {tripId, state:IN_PROGRESS}; trip_event TRIP_STARTED; saga giữ AWAITING_FINISH (desired NONE).
     */
    public CommandOutcome start(UUID tripId, AuthenticatedUser driver) {
        String state = tx.execute(s -> doStart(tripId, driver.userId()));
        return outcome(200, tripId, state, null);
    }

    /** Tx của start: khóa saga (serialize với complete/cancel), guard ACCEPTED + assigned driver,
     *  CAS IN_PROGRESS + trip_event. Lặp lại trên IN_PROGRESS → 409 STATE_CONFLICT (contract: state phải là ACCEPTED). */
    private String doStart(UUID tripId, UUID caller) {
        SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                .orElseThrow(() -> new TripNotFoundException("Trip not found."));
        TripRepository.TripRow trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException("Trip not found."));
        if (!"ACCEPTED".equals(trip.state())) {
            throw stateConflict(trip, "Trip must be ACCEPTED before start.");
        }
        if (!caller.equals(trip.driverUserId())) {
            throw notAssignedDriver(tripId);
        }
        int version = tripRepo.markInProgress(tripId)
                .orElseThrow(() -> new IllegalStateException("trips CAS ACCEPTED→IN_PROGRESS failed for " + tripId));
        eventRepo.insert(tripId, "TRIP_STARTED", version, caller, "Driver started the trip");
        audit("trip_started", tripId, "SUCCESS", "trip started tripId={} driverUserId={}", tripId, caller);
        return "IN_PROGRESS";
    }

    /**
     * POST /api/trips/{id}/complete (contract §3.3): chỉ assigned driver, state phải IN_PROGRESS.
     * Tx1: COMPLETED + payment PENDING/attempt 1 + trip_event TRIP_COMPLETED + outbox TripCompleted
     * (causation null — HTTP-triggered) + intent finish bền (saga AWAITING_FINISH, desired FINISH_ASSIGNMENT).
     * Ngoài tx: gọi finish driver; thành công → saga COMPLETED terminal; chưa rõ → giữ pending,
     * worker resume (T21 pattern). Response 200 {state:COMPLETED, paymentStatus:PENDING}
     * KHÔNG phụ thuộc kết quả finish — intent nằm bền trong saga.
     */
    public CommandOutcome complete(UUID tripId, AuthenticatedUser driver, UUID correlationId) {
        UUID reservationId = tx.execute(s -> planComplete(tripId, driver.userId(), correlationId));
        FinishResult result;
        try {
            result = finishWithReconcile(reservationId, tripId);
        } catch (RuntimeException e) {
            // COMPLETED + TripCompleted đã commit: lỗi ngoài transport (vd 4xx thiếu code, response thiếu
            // state) không được biến complete thành 500 — intent giữ pending cho worker.
            result = new FinishResult(FinishStatus.UNKNOWN, e.toString());
        }
        if (result.status() == FinishStatus.FINISHED) {
            tx.executeWithoutResult(s -> completeFinish(tripId));
        } else {
            String detail = result.detail();
            tx.executeWithoutResult(s -> sagaRepo.recordPendingError(tripId, "finish pending: " + detail));
            audit("trip_complete_finish_pending", tripId, "FAILURE",
                    "complete committed but finish pending reconcile tripId={} reservationId={} detail={}",
                    tripId, reservationId, detail);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tripId", tripId.toString());
        body.put("state", "COMPLETED");
        body.put("paymentStatus", "PENDING");
        return new CommandOutcome(200, body);
    }

    /** Tx1 của complete: khóa saga, guard IN_PROGRESS + assigned driver, ghi intent FINISH_ASSIGNMENT,
     *  COMPLETED + payment PENDING/attempt 1, trip_event TRIP_COMPLETED, outbox TripCompleted.
     *  Complete lặp trên COMPLETED → 409 STATE_CONFLICT trước mọi ghi — không phát intent/event mới. */
    private UUID planComplete(UUID tripId, UUID caller, UUID correlationId) {
        SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                .orElseThrow(() -> new TripNotFoundException("Trip not found."));
        TripRepository.TripRow trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException("Trip not found."));
        if (!"IN_PROGRESS".equals(trip.state())) {
            throw stateConflict(trip, "Trip must be IN_PROGRESS before complete.");
        }
        if (!caller.equals(trip.driverUserId())) {
            throw notAssignedDriver(tripId);
        }
        Map<String, Object> ctx = readContext(saga.contextJson());
        UUID reservationId = uuid(ctx, "reservationId");
        // Chốt T22: intent finish bền — saga AWAITING_FINISH + desired FINISH_ASSIGNMENT;
        // worker resume nếu HTTP chưa rõ (state-machines.md §2).
        sagaRepo.updateLocked(tripId, "AWAITING_FINISH", "FINISH_ASSIGNMENT", null, writeContext(ctx));
        int version = tripRepo.markCompleted(tripId)
                .orElseThrow(() -> new IllegalStateException("trips CAS IN_PROGRESS→COMPLETED failed for " + tripId));
        eventRepo.insert(tripId, "TRIP_COMPLETED", version, caller, "Driver completed the trip");
        // T25 — notification cùng transaction (chốt T25): cả passenger và driver.
        notifications.notify(trip.passengerId(), tripId, "TRIP_COMPLETED", version);
        notifications.notify(caller, tripId, "TRIP_COMPLETED", version);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("attempt", 1);
        payload.put("amountVnd", trip.fareVnd());
        payload.put("currency", "VND");
        // causation null — HTTP-triggered (events.md §5); correlation = request complete.
        outboxRepo.insertPending(UUID.randomUUID(), "TripCompleted", tripId, writeContext(payload),
                correlationId, null, Instant.now(), version, ROUTING_KEY_TRIP_COMPLETED);
        audit("trip_completed", tripId, "SUCCESS",
                "trip completed tripId={} fareVnd={} reservationId={}", tripId, trip.fareVnd(), reservationId);
        return reservationId;
    }

    enum FinishStatus { FINISHED, UNKNOWN }

    record FinishResult(FinishStatus status, String detail) {
    }

    /**
     * T22 — POST finish (contract §4.2, version 4, commandId tất định theo reservation).
     * RELEASED khớp trip = đã xong (sau CONFIRMED chỉ finish đưa reservation tới terminal);
     * timeout/5xx → GET reconcile rồi gửi lại MỘT lần cùng commandId; còn không rõ → UNKNOWN
     * để intent treo trong saga cho worker retry.
     */
    FinishResult finishWithReconcile(UUID reservationId, UUID tripId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandId", deterministicId("CMD-FINISH|" + reservationId).toString());
        body.put("commandVersion", FINISH_COMMAND_VERSION);
        body.put("tripId", tripId.toString());
        try {
            DriverReservationClient.CommandResult r = driverClient.finish(reservationId, body);
            if (r.ok()) {
                return new FinishResult(FinishStatus.FINISHED, r.state());
            }
            // 409 STATE_CONFLICT / STALE_VERSION / COMMAND_CONFLICT hoặc 404: đọc state thật để quyết định.
            return classifyFinish(reservationId, tripId, r.errorCode());
        } catch (RestClientException transport) {
            log.warn("finish outcome unknown ({}); reconciling via GET reservationId={}", transport.toString(), reservationId);
            try {
                Optional<DriverReservationClient.ReservationStatus> st = driverClient.get(reservationId);
                if (st.isPresent() && matchesTrip(st.get(), tripId) && "RELEASED".equals(st.get().state())) {
                    // Finish đã tới Driver dù HTTP timeout: coi là xong.
                    return new FinishResult(FinishStatus.FINISHED, st.get().state());
                }
                // Chưa terminal (hoặc finish chưa tới): gửi lại MỘT lần với cùng commandId (an toàn nhờ dedup §4.1).
                DriverReservationClient.CommandResult r2 = driverClient.finish(reservationId, body);
                return r2.ok() ? new FinishResult(FinishStatus.FINISHED, r2.state())
                        : classifyFinish(reservationId, tripId, r2.errorCode());
            } catch (RestClientException stillUnknown) {
                return new FinishResult(FinishStatus.UNKNOWN, stillUnknown.toString());
            }
        }
    }

    /** Driver trả 4xx cho finish: đọc state thật — RELEASED khớp trip = đã xong; còn lại giữ pending. */
    private FinishResult classifyFinish(UUID reservationId, UUID tripId, String code) {
        try {
            Optional<DriverReservationClient.ReservationStatus> st = driverClient.get(reservationId);
            if (st.isPresent() && matchesTrip(st.get(), tripId) && "RELEASED".equals(st.get().state())) {
                return new FinishResult(FinishStatus.FINISHED, "RELEASED");
            }
            String state = st.map(DriverReservationClient.ReservationStatus::state).orElse("NOT_FOUND");
            return new FinishResult(FinishStatus.UNKNOWN, code + " state=" + state);
        } catch (RestClientException e) {
            return new FinishResult(FinishStatus.UNKNOWN, code + " then " + e);
        }
    }

    private static boolean matchesTrip(DriverReservationClient.ReservationStatus st, UUID tripId) {
        return tripId.equals(st.tripId());
    }

    /** Tx2 của complete sau khi Driver xác nhận finish (RELEASED/TRIP_COMPLETED): saga → COMPLETED terminal.
     *  Idempotent nếu request/worker song song đã hoàn tất trước. */
    private void completeFinish(UUID tripId) {
        SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                .orElseThrow(() -> new IllegalStateException("saga row missing for trip " + tripId));
        if ("COMPLETED".equals(saga.phase())) {
            return; // request/worker song song đã hoàn tất trước
        }
        Map<String, Object> ctx = readContext(saga.contextJson());
        if (!"AWAITING_FINISH".equals(saga.phase()) || !"FINISH_ASSIGNMENT".equals(saga.desiredAction())) {
            throw new IllegalStateException("finish succeeded but saga is " + saga.phase() + "/"
                    + saga.desiredAction() + " for trip " + tripId);
        }
        sagaRepo.updateLocked(tripId, "COMPLETED", "NONE", null, writeContext(ctx));
        audit("trip_finish_done", tripId, "SUCCESS", "driver released after trip; saga COMPLETED tripId={}", tripId);
    }

    /**
     * T22 — resume intent finish treo (complete timeout / tiến trình chết giữa chừng): gọi lại
     * {@link #finishWithReconcile} + {@code completeFinish} đúng như request path. commandId tất định
     * theo reservation → replay idempotent phía driver; khóa row saga serialize với request song song.
     */
    public void resumePendingFinish(UUID tripId) {
        UUID reservationId = tx.execute(s -> {
            SagaRepository.SagaRow saga = sagaRepo.lockByTripId(tripId)
                    .orElseThrow(() -> new IllegalStateException("saga row missing for trip " + tripId));
            if (!"AWAITING_FINISH".equals(saga.phase()) || !"FINISH_ASSIGNMENT".equals(saga.desiredAction())) {
                return null; // intent đã hoàn tất hoặc phase khác
            }
            Map<String, Object> ctx = readContext(saga.contextJson());
            return uuid(ctx, "reservationId");
        });
        if (reservationId == null) {
            return;
        }
        FinishResult result = finishWithReconcile(reservationId, tripId);
        switch (result.status()) {
            case FINISHED -> tx.executeWithoutResult(s -> completeFinish(tripId));
            default -> tx.executeWithoutResult(s -> sagaRepo.recordPendingError(tripId,
                    "finish pending: " + result.detail()));
        }
    }

    // =====================================================================================
    // helpers
    // =====================================================================================

    private static void requireAttempt(Integer attempt) {
        if (attempt == null || attempt < 1) {
            throw new InvalidInputException("attempt is required and must be >= 1.");
        }
    }

    private static StateConflictException stateConflict(TripRepository.TripRow trip, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tripId", trip.id().toString());
        details.put("state", trip.state());
        return new StateConflictException(message, details);
    }

    private static NotOfferedDriverException notOffered(UUID tripId, int attempt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tripId", tripId.toString());
        details.put("attempt", attempt);
        return new NotOfferedDriverException("Caller is not the driver offered this trip for this attempt.", details);
    }

    private static NotAssignedDriverException notAssignedDriver(UUID tripId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tripId", tripId.toString());
        return new NotAssignedDriverException("Caller is not the driver assigned to this trip.", details);
    }

    private static String describePending(Map<String, Object> ctx) {
        Object intent = ctx.get("intent");
        if ("CANCEL".equals(intent)) {
            return "cancelled";
        }
        if ("REJECT".equals(intent)) {
            return "rejected";
        }
        return "processed";
    }

    private static CommandOutcome outcome(int status, UUID tripId, String state, Integer matchingAttempt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tripId", tripId.toString());
        body.put("state", state);
        if (matchingAttempt != null) {
            body.put("matchingAttempt", matchingAttempt);
        }
        return new CommandOutcome(status, body);
    }

    static UUID deterministicId(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID uuid(Map<String, Object> ctx, String key) {
        Object v = ctx.get(key);
        if (!(v instanceof String s)) {
            throw new IllegalStateException("saga context missing " + key);
        }
        return UUID.fromString(s);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readContext(String contextJson) {
        try {
            Map<String, Object> ctx = json.readValue(
                    (contextJson == null ? "{}" : contextJson).getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
            return ctx == null ? new LinkedHashMap<>() : ctx;
        } catch (Exception e) {
            throw new IllegalStateException("saga context unreadable: " + e.getMessage(), e);
        }
    }

    private String writeContext(Map<String, Object> ctx) {
        try {
            return json.writeValueAsString(ctx);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize saga context", e);
        }
    }

    private void audit(String eventType, UUID tripId, String result, String message, Object... args) {
        AuditLog.info(log, AuditLog.mdc("tripId", tripId.toString(), "eventType", eventType, "result", result),
                message, args);
    }
}
