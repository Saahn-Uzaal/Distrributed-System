package com.ridehailing.trip.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** T25 — notifications (spec §Trip database, api-contracts.md §3.4): recipient = user_id.
 * Chốt T25: dedup theo transition bằng unique {@code (user_id, trip_id, version, type)} +
 * ON CONFLICT DO NOTHING — redelivery/retry của cùng một transition không tạo thông báo trùng. */
@Repository
public class NotificationRepository {

    /** Row notifications cho GET /api/notifications (contract §3.4). */
    public record NotificationRow(UUID notificationId, UUID tripId, String type, String message,
                                 Instant readAt, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ghi notification cho MỘT user tại một transition (version = trips.version lúc đó).
     *  Trùng (user_id, trip_id, version, type) → bỏ qua (idempotent). */
    public void insert(UUID userId, UUID tripId, String type, int version, String message) {
        jdbc.update("INSERT INTO notifications (id, user_id, trip_id, type, message, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (user_id, trip_id, version, type) DO NOTHING",
                UUID.randomUUID(), userId, tripId, type, message, version);
    }

    /** GET /api/notifications — chỉ notification của caller, sắp created_at DESC (§3.4). */
    public List<NotificationRow> listForUser(UUID userId, long offset, int limit) {
        return jdbc.query("SELECT id, trip_id, type, message, read_at, created_at"
                        + " FROM notifications WHERE user_id = ? ORDER BY created_at DESC, id"
                        + " LIMIT ? OFFSET ?",
                (rs, i) -> new NotificationRow(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6).toInstant()),
                userId, limit, offset);
    }

    public long countForUser(UUID userId) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class, userId);
        return c == null ? 0 : c;
    }
}
