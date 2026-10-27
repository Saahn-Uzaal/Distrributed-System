package com.ridehailing.trip.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * T16 — envelope chuẩn đã parse (events.md §2). Field optional theo contract:
 * causationId và aggregateVersion có thể null.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String producer,
        UUID correlationId,
        UUID causationId,
        UUID aggregateId,
        Integer aggregateVersion,
        Map<String, Object> payload) {
}
