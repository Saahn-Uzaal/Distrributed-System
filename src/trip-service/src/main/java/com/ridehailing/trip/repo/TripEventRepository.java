package com.ridehailing.trip.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** T12 — trip event history (contract §3.5): ghi tại mỗi transition, version = trips.version lúc đó.
 *  T25: đọc phân trang cho GET /api/trips/{id}/events (sắp version DESC). */
@Repository
public class TripEventRepository {

    /** Row trip_events cho GET /api/trips/{id}/events (contract §3.3). */
    public record EventRow(String type, int version, UUID actorId, String summary, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    public TripEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID tripId, String type, int version, UUID actorId, String summary) {
        jdbc.update("INSERT INTO trip_events (trip_id, type, version, actor_id, summary)"
                        + " VALUES (?, ?, ?, ?, ?)",
                tripId, type, version, actorId, summary);
    }

    public int countByTrip(UUID tripId) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM trip_events WHERE trip_id = ?", Integer.class, tripId);
        return c == null ? 0 : c;
    }

    /** T25 — GET /api/trips/{id}/events (contract §3.3): sắp version DESC; tie-break id DESC
     *  (các event cùng version — vd RESERVATION_RELEASED + NO_DRIVER — mới nhất trước). */
    public List<EventRow> listForTrip(UUID tripId, long offset, int limit) {
        return jdbc.query("SELECT type, version, actor_id, summary, created_at FROM trip_events"
                        + " WHERE trip_id = ? ORDER BY version DESC, id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new EventRow(rs.getString(1), rs.getInt(2), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getTimestamp(5).toInstant()),
                tripId, limit, offset);
    }
}
