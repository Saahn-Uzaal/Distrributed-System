package com.ridehailing.payment.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * T23 — outbox producer payment-service (events.md §4.6): ghi event PENDING cùng transaction
 * với nghiệp vụ (payments). id = eventId ổn định khi publish lại (§2). Worker publish +
 * đánh dấu SENT theo pattern trip-service T13/matching T15.
 */
@Repository
public class OutboxRepository {

    /** Row outbox đủ để build envelope chuẩn (events.md §2) và retry bookkeeping. */
    public record OutboxRow(UUID id, String eventType, UUID aggregateId, String payloadJson,
                           UUID correlationId, UUID causationId, Instant occurredAt,
                           int schemaVersion, String producer, String routingKey,
                           int aggregateVersion, int attempts) {
    }

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ghi row PENDING. payloadJson phải là JSON hợp lệ — bind explicit {@code ?::jsonb}.
     *  causationId = eventId của TripCompleted tương ứng (events.md §5). */
    public void insertPending(UUID eventId, String eventType, UUID aggregateId, String payloadJson,
                             UUID correlationId, UUID causationId, Instant occurredAt,
                             int aggregateVersion, String routingKey) {
        jdbc.update("INSERT INTO outbox (id, event_type, aggregate_id, payload, correlation_id,"
                        + " causation_id, occurred_at, status, aggregate_version, schema_version, producer, routing_key)"
                        + " VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, 'PENDING', ?, 1, 'payment-service', ?)",
                eventId, eventType, aggregateId, payloadJson, correlationId, causationId,
                new Timestamp(occurredAt.toEpochMilli()), aggregateVersion, routingKey);
    }

    /** Claim MỘT row PENDING đủ điều kiện publish (chưa có next_attempt_at hoặc đã đến hạn),
     *  FIFO theo created_at. {@code FOR UPDATE SKIP LOCKED} + lease: worker khác không lấy lại
     *  row đang publish; worker chết → lease hết hạn → publish lại CÙNG eventId (at-least-once).
     *  Mọi so sánh thời gian dùng clock_timestamp() của PostgreSQL (một nguồn đồng hồ — R04). */
    public Optional<OutboxRow> claimNextEligible(long leaseMs) {
        List<OutboxRow> rows = jdbc.query(
                "UPDATE outbox SET next_attempt_at = clock_timestamp() + make_interval(secs => ?)"
                        + " WHERE id = (SELECT id FROM outbox WHERE status = 'PENDING'"
                        + " AND (next_attempt_at IS NULL OR next_attempt_at <= clock_timestamp())"
                        + " ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)"
                        + " RETURNING id, event_type, aggregate_id, payload, correlation_id, causation_id, occurred_at,"
                        + " schema_version, producer, routing_key, aggregate_version, attempts",
                (rs, i) -> new OutboxRow(
                        (UUID) rs.getObject("id"), rs.getString("event_type"), (UUID) rs.getObject("aggregate_id"),
                        rs.getString("payload"), (UUID) rs.getObject("correlation_id"), (UUID) rs.getObject("causation_id"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getInt("schema_version"),
                        rs.getString("producer"), rs.getString("routing_key"),
                        rs.getInt("aggregate_version"), rs.getInt("attempts")),
                leaseMs / 1000.0);
        return rows.stream().findFirst();
    }

    /** Đánh dấu SENT sau khi broker confirm (events.md §4.6). */
    public void markSent(UUID id) {
        jdbc.update("UPDATE outbox SET status = 'SENT', sent_at = clock_timestamp(), next_attempt_at = NULL,"
                + " last_error = NULL WHERE id = ? AND status = 'PENDING'", id);
    }

    /** Publish thất bại/returned: giữ PENDING, ghi last_error + backoff (events.md §1). */
    public void recordFailure(UUID id, String error, long backoffMs) {
        jdbc.update("UPDATE outbox SET last_error = ?, attempts = attempts + 1,"
                        + " next_attempt_at = clock_timestamp() + make_interval(secs => ?)"
                        + " WHERE id = ? AND status = 'PENDING'",
                truncate(error, 2000), backoffMs / 1000.0, id);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public int countPending() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE status = 'PENDING'", Integer.class);
        return c == null ? 0 : c;
    }
}
