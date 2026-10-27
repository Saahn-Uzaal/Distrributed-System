package com.ridehailing.payment.event;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import java.math.BigDecimal;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T23 — validation event khi consume (events.md §2):
 * - thiếu field bắt buộc / sai kiểu → {@code malformed_envelope};
 * - eventType consumer không xử lý → {@code unsupported_event_type};
 * - schemaVersion khác 1 → {@code unsupported_schema_version}.
 * Event malformed KHÔNG được retry như lỗi mạng (§4) — republish thẳng vào DLQ kèm reason.
 */
@Component
public class InboxMessageValidator {

    public static final String REASON_MALFORMED = "malformed_envelope";
    public static final String REASON_UNSUPPORTED_TYPE = "unsupported_event_type";
    public static final String REASON_UNSUPPORTED_SCHEMA = "unsupported_schema_version";

    /** Event duy nhất payment-service xử lý (events.md §1: payment.inbox ← trip.completed). */
    private static final Set<String> HANDLED_EVENT_TYPES = Set.of("TripCompleted");

    /** Currency MVP — events.md §3.4: luôn "VND". */
    public static final String CURRENCY_VND = "VND";

    private final ObjectMapper json;

    public InboxMessageValidator(ObjectMapper json) {
        this.json = json;
    }

    /** Kết quả validate: ok + envelope/payload đã parse, hoặc reason để republish DLQ. */
    public record ValidationResult(boolean ok, EventEnvelope envelope, TripCompletedPayload payload, String reason) {
        static ValidationResult ok(EventEnvelope envelope, TripCompletedPayload payload) {
            return new ValidationResult(true, envelope, payload, null);
        }

        static ValidationResult fail(String reason) {
            return new ValidationResult(false, null, null, reason);
        }
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

        TripCompletedPayload tripPayload = validateTripCompleted(payload, aggregateId);
        if (tripPayload == null) {
            return ValidationResult.fail(REASON_MALFORMED);
        }

        EventEnvelope envelope = new EventEnvelope(eventId, eventType, schemaVersion.intValue(), occurredAt,
                producer, correlationId, causationId, aggregateId, aggregateVersion, payload);
        return ValidationResult.ok(envelope, tripPayload);
    }

    /** Payload TripCompleted theo events.md §3.4; trả null nếu sai schema.
     *  Chốt T23: {@code tripId} phải khớp {@code aggregateId} (event về đúng trip) —
     *  lệch là envelope không nhất quán → malformed, không xử lý. */
    private TripCompletedPayload validateTripCompleted(Map<String, Object> payload, UUID aggregateId) {
        UUID tripId = parseUuid(asString(payload.get("tripId")));
        if (tripId == null || !tripId.equals(aggregateId)) {
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
        String currency = asString(payload.get("currency"));
        // MVP: chỉ chấp nhận "VND" (events.md §3.4).
        if (!CURRENCY_VND.equals(currency)) {
            return null;
        }
        return new TripCompletedPayload(tripId, attemptRaw.intValue(), amountRaw.longValue(), currency);
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
