package com.ridehailing.trip.event;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import java.math.BigDecimal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T16/T24 — validation event khi consume (events.md §2):
 * - thiếu field bắt buộc / sai kiểu → {@code malformed_envelope};
 * - eventType consumer không xử lý → {@code unsupported_event_type}
 *   (vd TripRequested — trip-service không consume event này);
 * - schemaVersion khác 1 → {@code unsupported_schema_version}.
 * Event malformed KHÔNG được retry như lỗi mạng (§4) — republish thẳng vào DLQ kèm reason.
 */
@Component
public class InboxMessageValidator {

    public static final String REASON_MALFORMED = "malformed_envelope";
    public static final String REASON_UNSUPPORTED_TYPE = "unsupported_event_type";
    public static final String REASON_UNSUPPORTED_SCHEMA = "unsupported_schema_version";

    /** Event trip-service xử lý (events.md §1: trip.inbox ← matching.#, payment.#):
     *  T16 — DriverProposed/NoDriverFound; T24 — PaymentSucceeded/PaymentFailed. */
    private static final Set<String> HANDLED_EVENT_TYPES =
            Set.of("DriverProposed", "NoDriverFound", "PaymentSucceeded", "PaymentFailed");

    /** events.md §3.2: max MATCHING_MAX_CANDIDATES=5 candidate trong một proposal. */
    private static final int MAX_CANDIDATES = 5;

    private final ObjectMapper json;

    public InboxMessageValidator(ObjectMapper json) {
        this.json = json;
    }

    /** Kết quả validate: ok + envelope/payload đã parse, hoặc reason để republish DLQ. */
    public record ValidationResult(boolean ok, EventEnvelope envelope, DriverProposedPayload driverProposed,
                                  NoDriverFoundPayload noDriverFound, PaymentSucceededPayload paymentSucceeded,
                                  PaymentFailedPayload paymentFailed, String reason) {
        static ValidationResult ok(EventEnvelope envelope, DriverProposedPayload payload) {
            return new ValidationResult(true, envelope, payload, null, null, null, null);
        }

        static ValidationResult ok(EventEnvelope envelope, NoDriverFoundPayload payload) {
            return new ValidationResult(true, envelope, null, payload, null, null, null);
        }

        static ValidationResult ok(EventEnvelope envelope, PaymentSucceededPayload payload) {
            return new ValidationResult(true, envelope, null, null, payload, null, null);
        }

        static ValidationResult ok(EventEnvelope envelope, PaymentFailedPayload payload) {
            return new ValidationResult(true, envelope, null, null, null, payload, null);
        }

        static ValidationResult fail(String reason) {
            return new ValidationResult(false, null, null, null, null, null, reason);
        }
    }

    /** Payload DriverProposed theo events.md §3.2: danh sách candidate có thứ tự (max 5). */
    public record DriverProposedPayload(UUID tripId, int attempt, List<Candidate> candidates) {
    }

    /** Candidate theo events.md §3.2 chốt R02: driverId = profile, driverUserId = JWT sub. */
    public record Candidate(UUID driverId, UUID driverUserId, long distanceM) {
    }

    /** Payload NoDriverFound theo events.md §3.3; reason enum duy nhất NO_CANDIDATES. */
    public record NoDriverFoundPayload(UUID tripId, int attempt, String reason) {
        public static final String REASON_NO_CANDIDATES = "NO_CANDIDATES";
    }

    /** Payload PaymentSucceeded theo events.md §3.5 (T24). */
    public record PaymentSucceededPayload(UUID tripId, UUID paymentId, int attempt, long amountVnd) {
    }

    /** Payload PaymentFailed theo events.md §3.6 (T24); reason = mã kỹ thuật/giả lập (vd SIMULATED_DECLINE). */
    public record PaymentFailedPayload(UUID tripId, UUID paymentId, int attempt, String reason) {
    }

    public ValidationResult validate(byte[] body) {
        Map<String, Object> root;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readerFor(Map.class)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(body);
            root = parsed;
        } catch (Exception e) {
            return ValidationResult.fail(REASON_MALFORMED);
        }

        if (root == null) {
            return ValidationResult.fail(REASON_MALFORMED);
        }
        String eventIdStr = asString(root.get("eventId"));
        UUID eventId = parseUuid(eventIdStr);
        if (eventId == null) {
            return ValidationResult.fail(REASON_MALFORMED);
        }

        String eventType = asString(root.get("eventType"));
        if (eventType == null || eventType.isBlank()) {
            return ValidationResult.fail(REASON_MALFORMED);
        }
        // Kiểm tra TRƯỚC schema: event hợp lệ nhưng consumer không xử lý là lỗi routing,
        // không phải envelope hỏng (§2).
        if (!HANDLED_EVENT_TYPES.contains(eventType)) {
            return ValidationResult.fail(REASON_UNSUPPORTED_TYPE);
        }

        Number schemaVersion = asNumber(root.get("schemaVersion"));
        if (schemaVersion == null || !isWhole(schemaVersion)) {
            return ValidationResult.fail(REASON_MALFORMED);
        }
        if (!inRange(schemaVersion, 1, 1)) {
            return ValidationResult.fail(REASON_UNSUPPORTED_SCHEMA);
        }

        String occurredAtStr = asString(root.get("occurredAt"));
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(occurredAtStr); // ISO-8601 UTC (§2)
        } catch (Exception e) {
            return ValidationResult.fail(REASON_MALFORMED);
        }

        String producer = asString(root.get("producer"));
        if (producer == null || producer.isBlank()) {
            return ValidationResult.fail(REASON_MALFORMED);
        }

        UUID correlationId = parseUuid(asString(root.get("correlationId")));
        if (correlationId == null) {
            return ValidationResult.fail(REASON_MALFORMED);
        }

        // causationId optional — nếu có mặt phải là UUID hợp lệ (§2).
        Object causationRaw = root.get("causationId");
        UUID causationId = null;
        if (causationRaw != null) {
            causationId = parseUuid(asString(causationRaw));
            if (causationId == null) {
                return ValidationResult.fail(REASON_MALFORMED);
            }
        }

        UUID aggregateId = parseUuid(asString(root.get("aggregateId")));
        if (aggregateId == null) {
            return ValidationResult.fail(REASON_MALFORMED);
        }

        // aggregateVersion optional — nếu có mặt phải là số nguyên không âm (§2).
        Object versionRaw = root.get("aggregateVersion");
        Integer aggregateVersion = null;
        if (versionRaw != null) {
            Number v = asNumber(versionRaw);
            if (!inRange(v, 0, Integer.MAX_VALUE)) {
                return ValidationResult.fail(REASON_MALFORMED);
            }
            aggregateVersion = v.intValue();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = root.get("payload") instanceof Map<?, ?> p ? (Map<String, Object>) p : null;
        if (payload == null) {
            return ValidationResult.fail(REASON_MALFORMED);
        }

        EventEnvelope envelope = new EventEnvelope(eventId, eventType, schemaVersion.intValue(), occurredAt,
                producer, correlationId, causationId, aggregateId, aggregateVersion, payload);
        switch (eventType) {
            case "DriverProposed" -> {
                DriverProposedPayload dp = validateDriverProposed(payload);
                return dp == null ? ValidationResult.fail(REASON_MALFORMED) : ValidationResult.ok(envelope, dp);
            }
            case "NoDriverFound" -> {
                NoDriverFoundPayload ndf = validateNoDriverFound(payload);
                return ndf == null ? ValidationResult.fail(REASON_MALFORMED) : ValidationResult.ok(envelope, ndf);
            }
            case "PaymentSucceeded" -> {
                PaymentSucceededPayload ps = validatePaymentSucceeded(payload, aggregateId);
                return ps == null ? ValidationResult.fail(REASON_MALFORMED) : ValidationResult.ok(envelope, ps);
            }
            case "PaymentFailed" -> {
                PaymentFailedPayload pf = validatePaymentFailed(payload, aggregateId);
                return pf == null ? ValidationResult.fail(REASON_MALFORMED) : ValidationResult.ok(envelope, pf);
            }
            default -> {
                // Không tới được đây (HANDLED_EVENT_TYPES đã chặn trước).
                return ValidationResult.fail(REASON_UNSUPPORTED_TYPE);
            }
        }
    }

    /** Payload DriverProposed theo events.md §3.2; trả null nếu sai schema. */
    private DriverProposedPayload validateDriverProposed(Map<String, Object> payload) {
        UUID tripId = parseUuid(asString(payload.get("tripId")));
        if (tripId == null) {
            return null;
        }
        Number attemptRaw = asNumber(payload.get("attempt"));
        if (!inRange(attemptRaw, 1, Integer.MAX_VALUE)) {
            return null;
        }
        Object candidatesRaw = payload.get("candidates");
        if (!(candidatesRaw instanceof List<?> list)) {
            return null;
        }
        // Matching chỉ phát DriverProposed khi danh sách khác rỗng (§3.2); max 5 (MATCHING_MAX_CANDIDATES).
        if (list.isEmpty() || list.size() > MAX_CANDIDATES) {
            return null;
        }
        List<Candidate> candidates = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                return null;
            }
            UUID driverId = parseUuid(asString(m.get("driverId")));
            UUID driverUserId = parseUuid(asString(m.get("driverUserId")));
            Number distanceRaw = asNumber(m.get("distanceM"));
            if (driverId == null || driverUserId == null || distanceRaw == null || !isWhole(distanceRaw)
                    || distanceRaw.longValue() < 0) {
                return null;
            }
            candidates.add(new Candidate(driverId, driverUserId, distanceRaw.longValue()));
        }
        return new DriverProposedPayload(tripId, attemptRaw.intValue(), List.copyOf(candidates));
    }

    /** Payload NoDriverFound theo events.md §3.3; trả null nếu sai schema. */
    private NoDriverFoundPayload validateNoDriverFound(Map<String, Object> payload) {
        UUID tripId = parseUuid(asString(payload.get("tripId")));
        if (tripId == null) {
            return null;
        }
        Number attemptRaw = asNumber(payload.get("attempt"));
        if (!inRange(attemptRaw, 1, Integer.MAX_VALUE)) {
            return null;
        }
        String reason = asString(payload.get("reason"));
        // §3.3: reason enum chốt — matching chỉ phát NO_CANDIDATES.
        if (!NoDriverFoundPayload.REASON_NO_CANDIDATES.equals(reason)) {
            return null;
        }
        return new NoDriverFoundPayload(tripId, attemptRaw.intValue(), reason);
    }

    /** Payload PaymentSucceeded theo events.md §3.5; trả null nếu sai schema.
     *  Chốt T24: {@code tripId} phải khớp {@code aggregateId} (event về đúng trip); amountVnd nguyên >0 vừa long. */
    private PaymentSucceededPayload validatePaymentSucceeded(Map<String, Object> payload, UUID aggregateId) {
        UUID tripId = parseUuid(asString(payload.get("tripId")));
        if (tripId == null || !tripId.equals(aggregateId)) {
            return null;
        }
        UUID paymentId = parseUuid(asString(payload.get("paymentId")));
        if (paymentId == null) {
            return null;
        }
        Number attemptRaw = asNumber(payload.get("attempt"));
        if (!inRange(attemptRaw, 1, Integer.MAX_VALUE)) {
            return null;
        }
        Number amountRaw = asNumber(payload.get("amountVnd"));
        if (amountRaw == null || !isWhole(amountRaw)) {
            return null;
        }
        // Check the exact value against BIGINT bounds before narrowing to long.
        if (!inRange(amountRaw, 1, Long.MAX_VALUE)) {
            return null;
        }
        return new PaymentSucceededPayload(tripId, paymentId, attemptRaw.intValue(), amountRaw.longValue());
    }

    /** Payload PaymentFailed theo events.md §3.6; trả null nếu sai schema.
     *  Chốt T24: {@code tripId} phải khớp {@code aggregateId}; reason là mã kỹ thuật/giả lập tự do (không enum cố định). */
    private PaymentFailedPayload validatePaymentFailed(Map<String, Object> payload, UUID aggregateId) {
        UUID tripId = parseUuid(asString(payload.get("tripId")));
        if (tripId == null || !tripId.equals(aggregateId)) {
            return null;
        }
        UUID paymentId = parseUuid(asString(payload.get("paymentId")));
        if (paymentId == null) {
            return null;
        }
        Number attemptRaw = asNumber(payload.get("attempt"));
        if (!inRange(attemptRaw, 1, Integer.MAX_VALUE)) {
            return null;
        }
        String reason = asString(payload.get("reason"));
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return new PaymentFailedPayload(tripId, paymentId, attemptRaw.intValue(), reason);
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    private static Number asNumber(Object o) {
        return o instanceof Number n ? n : null;
    }

    /** Số nguyên (không fraction), không NaN/Infinity. Kiểm tra bằng scale thay vì
     *  {@code toBigIntegerExact()} — số mũ lớn như {@code 1e999999999} không bị dựng thành BigInteger khổng lồ. */
    private static boolean isWhole(Number n) {
        BigDecimal value = exact(n);
        return value != null && (value.signum() == 0 || value.stripTrailingZeros().scale() <= 0);
    }

    /** Check the exact JSON number before narrowing it to an int/long. */
    private static boolean inRange(Number n, long min, long max) {
        if (!isWhole(n)) {
            return false;
        }
        BigDecimal value = exact(n);
        return value.compareTo(BigDecimal.valueOf(min)) >= 0
                && value.compareTo(BigDecimal.valueOf(max)) <= 0;
    }

    private static BigDecimal exact(Number n) {
        if (n == null) {
            return null;
        }
        try {
            return new BigDecimal(n.toString());
        } catch (NumberFormatException e) {
            return null; // NaN/Infinity
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
