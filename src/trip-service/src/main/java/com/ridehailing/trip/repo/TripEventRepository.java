package com.ridehailing.trip.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** T12 — trip event history (contract §3.5): ghi tại mỗi transition, version = trips.version lúc đó. */
@Repository
public class TripEventRepository {

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
}
