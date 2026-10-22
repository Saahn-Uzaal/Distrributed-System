package com.ridehailing.trip.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * T12 — outbox producer (events.md §4.6): ghi event PENDING cùng transaction với nghiệp vụ.
 * id = eventId ổn định khi publish lại (§2). Worker publish + đánh dấu SENT thuộc T13.
 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ghi row PENDING. payloadJson phải là JSON hợp lệ — bind explicit {@code ?::jsonb}
     * (pgjdbc bind String thành varchar, không suy ra jsonb).
     * T12-2 (N33): ghi thêm aggregate_version (chụp trips.version lúc ghi) + routing_key
     * cho envelope/retry; schema_version/producer cố định ở producer. */
    public void insertPending(UUID eventId, String eventType, UUID aggregateId, String payloadJson,
                             UUID correlationId, Instant occurredAt, int aggregateVersion, String routingKey) {
        jdbc.update("INSERT INTO outbox (id, event_type, aggregate_id, payload, correlation_id,"
                        + " causation_id, occurred_at, status, aggregate_version, schema_version, producer, routing_key)"
                        + " VALUES (?, ?, ?, ?::jsonb, ?, NULL, ?, 'PENDING', ?, 1, 'trip-service', ?)",
                eventId, eventType, aggregateId, payloadJson, correlationId,
                new java.sql.Timestamp(occurredAt.toEpochMilli()), aggregateVersion, routingKey);
    }

    public int countPending() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'", Integer.class);
        return c == null ? 0 : c;
    }
}
