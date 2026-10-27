package com.ridehailing.matching.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * T15 — matching job (spec §20.4 "lưu job/attempt"): unique {@code (trip_id, attempt)}.
 * Row chỉ commit trong transaction cùng inbox dedup record + outbox PENDING (events.md §4.2);
 * rollback → không có row → redelivery xử lý lại từ đầu.
 */
@Repository
public class MatchingJobRepository {

    private final JdbcTemplate jdbc;

    public MatchingJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ghi job ở state cuối (PROPOSED/NO_DRIVER_FOUND). candidatesJson: JSON list có thứ tự, hoặc null. */
    public void insert(UUID tripId, int attempt, String state, String candidatesJson,
                       UUID eventId, UUID correlationId) {
        jdbc.update(
                "INSERT INTO matching_jobs (trip_id, attempt, state, candidates, event_id, correlation_id)"
                        + " VALUES (?, ?, ?, ?::jsonb, ?, ?)",
                tripId, attempt, state, candidatesJson, eventId, correlationId);
    }

    /** T15 fix (M2): khóa advisory theo trip trong transaction hiện tại — các TripRequested của cùng một trip
     *  được xử lý tuần tự, nên kiểm tra MAX(attempt) và insert job không bị hai event song song vượt qua. */
    public void lockTrip(UUID tripId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, "matching-trip:" + tripId);
    }

    /** Attempt lớn nhất đã ghi cho trip — dùng stale-event guard (events.md §4.4). */
    public Integer maxAttemptForTrip(UUID tripId) {
        return jdbc.queryForObject("SELECT MAX(attempt) FROM matching_jobs WHERE trip_id = ?",
                Integer.class, tripId);
    }

    /** Số job của một (trip, attempt) — test/verify. */
    public int countByTripAndAttempt(UUID tripId, int attempt) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM matching_jobs WHERE trip_id = ? AND attempt = ?",
                Integer.class, tripId, attempt);
        return n == null ? 0 : n;
    }
}
