package com.ridehailing.trip.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** T12 — trips (trip_db). JdbcTemplate theo pattern T03/T09 (chưa có JPA). */
@Repository
public class TripRepository {

    /** Row trips theo contract §3.3 (detail) + cột nội bộ cho transition sau. */
    public record TripRow(UUID id, UUID passengerId, Double pickupLat, Double pickupLng,
                          Double destinationLat, Double destinationLng, long distanceM, long fareVnd,
                          String state, int matchingAttempt, UUID driverId, UUID driverUserId, String paymentStatus,
                          int paymentAttempt, int version, Instant createdAt, Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;

    public TripRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** INSERT trip mới (state SEARCHING, attempt 1, version 1). Id sinh phía Java. */
    public UUID insert(UUID id, UUID passengerId, double pickupLat, double pickupLng,
                       double destinationLat, double destinationLng, long distanceM, long fareVnd) {
        jdbc.update("INSERT INTO trips (id, passenger_id, pickup_lat, pickup_lng, "
                        + "destination_lat, destination_lng, distance_m, fare_vnd, state, matching_attempt, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SEARCHING', 1, 1)",
                id, passengerId, pickupLat, pickupLng, destinationLat, destinationLng, distanceM, fareVnd);
        return id;
    }

    public Optional<TripRow> findById(UUID id) {
        List<TripRow> rows = jdbc.query(
                "SELECT id, passenger_id, pickup_lat, pickup_lng, destination_lat, destination_lng,"
                        + " distance_m, fare_vnd, state, matching_attempt, driver_id, driver_user_id, payment_status,"
                        + " payment_attempt, version, created_at, updated_at FROM trips WHERE id = ?",
                this::mapRow, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Passenger thấy chuyến của mình; driver thấy chuyến đã assign cho mình (contract §3.3). */
    public List<TripRow> listForUser(UUID passengerId, UUID driverUserId, long offset, int limit) {
        return jdbc.query("SELECT id, passenger_id, pickup_lat, pickup_lng, destination_lat,"
                        + " destination_lng, distance_m, fare_vnd, state, matching_attempt, driver_id, driver_user_id,"
                        + " payment_status, payment_attempt, version, created_at, updated_at"
                        + " FROM trips WHERE passenger_id = ? OR driver_user_id = ?"
                        + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                this::mapRow, passengerId, driverUserId, limit, offset);
    }

    public long countForUser(UUID passengerId, UUID driverUserId) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trips WHERE passenger_id = ? OR driver_user_id = ?",
                Long.class, passengerId, driverUserId);
        return c == null ? 0 : c;
    }

    private TripRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Object driverRaw = rs.getObject("driver_id");
        return new TripRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("passenger_id"),
                rs.getDouble("pickup_lat"),
                rs.getDouble("pickup_lng"),
                rs.getDouble("destination_lat"),
                rs.getDouble("destination_lng"),
                rs.getLong("distance_m"),
                rs.getLong("fare_vnd"),
                rs.getString("state"),
                rs.getInt("matching_attempt"),
                driverRaw == null ? null : (UUID) driverRaw,
                (UUID) rs.getObject("driver_user_id"),
                rs.getString("payment_status"),
                rs.getInt("payment_attempt"),
                rs.getInt("version"),
                ((Timestamp) rs.getObject("created_at")).toInstant(),
                ((Timestamp) rs.getObject("updated_at")).toInstant());
    }
}
