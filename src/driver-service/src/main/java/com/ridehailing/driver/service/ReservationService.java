package com.ridehailing.driver.service;

import com.ridehailing.driver.exception.CommandConflictException;
import com.ridehailing.driver.exception.DriverNotAvailableException;
import com.ridehailing.driver.exception.InvalidInputException;
import com.ridehailing.driver.exception.NotFoundException;
import com.ridehailing.driver.exception.StaleVersionException;
import com.ridehailing.driver.exception.StateConflictException;
import com.ridehailing.driver.exception.TombstonedReservationException;
import com.ridehailing.driver.repo.DriverRepository;
import com.ridehailing.driver.repo.ReservationRepository;
import com.ridehailing.driver.web.dto.ReserveRequest;
import com.ridehailing.driver.web.dto.ConfirmRequest;
import com.ridehailing.driver.web.dto.ReleaseRequest;
import com.ridehailing.driver.web.dto.FinishRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * T10/T11 — reserve, confirm, release, finish and RESERVED expiry.
 * Each external command claims its ID, locks its reservation, mutates fenced driver state
 * and stores the response in one transaction. Missing rows are covered by the resource lock.
 */
@Service
public class ReservationService {

    /** Original command response: 201 for reserve, 200 for lifecycle commands, identical on replay. */
    public record ReserveOutcome(int httpStatus, Map<String, Object> body) {
    }

    /** GET /internal/reservations/{reservationId} — view theo contract §4.2. */
    public record ReservationView(UUID reservationId, String state, UUID tripId, UUID driverId,
                                 Integer attempt, Instant expiresAt, int version) {
    }

    /** N11 — audit log: reservation_reserved/confirmed/released/finished/expired; không ghi secret. */
    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final DriverRepository driverRepo;
    private final ReservationRepository reservationRepo;
    private final ObjectMapper json;
    private final long heartbeatStaleAfterMs;

    public ReservationService(DriverRepository driverRepo,
                             ReservationRepository reservationRepo,
                             ObjectMapper json,
                             @Value("${driver.heartbeat-stale-after-ms:15000}") long heartbeatStaleAfterMs) {
        this.driverRepo = driverRepo;
        this.reservationRepo = reservationRepo;
        this.json = json;
        this.heartbeatStaleAfterMs = heartbeatStaleAfterMs;
    }

    /**
     * RESERVE nguyên tử (chốt T10, thứ tự kiểm tra):
     * 1) claim commandId atomically: same scoped hash -> replay; mismatch -> COMMAND_CONFLICT.
     * 2) tombstone: reservation đã terminal (RELEASED/EXPIRED) -> 409 TOMBSTONED_RESERVATION
     *    (invariant 6 — release tới trước không cho reserve trễ hồi sinh assignment).
     * 3) version: commandVersion > last_command_version (0 nếu chưa có row), ngược lại 409 STALE_VERSION.
     * 4) atomic: INSERT reservation + CAS driver WHERE status='AVAILABLE' AND heartbeat mới;
     *    affected != 1 -> rollback toàn bộ + 409 DRIVER_NOT_AVAILABLE.
     * 5) complete the claimed response in the same transaction; failures roll back the claim too.
     */
    @Transactional
    public ReserveOutcome reserve(UUID reservationId, ReserveRequest cmd) {
        validate(cmd);
        return execute(reservationId, cmd.commandId(), cmd.commandVersion(), "RESERVE", requestHash(cmd),
                () -> doReserve(reservationId, cmd));
    }

    private ReserveOutcome doReserve(UUID reservationId, ReserveRequest cmd) {
        var existing = reservationRepo.findById(reservationId);
        if (existing.isPresent()) {
            String state = existing.get().state();
            if ("RELEASED".equals(state) || "EXPIRED".equals(state)) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("reservationId", reservationId.toString());
                details.put("state", state);
                details.put("terminalReason", existing.get().terminalReason());
                throw new TombstonedReservationException(
                        "Reservation " + reservationId + " is already terminal (" + state + "); late reserve rejected.",
                        details);
            }
        }

        checkVersion(reservationId, cmd.commandVersion(), existing.orElse(null));
        if (existing.isPresent()) {
            throw conflict(existing.get(), "Reservation already exists; use a lifecycle command.");
        }

        Instant heartbeatCutoff = Instant.now().minusMillis(heartbeatStaleAfterMs);
        try {
            reservationRepo.insertReserved(reservationId, cmd.tripId(), cmd.driverId(), cmd.attempt(),
                    cmd.expiresAt(), (int) cmd.commandVersion(),
                    cmd.pickup().latitude(), cmd.pickup().longitude(),
                    cmd.destination().latitude(), cmd.destination().longitude(),
                    cmd.distanceM(), cmd.fareVnd());
        } catch (DataIntegrityViolationException e) {
            // unique(trip_id, attempt): trip+attempt này đã có reservation — driver không thể nhận thêm.
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("tripId", cmd.tripId().toString());
            details.put("attempt", cmd.attempt());
            throw new DriverNotAvailableException(
                    "A reservation for this trip and attempt already exists.", details);
        }

        int updated = reservationRepo.casReserveDriver(cmd.driverId(), reservationId, heartbeatCutoff);
        if (updated != 1) {
            // Exception -> Spring rollback cả INSERT reservation; báo state thực của driver.
            Map<String, Object> details = new LinkedHashMap<>();
            driverRepo.findById(cmd.driverId()).ifPresent(d -> {
                details.put("state", d.status());
                details.put("lastSeenAt", d.lastSeenAt() == null ? null : d.lastSeenAt().toString());
            });
            throw new DriverNotAvailableException(
                    "Driver is not AVAILABLE or heartbeat is stale.", details);
        }

        // N11 audit: reservation_reserved — chỉ ghi sau khi CAS thành công; MDC reservationId + tripId.
        audit("reservation_reserved", reservationId, cmd.tripId(),
                "driverId=" + cmd.driverId() + " attempt=" + cmd.attempt());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reservationId", reservationId.toString());
        body.put("state", "RESERVED");
        body.put("version", cmd.commandVersion());
        return new ReserveOutcome(201, body);
    }

    @Transactional
    public ReserveOutcome confirm(UUID id, ConfirmRequest cmd) {
        if (cmd == null || cmd.commandId() == null || cmd.tripId() == null || cmd.driverId() == null || cmd.attempt() < 1) {
            throw new InvalidInputException("Confirm requires commandId, tripId, driverId and a positive attempt.");
        }
        String bodyHash = CommandHash.sha256("attempt=" + cmd.attempt() + "|driverId=" + cmd.driverId() + "|tripId=" + cmd.tripId());
        return execute(id, cmd.commandId(), cmd.commandVersion(), "CONFIRM", bodyHash, () -> {
            var row = required(id);
            checkVersion(id, cmd.commandVersion(), row);
            if (!"RESERVED".equals(row.state()) || !Objects.equals(row.tripId(), cmd.tripId())
                    || !Objects.equals(row.driverId(), cmd.driverId()) || row.attempt() != cmd.attempt()) {
                throw conflict(row, "Confirm requires the matching, unexpired RESERVED offer.");
            }
            // A heartbeat may hold this row across the deadline. Check time after acquiring it.
            reservationRepo.lockDriver(row.driverId());
            if (!row.expiresAt().isAfter(Instant.now())) {
                throw conflict(row, "Offer expired while confirmation was waiting for the driver.");
            }
            if (reservationRepo.confirmDriver(row.driverId(), id, row.tripId()) != 1) {
                throw conflict(row, "Driver no longer points to this RESERVED assignment.");
            }
            reservationRepo.transition(id, "CONFIRMED", null, (int) cmd.commandVersion());
            audit("reservation_confirmed", id, row.tripId(), "driverId=" + row.driverId());
            return response(id, "CONFIRMED");
        });
    }

    @Transactional
    public ReserveOutcome release(UUID id, ReleaseRequest cmd) {
        if (cmd == null || cmd.commandId() == null || !("CANCELLED".equals(cmd.reason())
                || "REJECTED".equals(cmd.reason()) || "OFFER_EXPIRED".equals(cmd.reason()))) {
            throw new InvalidInputException("Release requires commandId and reason CANCELLED, REJECTED or OFFER_EXPIRED.");
        }
        return execute(id, cmd.commandId(), cmd.commandVersion(), "RELEASE", CommandHash.sha256("reason=" + cmd.reason()), () -> {
            var row = reservationRepo.findById(id).orElse(null);
            checkVersion(id, cmd.commandVersion(), row);
            if (row == null) {
                reservationRepo.insertTombstone(id, (int) cmd.commandVersion(), cmd.reason());
                audit("reservation_released", id, null, "reason=" + cmd.reason() + " (tombstone)");
                return response(id, "RELEASED");
            }
            if (terminal(row)) {
                // Do not rewrite terminal history or touch driver pointers on late commands.
                reservationRepo.transition(id, row.state(), row.terminalReason(), (int) cmd.commandVersion());
                audit("reservation_released", id, row.tripId(),
                        "reason=" + cmd.reason() + " (already terminal " + row.state() + ")");
                return response(id, row.state());
            }
            reservationRepo.releaseDriver(row);
            reservationRepo.transition(id, "RELEASED", cmd.reason(), (int) cmd.commandVersion());
            audit("reservation_released", id, row.tripId(), "reason=" + cmd.reason());
            return response(id, "RELEASED");
        });
    }

    @Transactional
    public ReserveOutcome finish(UUID id, FinishRequest cmd) {
        if (cmd == null || cmd.commandId() == null || cmd.tripId() == null) {
            throw new InvalidInputException("Finish requires commandId and tripId.");
        }
        return execute(id, cmd.commandId(), cmd.commandVersion(), "FINISH", CommandHash.sha256("tripId=" + cmd.tripId()), () -> {
            var row = required(id);
            checkVersion(id, cmd.commandVersion(), row);
            boolean alreadyFinished = "RELEASED".equals(row.state()) && "TRIP_COMPLETED".equals(row.terminalReason());
            if (!Objects.equals(row.tripId(), cmd.tripId()) || !("CONFIRMED".equals(row.state()) || alreadyFinished)) {
                throw conflict(row, "Finish requires a matching CONFIRMED trip or its completed terminal record.");
            }
            if (!alreadyFinished) reservationRepo.releaseDriver(row);
            reservationRepo.transition(id, "RELEASED", "TRIP_COMPLETED", (int) cmd.commandVersion());
            audit("reservation_finished", id, row.tripId(), "tripId=" + cmd.tripId());
            return response(id, "RELEASED");
        });
    }

    /** Scheduled recovery uses persisted state; external command versions are unchanged. */
    @Transactional
    public void expire(UUID id) {
        reservationRepo.lockReservation(id);
        var row = reservationRepo.findById(id).orElse(null);
        if (row != null && reservationRepo.expireReserved(id) == 1) {
            reservationRepo.releaseDriver(row);
            // N11: expiry worker log từng reservation đã expire.
            audit("reservation_expired", id, row.tripId(), "driverId=" + row.driverId());
        }
    }

    private ReserveOutcome execute(UUID reservationId, UUID commandId, long version, String operation,
                                   String bodyHash, Supplier<ReserveOutcome> work) {
        if (version < 1 || version > Integer.MAX_VALUE) {
            throw new InvalidInputException("commandVersion must be in [1, 2147483647].");
        }
        String hash = CommandHash.scoped(operation, reservationId, bodyHash);
        if (reservationRepo.claimProcessed(commandId, hash) == 0) {
            var processed = reservationRepo.findProcessed(commandId).orElseThrow();
            if (!hash.equals(processed.requestHash())) {
                throw new CommandConflictException("Command ID was already used for a different operation, resource or payload.");
            }
            return parseStoredResponse(processed.storedResponseJson());
        }
        reservationRepo.lockReservation(reservationId);
        ReserveOutcome outcome = work.get();
        reservationRepo.completeProcessed(commandId, "{\"status\":" + outcome.httpStatus()
                + ",\"body\":" + writeJson(outcome.body()) + "}");
        return outcome;
    }

    /** N11 — audit log nghiệp vụ: eventType riêng + MDC reservationId/tripId; không ghi secret. */
    private void audit(String eventType, UUID reservationId, UUID tripId, String detail) {
        MDC.put("reservationId", reservationId.toString());
        if (tripId != null) {
            MDC.put("tripId", tripId.toString());
        }
        MDC.put("eventType", eventType);
        MDC.put("result", "SUCCESS");
        log.info("{} {}", eventType, detail);
    }

    private ReservationRepository.ReservationRow required(UUID id) {
        return reservationRepo.findById(id).orElseThrow(() -> new NotFoundException("Reservation not found."));
    }

    private void checkVersion(UUID id, long version, ReservationRepository.ReservationRow row) {
        int stored = row == null ? 0 : row.lastCommandVersion();
        if (version <= stored) {
            throw new StaleVersionException("commandVersion must exceed last_command_version.",
                    Map.of("reservationId", id.toString(), "receivedCommandVersion", version, "storedLastCommandVersion", stored));
        }
    }

    private boolean terminal(ReservationRepository.ReservationRow row) {
        return "RELEASED".equals(row.state()) || "EXPIRED".equals(row.state());
    }

    private StateConflictException conflict(ReservationRepository.ReservationRow row, String message) {
        return new StateConflictException(message, Map.of("reservationId", row.id().toString(), "state", row.state()));
    }

    private ReserveOutcome response(UUID id, String state) {
        return new ReserveOutcome(200, Map.of("reservationId", id.toString(), "state", state));
    }

    /** GET /internal/reservations/{reservationId} — 404 nếu chưa từng biết reservationId (chốt §4.2). */
    public ReservationView get(UUID reservationId) {
        var row = reservationRepo.findById(reservationId)
                .orElseThrow(() -> new NotFoundException("Reservation not found."));
        return new ReservationView(row.id(), row.state(), row.tripId(), row.driverId(),
                row.attempt(), row.expiresAt(), row.lastCommandVersion());
    }

    private void validate(ReserveRequest cmd) {
        if (cmd == null || cmd.commandId() == null || cmd.tripId() == null || cmd.driverId() == null
                || cmd.attempt() < 1 || cmd.expiresAt() == null
                || cmd.pickup() == null || cmd.destination() == null) {
            throw new InvalidInputException("Reserve command is missing required fields.");
        }
        validateCoordinates(cmd.pickup().latitude(), cmd.pickup().longitude());
        validateCoordinates(cmd.destination().latitude(), cmd.destination().longitude());
        if (cmd.distanceM() < 0 || cmd.fareVnd() <= 0) {
            throw new InvalidInputException("distanceM must be >= 0 and fareVnd > 0.");
        }
    }

    private static void validateCoordinates(Double latitude, Double longitude) {
        if (latitude == null || !Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new InvalidInputException("latitude must be a finite number in [-90, 90].");
        }
        if (longitude == null || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new InvalidInputException("longitude must be a finite number in [-180, 180].");
        }
    }

    /**
     * T10 canonical body hash, retained for migration compatibility. execute() wraps it with
     * operation + reservation ID before storing/comparing processed_commands.request_hash.
     */
    static String requestHash(ReserveRequest cmd) {
        String canonical = "attempt=" + cmd.attempt()
                + "|distanceM=" + cmd.distanceM()
                + "|driverId=" + cmd.driverId()
                + "|expiresAt=" + cmd.expiresAt().toString()
                + "|fareVnd=" + cmd.fareVnd()
                + "|pickupLat=" + cmd.pickup().latitude()
                + "|pickupLng=" + cmd.pickup().longitude()
                + "|destLat=" + cmd.destination().latitude()
                + "|destLng=" + cmd.destination().longitude()
                + "|tripId=" + cmd.tripId();
        return CommandHash.sha256(canonical);
    }

    private ReserveOutcome parseStoredResponse(String storedJson) {
        try {
            Map<String, Object> envelope = json.readValue(
                    storedJson.getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
            int status = ((Number) envelope.get("status")).intValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) envelope.get("body");
            return new ReserveOutcome(status, body);
        } catch (Exception e) {
            throw new IllegalStateException("Stored response for replay is corrupted.", e);
        }
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize stored response.", e);
        }
    }
}
