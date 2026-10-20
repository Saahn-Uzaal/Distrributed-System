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
     * (pgjdbc bind String thành varchar, không suy ra jsonb). */
    public void insertPending(UUID eventId, String eventType, UUID aggregateId, String payloadJson,
                             UUID correlationId, Instant occurredAt) {
        jdbc.update("INSERT INTO outbox (id, event_type, aggregate_id, payload, correlation_id,"
                        + " causation_id, occurred_at, status)"
                        + " VALUES (?, ?, ?, ?::jsonb, ?, NULL, ?, 'PENDING')",
                eventId, eventType, aggregateId, payloadJson, correlationId, new java.sql.Timestamp(occurredAt.toEpochMilli()));
    }

    public int countPending() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'", Integer.class);
        return c == null ? 0 : c;
    }
}
