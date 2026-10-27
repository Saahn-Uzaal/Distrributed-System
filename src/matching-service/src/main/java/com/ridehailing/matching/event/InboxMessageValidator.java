package com.ridehailing.matching.event;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T14 — validation event khi consume (events.md §2):
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

    /** Event duy nhất matching-service xử lý (events.md §1: matching.inbox ← trip.requested). */
    private static final Set<String> HANDLED_EVENT_TYPES = Set.of("TripRequested");

    private final ObjectMapper json;

    public InboxMessageValidator(ObjectMapper json) {
        this.json = json;
    }

    /** Kết quả validate: ok + envelope/payload đã parse, hoặc reason để republish DLQ. */
    public record ValidationResult(boolean ok, EventEnvelope envelope, TripRequestedPayload payload, String reason) {
        static ValidationResult ok(EventEnvelope envelope, TripRequestedPayload payload) {
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
            Map<String, Object> parsed = json.readValue(body, Map.class);
            root = parsed;
        } catch (Exception e) {
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
        if (schemaVersion.intValue() != 1) {
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
            if (v == null || !isWhole(v) || v.intValue() < 0) {
                return ValidationResult.fail(REASON_MALFORMED);
            }
            aggregateVersion = v.intValue();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = root.get("payload") instanceof Map<?, ?> p ? (Map<String, Object>) p : null;
        if (payload == null) {
            return ValidationResult.fail(REASON_MALFORMED);
        }

        TripRequestedPayload tripPayload = validateTripRequested(payload);
        if (tripPayload == null) {
            return ValidationResult.fail(REASON_MALFORMED);
        }

        EventEnvelope envelope = new EventEnvelope(eventId, eventType, schemaVersion.intValue(), occurredAt,
                producer, correlationId, causationId, aggregateId, aggregateVersion, payload);
        return ValidationResult.ok(envelope, tripPayload);
    }

    /** Payload TripRequested theo events.md §3.1; trả null nếu sai schema. */
    private TripRequestedPayload validateTripRequested(Map<String, Object> payload) {
        UUID tripId = parseUuid(asString(payload.get("tripId")));
        if (tripId == null) {
            return null;
        }
        Number attemptRaw = asNumber(payload.get("attempt"));
        if (attemptRaw == null || !isWhole(attemptRaw) || attemptRaw.intValue() < 1) {
            return null;
        }
        Object pickupRaw = payload.get("pickup");
        if (!(pickupRaw instanceof Map<?, ?> pickup)) {
            return null;
        }
        Double latitude = asDouble(pickup.get("latitude"));
        Double longitude = asDouble(pickup.get("longitude"));
        if (latitude == null || longitude == null
                || latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
            return null;
        }
        Object excludedRaw = payload.get("excludedDriverIds");
        if (!(excludedRaw instanceof List<?> list)) {
            return null;
        }
        java.util.List<UUID> excluded = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            UUID id = parseUuid(asString(item));
            if (id == null) {
                return null;
            }
            excluded.add(id);
        }
        return new TripRequestedPayload(tripId, attemptRaw.intValue(), latitude, longitude, List.copyOf(excluded));
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    private static Number asNumber(Object o) {
        return o instanceof Number n ? n : null;
    }

    /** Số hữu hạn, không phải NaN/Infinity (tọa độ theo contract §1.4). */
    private static Double asDouble(Object o) {
        if (!(o instanceof Number n)) {
            return null;
        }
        double d = n.doubleValue();
        return Double.isFinite(d) ? d : null;
    }

    private static boolean isWhole(Number n) {
        double d = n.doubleValue();
        return !Double.isNaN(d) && d == Math.rint(d);
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
