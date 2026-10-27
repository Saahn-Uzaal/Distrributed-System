package com.ridehailing.trip.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * T12 — outbox producer (events.md §4.6): ghi event PENDING cùng transaction với nghiệp vụ.
 * id = eventId ổn định khi publish lại (§2). Worker publish + đánh dấu SENT thuộc T13.
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

    /** Ghi row PENDING (root event — không có causation). payloadJson phải là JSON hợp lệ
     *  — bind explicit {@code ?::jsonb} (pgjdbc bind String thành varchar, không suy ra jsonb).
     *  T12-2 (N33): ghi thêm aggregate_version (chụp trips.version lúc ghi) + routing_key
     *  cho envelope/retry; schema_version/producer cố định ở producer. */
    public void insertPending(UUID eventId, String eventType, UUID aggregateId, String payloadJson,
                             UUID correlationId, Instant occurredAt, int aggregateVersion, String routingKey) {
        insertPending(eventId, eventType, aggregateId, payloadJson, correlationId, null, occurredAt,
                aggregateVersion, routingKey);
    }

    /** T16 — ghi row PENDING kèm causationId (events.md §5): retry TripRequested attempt mới
     *  reference eventId của event DriverProposed/NoDriverFound đã gây ra nó. */
    public void insertPending(UUID eventId, String eventType, UUID aggregateId, String payloadJson,
                             UUID correlationId, UUID causationId, Instant occurredAt,
                             int aggregateVersion, String routingKey) {
        jdbc.update("INSERT INTO outbox (id, event_type, aggregate_id, payload, correlation_id,"
                        + " causation_id, occurred_at, status, aggregate_version, schema_version, producer, routing_key)"
                        + " VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, 'PENDING', ?, 1, 'trip-service', ?)",
                eventId, eventType, aggregateId, payloadJson, correlationId, causationId,
                new Timestamp(occurredAt.toEpochMilli()), aggregateVersion, routingKey);
    }

    /**
     * T16 fix (T1): như {@link #insertPending} nhưng chưa được publish trước {@code clock_timestamp() + delayMs}
     * (next_attempt_at — điều kiện claim của outbox worker). Dùng cho MATCHING_RETRY_DELAY_MS: trước đây row ghi
     * ngay nên TripRequested lượt mới được publish tức thì, độ trễ chỉ nằm trên giấy (saga.next_retry_at).
     */
    public void insertPendingDelayed(UUID eventId, String eventType, UUID aggregateId, String payloadJson,
                                     UUID correlationId, UUID causationId, Instant occurredAt,
                                     int aggregateVersion, String routingKey, long delayMs) {
        jdbc.update("INSERT INTO outbox (id, event_type, aggregate_id, payload, correlation_id,"
                        + " causation_id, occurred_at, status, aggregate_version, schema_version, producer, routing_key,"
                        + " next_attempt_at)"
                        + " VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, 'PENDING', ?, 1, 'trip-service', ?,"
                        + " clock_timestamp() + make_interval(secs => ?))",
                eventId, eventType, aggregateId, payloadJson, correlationId, causationId,
                new Timestamp(occurredAt.toEpochMilli()), aggregateVersion, routingKey, Math.max(0, delayMs) / 1000.0);
    }

    /**
     * T13 — claim MỘT row PENDING đủ điều kiện publish (chưa có next_attempt_at hoặc đã đến hạn),
     * FIFO theo created_at. Claim bằng {@code FOR UPDATE SKIP LOCKED} + lease: next_attempt_at được
     * đẩy tới {@code clock_timestamp() + lease} ngay trong câu UPDATE, nên worker khác (thread/instance)
     * bỏ qua row này trong lúc nó đang được publish mà không cần giữ khóa DB khi gọi broker.
     * Worker chết giữa chừng -> lease hết hạn -> row được publish lại với CÙNG eventId (at-least-once).
     * Mọi so sánh thời gian dùng clock_timestamp() của PostgreSQL (một nguồn đồng hồ — R04).
     */
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

    /** T13 — đánh dấu SENT sau khi broker confirm (events.md §4.6); sent_at theo đồng hồ DB. */
    public void markSent(UUID id) {
        jdbc.update("UPDATE outbox SET status = 'SENT', sent_at = clock_timestamp(), next_attempt_at = NULL,"
                + " last_error = NULL WHERE id = ? AND status = 'PENDING'", id);
    }

    /** T13 — publish thất bại/returned: giữ PENDING, ghi last_error + backoff (events.md §1).
     *  next_attempt_at tính bằng đồng hồ DB, cùng nguồn với điều kiện claim. */
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
