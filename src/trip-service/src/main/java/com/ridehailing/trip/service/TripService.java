package com.ridehailing.trip.service;

import com.ridehailing.trip.exception.ActiveTripExistsException;
import com.ridehailing.trip.exception.IdempotencyMismatchException;
import com.ridehailing.trip.exception.InvalidInputException;
import com.ridehailing.trip.exception.TripNotFoundException;
import com.ridehailing.trip.geo.Haversine;
import com.ridehailing.trip.repo.IdempotencyKeyRepository;
import com.ridehailing.trip.repo.OutboxRepository;
import com.ridehailing.trip.repo.SagaRepository;
import com.ridehailing.trip.repo.TripEventRepository;
import com.ridehailing.trip.repo.TripRepository;
import com.ridehailing.trip.repo.TripQuoteRepository;
import com.ridehailing.trip.security.AuthenticatedUser;
import com.ridehailing.trip.security.CorrelationIdFilter;
import com.ridehailing.trip.security.Role;
import com.ridehailing.trip.web.dto.QuoteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ridehailing.trip.logging.AuditLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T12 — quote + tạo trip (SEARCHING) + list/get theo contract api-contracts.md §3.3,
 * state-machines.md §1 và events.md §3.1/§4.6.
 *
 * Chốt T12:
 * - Fare tính phía server: distanceM = round(haversine); fareVnd = FARE_BASE_VND + ceil(distanceM/1000) * FARE_PER_KM_VND.
 * - Idempotency POST /api/trips: header UUID bắt buộc; claim key bằng ON CONFLICT DO NOTHING trong transaction;
 *   cùng key + cùng payload -> replay response gốc đã lưu (202); cùng key + payload khác -> 409 IDEMPOTENCY_MISMATCH.
 * - Side effects tạo trip (cùng transaction): outbox PENDING TripRequested attempt 1 (publisher worker = T13),
 *   trip_event TRIP_CREATED version 1; saga WAITING_MATCH deadline = createdAt + TRIP_MATCH_DEADLINE_SECONDS
 *   (T16, state-machines.md §1/§2). Notification passenger = T25 — chưa ghi ở đây.
 */
@Service
public class TripService {

    private static final String CREATE_TRIP_OPERATION = "CREATE_TRIP";

    /** N11 — audit log: trip_created; không ghi secret. */
    private static final Logger log = LoggerFactory.getLogger(TripService.class);

    /** Kết quả create: status HTTP + body response (+ Location path cho header). */
    public record CreateOutcome(int httpStatus, Map<String, Object> body) {
    }

    private final TripRepository tripRepo;
    private final OutboxRepository outboxRepo;
    private final SagaRepository sagaRepo;
    private final TripEventRepository eventRepo;
    private final IdempotencyKeyRepository idempotencyRepo;
    private final TripQuoteRepository quoteRepo;
    private final ObjectMapper json;
    private final long fareBaseVnd;
    private final long farePerKmVnd;
    private final long tripMatchDeadlineSeconds;

    @Autowired
    public TripService(TripRepository tripRepo, OutboxRepository outboxRepo, SagaRepository sagaRepo,
                       TripEventRepository eventRepo, IdempotencyKeyRepository idempotencyRepo, ObjectMapper json,
                       TripQuoteRepository quoteRepo,
                       @Value("${fare.base-vnd:12000}") long fareBaseVnd,
                       @Value("${fare.per-km-vnd:5000}") long farePerKmVnd,
                       @Value("${app.trip.trip-match-deadline-seconds:120}") long tripMatchDeadlineSeconds) {
        this.tripRepo = tripRepo;
        this.outboxRepo = outboxRepo;
        this.sagaRepo = sagaRepo;
        this.eventRepo = eventRepo;
        this.idempotencyRepo = idempotencyRepo;
        this.quoteRepo = quoteRepo;
        this.json = json;
        this.fareBaseVnd = fareBaseVnd;
        this.farePerKmVnd = farePerKmVnd;
        this.tripMatchDeadlineSeconds = Math.max(1, tripMatchDeadlineSeconds);
    }

    /** Compatibility constructor for the legacy-migration replay test. */
    public TripService(TripRepository tripRepo, OutboxRepository outboxRepo, SagaRepository sagaRepo,
                       TripEventRepository eventRepo, IdempotencyKeyRepository idempotencyRepo, ObjectMapper json,
                       long fareBaseVnd, long farePerKmVnd, long tripMatchDeadlineSeconds) {
        this(tripRepo, outboxRepo, sagaRepo, eventRepo, idempotencyRepo, json, null,
                fareBaseVnd, farePerKmVnd, tripMatchDeadlineSeconds);
    }

    /** POST /api/trips/quote — không tạo trip, không side effect (contract §3.3). */
    public Map<String, Object> quote(QuoteRequest req) {
        validate(req);
        long distanceM = Haversine.meters(req.pickup().latitude(), req.pickup().longitude(),
                req.destination().latitude(), req.destination().longitude());
        return fare(distanceM);
    }

    /** POST /api/trips — tạo trip SEARCHING + ghi ý định matching (outbox pending) cùng transaction. */
    @Transactional
    public CreateOutcome create(AuthenticatedUser user, String idempotencyKeyHeader, QuoteRequest req,
                                UUID correlationId) {
        validate(req);
        long distanceM = Haversine.meters(req.pickup().latitude(), req.pickup().longitude(),
                req.destination().latitude(), req.destination().longitude());
        long fareVnd = ((Number) fare(distanceM).get("fareVnd")).longValue();
        ResolvedTrip resolved = new ResolvedTrip(req.pickup().latitude(), req.pickup().longitude(),
                req.destination().latitude(), req.destination().longitude(),
                req.pickup().latitude() + ", " + req.pickup().longitude(),
                req.destination().latitude() + ", " + req.destination().longitude(),
                distanceM, 0, "{\"type\":\"LineString\",\"coordinates\":[]}", fareVnd, null);
        return createResolved(user, idempotencyKeyHeader, requestHash(req), resolved, correlationId);
    }

    @Transactional
    public CreateOutcome createFromQuote(AuthenticatedUser user, String idempotencyKeyHeader, UUID quoteToken,
                                         UUID correlationId) {
        if (quoteToken == null) throw new InvalidInputException("quoteToken is required.");
        validateIdempotencyKey(idempotencyKeyHeader);
        UUID key = UUID.fromString(idempotencyKeyHeader.trim());
        String hash = requestHash(quoteToken);
        var existing = idempotencyRepo.findByKey(user.userId(), CREATE_TRIP_OPERATION, key);
        if (existing.isPresent()) return replayOrMismatch(existing.get(), hash, key);
        if (quoteRepo == null) throw new InvalidInputException("Route quotes are unavailable.");
        var quote = quoteRepo.findValidForUpdate(quoteToken, user.userId(), Instant.now())
                .orElseGet(() -> {
                    var raced = idempotencyRepo.findByKey(user.userId(), CREATE_TRIP_OPERATION, key);
                    if (raced.isPresent()) return null;
                    throw new InvalidInputException("Quote is invalid or expired; calculate the route again.");
                });
        if (quote == null) return replayOrMismatch(idempotencyRepo.findByKey(user.userId(), CREATE_TRIP_OPERATION, key)
                .orElseThrow(), hash, key);
        ResolvedTrip resolved = new ResolvedTrip(quote.pickup().latitude(), quote.pickup().longitude(),
                quote.destination().latitude(), quote.destination().longitude(), quote.pickup().displayName(),
                quote.destination().displayName(), quote.distanceM(), quote.durationS(), quote.routeGeometry(),
                quote.fareVnd(), quoteToken);
        return createResolved(user, idempotencyKeyHeader, hash, resolved, correlationId);
    }

    private record ResolvedTrip(double pickupLat, double pickupLng, double destinationLat, double destinationLng,
                                String pickupAddress, String destinationAddress, long distanceM, long durationS,
                                String routeGeometry, long fareVnd, UUID quoteToken) {}

    private CreateOutcome createResolved(AuthenticatedUser user, String idempotencyKeyHeader, String hash,
                                         ResolvedTrip resolved, UUID correlationId) {
        validateIdempotencyKey(idempotencyKeyHeader);
        if (!CorrelationIdFilter.isValidUuid(idempotencyKeyHeader)) {
            throw new InvalidInputException("Idempotency-Key header is required and must be a valid UUID.");
        }
        UUID key = UUID.fromString(idempotencyKeyHeader.trim());
        // T12-1 (F14): MỘT timestamp duy nhất, truncate về giây để round-trip qua DB
        // (TIMESTAMPTZ microsecond) giữ nguyên chuỗi ISO — response, GET, replay và
        // outbox.occurred_at đều dùng đúng giá trị này.
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        UUID tripId = UUID.randomUUID();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tripId", tripId.toString());
        body.put("state", "SEARCHING");
        body.put("matchingAttempt", 1);
        body.put("createdAt", createdAt);
        String storedJson = "{\"status\":202,\"body\":" + writeJson(body) + "}";

        int claimed = idempotencyRepo.claim(user.userId(), CREATE_TRIP_OPERATION, key, hash, storedJson);
        if (claimed == 0) {
            var stored = idempotencyRepo.findByKey(user.userId(), CREATE_TRIP_OPERATION, key).orElseThrow(
                    () -> new IllegalStateException("Idempotency key row disappeared after claim."));
            if (stored.requestHash().equals(hash)) {
                // Replay response gốc đã lưu — không tạo trip mới.
                return parseStoredResponse(stored);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("idempotencyKey", key.toString());
            throw new IdempotencyMismatchException(
                    "Idempotency-Key was already used with a different payload.", details);
        }

        // T12-6 (N28): tối đa MỘT trip chưa terminal per passenger. Advisory xact lock serialize các
        // create đồng thời của cùng passenger; check chạy sau khi mọi create trước đó commit/rollback.
        // Replay key cũ (claimed == 0) không đi qua nhánh này — vẫn trả 202 gốc.
        tripRepo.acquirePassengerAdvisoryLock(user.userId());
        if (tripRepo.countNonTerminalForPassenger(user.userId()) > 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("passengerId", user.userId().toString());
            throw new ActiveTripExistsException(
                    "Passenger already has an active trip; cancel or complete it before creating another.",
                    details);
        }

        // T12-1 (F14): MỘT timestamp duy nhất — ghi tường minh vào trips.created_at và dùng
        // cho response + replay stored + outbox.occurred_at.
        tripRepo.insert(tripId, user.userId(), resolved.pickupLat(), resolved.pickupLng(),
                resolved.destinationLat(), resolved.destinationLng(), resolved.pickupAddress(),
                resolved.destinationAddress(), resolved.distanceM(), resolved.durationS(),
                resolved.routeGeometry(), resolved.fareVnd(), createdAt);
        if (resolved.quoteToken() != null && quoteRepo.markUsed(resolved.quoteToken(), tripId, createdAt) != 1) {
            throw new InvalidInputException("Quote was already used; calculate the route again.");
        }

        // Ý định yêu cầu matching: outbox PENDING TripRequested attempt 1 (events.md §3.1).
        // T12-2 (N33): aggregate_version = trips.version lúc ghi (= 1), routing_key = trip.requested.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tripId", tripId.toString());
        payload.put("attempt", 1);
        payload.put("pickup", pointJson(resolved.pickupLat(), resolved.pickupLng(), resolved.pickupAddress()));
        payload.put("destination", pointJson(resolved.destinationLat(), resolved.destinationLng(), resolved.destinationAddress()));
        payload.put("distanceM", resolved.distanceM());
        payload.put("routeDurationS", resolved.durationS());
        payload.put("routeGeometry", readJson(resolved.routeGeometry()));
        payload.put("fareVnd", resolved.fareVnd());
        payload.put("excludedDriverIds", List.of());
        outboxRepo.insertPending(UUID.randomUUID(), "TripRequested", tripId, writeJson(payload), correlationId,
                createdAt, 1, "trip.requested");

        // T16 — Saga WAITING_MATCH (state-machines.md §1/§2): một saga per trip; deadline = createdAt +
        // TRIP_MATCH_DEADLINE_SECONDS; context khởi tạo excludedDriverIds rỗng (T19+ cập nhật).
        sagaRepo.insert(tripId, createdAt.plusSeconds(tripMatchDeadlineSeconds), "{\"excludedDriverIds\":[]}");

        // Trip event history (contract §3.5): transition đầu – -> SEARCHING.
        eventRepo.insert(tripId, "TRIP_CREATED", 1, user.userId(), "Trip created in SEARCHING");

        // N11 audit: trip_created — MDC tripId; chỉ userId, không email/password.
        // L05: ghi sau commit, MDC tạm thời.
        AuditLog.info(log, AuditLog.mdc("tripId", tripId.toString(), "eventType", "trip_created", "result", "SUCCESS"),
                "trip created state=SEARCHING matchingAttempt=1 passengerUserId={}", user.userId());

        return new CreateOutcome(202, body);
    }

    private void validateIdempotencyKey(String header) {
        if (!CorrelationIdFilter.isValidUuid(header))
            throw new InvalidInputException("Idempotency-Key header is required and must be a valid UUID.");
    }

    private CreateOutcome replayOrMismatch(IdempotencyKeyRepository.Stored stored, String hash, UUID key) {
        if (stored.requestHash().equals(hash)) return parseStoredResponse(stored);
        throw new IdempotencyMismatchException("Idempotency-Key was already used with a different payload.",
                Map.of("idempotencyKey", key.toString()));
    }

    /** GET /api/trips — phân trang §1.3; passenger thấy chuyến của mình, driver thấy chuyến đã assign.
     * T12-5 (R02): REPEATABLE_READ để count và content đọc cùng một snapshot DB. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(AuthenticatedUser user, int page, int size) {
        if (page < 0 || size < 1) {
            throw new InvalidInputException("page must be >= 0 and size must be >= 1.");
        }
        int effectiveSize = Math.min(size, 100); // max 100 — lớn hơn bị clamp (§1.3)
        UUID passengerId = user.role() == Role.PASSENGER ? user.userId() : null;
        UUID driverUserId = user.role() == Role.DRIVER ? user.userId() : null;
        long total = tripRepo.countForUser(passengerId, driverUserId);
        List<Map<String, Object>> content = tripRepo.listForUser(passengerId, driverUserId,
                (long) page * effectiveSize, effectiveSize).stream().map(this::listItem).toList();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("content", content);
        envelope.put("page", page);
        envelope.put("size", effectiveSize);
        envelope.put("totalElements", total);
        envelope.put("totalPages", (int) ((total + effectiveSize - 1) / effectiveSize));
        return envelope;
    }

    /** GET /api/trips/{id} — owner hoặc assigned driver, không thì 404 (chính sách §1.2). */
    public Map<String, Object> get(UUID tripId, AuthenticatedUser user) {
        TripRepository.TripRow row = tripRepo.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException("Trip not found."));
        boolean owner = user.role() == Role.PASSENGER && row.passengerId().equals(user.userId());
        boolean assignedDriver = user.role() == Role.DRIVER && row.driverUserId() != null
                && row.driverUserId().equals(user.userId());
        if (!owner && !assignedDriver) {
            throw new TripNotFoundException("Trip not found."); // không lộ sự tồn tại trip của người khác
        }
        return detail(row);
    }

    /** T25 — GET /api/trips/{id}/events (contract §3.3): owner hoặc assigned driver, phân trang,
     *  sắp version DESC; envelope như {@link #list}. REPEATABLE_READ như {@link #list} (T12-5): ownership,
     *  count và content đọc cùng một snapshot — transition chen giữa không làm lệch totalElements. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> events(UUID tripId, AuthenticatedUser user, int page, int size) {
        if (page < 0 || size < 1) {
            throw new InvalidInputException("page must be >= 0 and size must be >= 1.");
        }
        int effectiveSize = Math.min(size, 100); // max 100 — lớn hơn bị clamp (§1.3)
        TripRepository.TripRow row = tripRepo.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException("Trip not found."));
        boolean owner = user.role() == Role.PASSENGER && row.passengerId().equals(user.userId());
        boolean assignedDriver = user.role() == Role.DRIVER && row.driverUserId() != null
                && row.driverUserId().equals(user.userId());
        if (!owner && !assignedDriver) {
            throw new TripNotFoundException("Trip not found."); // không lộ sự tồn tại trip của người khác (§1.2)
        }
        long total = eventRepo.countByTrip(tripId);
        List<Map<String, Object>> content = eventRepo.listForTrip(tripId, (long) page * effectiveSize, effectiveSize).stream()
                .map(TripService::eventItem).toList();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("content", content);
        envelope.put("page", page);
        envelope.put("size", effectiveSize);
        envelope.put("totalElements", total);
        envelope.put("totalPages", (int) ((total + effectiveSize - 1) / effectiveSize));
        return envelope;
    }

    private static Map<String, Object> eventItem(TripEventRepository.EventRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", row.type());
        m.put("version", row.version());
        m.put("actorId", row.actorId() == null ? null : row.actorId().toString());
        m.put("summary", row.summary());
        m.put("createdAt", row.createdAt().truncatedTo(ChronoUnit.SECONDS));
        return m;
    }

    private Map<String, Object> fare(long distanceM) {
        long km = (distanceM + 999) / 1000; // ceil(distanceM/1000), integer math
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("distanceM", distanceM);
        out.put("fareVnd", fareBaseVnd + km * farePerKmVnd);
        return out;
    }

    private void validate(QuoteRequest req) {
        if (req == null || req.pickup() == null || req.destination() == null) {
            throw new InvalidInputException("pickup and destination are required.");
        }
        validateCoordinates(req.pickup().latitude(), req.pickup().longitude());
        validateCoordinates(req.destination().latitude(), req.destination().longitude());
    }

    private static void validateCoordinates(Double latitude, Double longitude) {
        if (latitude == null || !Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new InvalidInputException("latitude must be a finite number in [-90, 90].");
        }
        if (longitude == null || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new InvalidInputException("longitude must be a finite number in [-180, 180].");
        }
    }

    /** request_hash (chốt T12): SHA-256 hex của payload nghiệp vụ canonical — chỉ tọa độ pickup/destination.
     * T12-4 (R01): canonical double — -0.0 chuẩn hóa thành 0.0 trước khi băm (contract §3.3). */
    static String requestHash(QuoteRequest req) {
        String canonical = "destLat=" + canonicalDouble(req.destination().latitude())
                + "|destLng=" + canonicalDouble(req.destination().longitude())
                + "|pickupLat=" + canonicalDouble(req.pickup().latitude())
                + "|pickupLng=" + canonicalDouble(req.pickup().longitude());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable.", e);
        }
    }

    static String requestHash(UUID quoteToken) {
        String canonical = "quoteToken=" + quoteToken;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable.", e);
        }
    }

    /** Canonical double cho hash (T10-5/T12-4): -0.0 -> 0.0; các giá trị khác giữ Double.toString. */
    static String canonicalDouble(double value) {
        return value == 0.0 ? "0.0" : Double.toString(value);
    }

    private CreateOutcome parseStoredResponse(IdempotencyKeyRepository.Stored stored) {
        try {
            // stored_response = {"status":<int>,"body":{...}} — replay đúng body gốc đã lưu.
            var envelope = json.readTree(stored.bodyJson());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) json.convertValue(envelope.path("body"), LinkedHashMap.class);
            return new CreateOutcome(stored.status(), body);
        } catch (Exception e) {
            throw new IllegalStateException("Stored idempotency response is malformed.", e);
        }
    }

    private Map<String, Object> listItem(TripRepository.TripRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tripId", row.id().toString());
        m.put("state", row.state());
        m.put("pickup", pointJson(row.pickupLat(), row.pickupLng(), row.pickupAddress()));
        m.put("destination", pointJson(row.destinationLat(), row.destinationLng(), row.destinationAddress()));
        m.put("distanceM", row.distanceM());
        m.put("routeDurationS", row.routeDurationS());
        m.put("routeGeometry", readJson(row.routeGeometry()));
        m.put("fareVnd", row.fareVnd());
        m.put("paymentStatus", row.paymentStatus());
        m.put("createdAt", row.createdAt().truncatedTo(ChronoUnit.SECONDS));
        return m;
    }

    private Map<String, Object> detail(TripRepository.TripRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tripId", row.id().toString());
        m.put("state", row.state());
        m.put("pickup", pointJson(row.pickupLat(), row.pickupLng(), row.pickupAddress()));
        m.put("destination", pointJson(row.destinationLat(), row.destinationLng(), row.destinationAddress()));
        m.put("distanceM", row.distanceM());
        m.put("routeDurationS", row.routeDurationS());
        m.put("routeGeometry", readJson(row.routeGeometry()));
        m.put("fareVnd", row.fareVnd());
        m.put("paymentStatus", row.paymentStatus());
        m.put("driverId", row.driverId() == null ? null : row.driverId().toString());
        m.put("matchingAttempt", row.matchingAttempt());
        m.put("createdAt", row.createdAt().truncatedTo(ChronoUnit.SECONDS));
        m.put("updatedAt", row.updatedAt().truncatedTo(ChronoUnit.SECONDS));
        return m;
    }

    private Map<String, Object> pointJson(double lat, double lng) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("latitude", lat);
        p.put("longitude", lng);
        return p;
    }

    private Map<String, Object> pointJson(double lat, double lng, String address) {
        Map<String, Object> p = pointJson(lat, lng);
        p.put("address", address);
        return p;
    }

    private Map<String, Object> pointJson(QuoteRequest.Point p) {
        return pointJson(p.latitude(), p.longitude());
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize JSON.", e);
        }
    }

    private Object readJson(String value) {
        try { return json.readTree(value); }
        catch (Exception e) { throw new IllegalStateException("Stored route geometry is malformed.", e); }
    }
}
