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
                          Double destinationLat, Double destinationLng, String pickupAddress,
                          String destinationAddress, long distanceM, long routeDurationS,
                          String routeGeometry, long fareVnd,
                          String state, int matchingAttempt, UUID driverId, UUID driverUserId, String paymentStatus,
                          int paymentAttempt, int version, Instant createdAt, Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;

    public TripRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** INSERT trip mới (state SEARCHING, attempt 1, version 1). Id sinh phía Java.
     * T12-1 (F14): created_at ghi tường minh từ caller — một timestamp duy nhất cho
     * response/replay/outbox và DB. */
    public UUID insert(UUID id, UUID passengerId, double pickupLat, double pickupLng,
                       double destinationLat, double destinationLng, long distanceM, long fareVnd,
                       Instant createdAt) {
        return insert(id, passengerId, pickupLat, pickupLng, destinationLat, destinationLng,
                pickupLat + ", " + pickupLng, destinationLat + ", " + destinationLng,
                distanceM, 0, "{\"type\":\"LineString\",\"coordinates\":[]}", fareVnd, createdAt);
    }

    public UUID insert(UUID id, UUID passengerId, double pickupLat, double pickupLng,
                       double destinationLat, double destinationLng, String pickupAddress,
                       String destinationAddress, long distanceM, long routeDurationS,
                       String routeGeometry, long fareVnd, Instant createdAt) {
        jdbc.update("INSERT INTO trips (id,passenger_id,pickup_lat,pickup_lng,destination_lat,destination_lng,"
                        + "pickup_address,destination_address,distance_m,route_duration_s,route_geometry,fare_vnd,state,matching_attempt,version,created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?,'SEARCHING',1,1,?)",
                id, passengerId, pickupLat, pickupLng, destinationLat, destinationLng, pickupAddress,
                destinationAddress, distanceM, routeDurationS, routeGeometry, fareVnd, Timestamp.from(createdAt));
        return id;
    }

    public Optional<TripRow> findById(UUID id) {
        List<TripRow> rows = jdbc.query(
                "SELECT id, passenger_id, pickup_lat, pickup_lng, destination_lat, destination_lng,"
                        + " pickup_address,destination_address,distance_m,route_duration_s,route_geometry::text,fare_vnd, state, matching_attempt, driver_id, driver_user_id, payment_status,"
                        + " payment_attempt, version, created_at, updated_at FROM trips WHERE id = ?",
                this::mapRow, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Passenger thấy chuyến của mình; driver thấy chuyến đã assign cho mình (contract §3.3). */
    public List<TripRow> listForUser(UUID passengerId, UUID driverUserId, long offset, int limit) {
        return jdbc.query("SELECT id, passenger_id, pickup_lat, pickup_lng, destination_lat,"
                        + " destination_lng,pickup_address,destination_address,distance_m,route_duration_s,route_geometry::text,fare_vnd, state, matching_attempt, driver_id, driver_user_id,"
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

    /** T16 — SEARCHING → DRIVER_OFFERED (state-machines.md §1): set driver_id/driver_user_id/reservation_id
     *  + version+1, guard state/matching_attempt hiện tại. Trả số row update (0 = CAS fail). */
    public int markDriverOffered(UUID tripId, String expectedState, int expectedAttempt,
                                UUID driverId, UUID driverUserId, UUID reservationId) {
        return jdbc.update("UPDATE trips SET state = 'DRIVER_OFFERED', driver_id = ?, driver_user_id = ?,"
                        + " reservation_id = ?, version = version + 1, updated_at = clock_timestamp()"
                        + " WHERE id = ? AND state = ? AND matching_attempt = ?",
                driverId, driverUserId, reservationId, tripId, expectedState, expectedAttempt);
    }

    /** T16 — SEARCHING → SEARCHING: attempt+1 (events.md §3.2), version+1; guard state/attempt hiện tại. */
    public int bumpMatchingAttempt(UUID tripId, String expectedState, int expectedAttempt) {
        return jdbc.update("UPDATE trips SET matching_attempt = matching_attempt + 1,"
                        + " version = version + 1, updated_at = clock_timestamp()"
                        + " WHERE id = ? AND state = ? AND matching_attempt = ?",
                tripId, expectedState, expectedAttempt);
    }

    /** T16 — SEARCHING → NO_DRIVER terminal (events.md §3.2), version+1; guard state/attempt hiện tại. */
    public int markNoDriver(UUID tripId, String expectedState, int expectedAttempt) {
        return jdbc.update("UPDATE trips SET state = 'NO_DRIVER', version = version + 1,"
                        + " updated_at = clock_timestamp() WHERE id = ? AND state = ? AND matching_attempt = ?",
                tripId, expectedState, expectedAttempt);
    }

    // ---------- T18–T20: chuyển trạng thái có điều kiện, trả version mới (empty = CAS thất bại) ----------

    private Optional<Integer> updateReturningVersion(String sql, Object... args) {
        List<Integer> rows = jdbc.query(sql, (rs, i) -> rs.getInt("version"), args);
        return rows.stream().findFirst();
    }

    /** T18 — DRIVER_OFFERED → ACCEPTED, chỉ khi đúng attempt đang offer. */
    public Optional<Integer> markAccepted(UUID tripId, int attempt) {
        return updateReturningVersion("UPDATE trips SET state = 'ACCEPTED', version = version + 1,"
                + " updated_at = clock_timestamp() WHERE id = ? AND state = 'DRIVER_OFFERED' AND matching_attempt = ?"
                + " RETURNING version", tripId, attempt);
    }

    /** T19 — DRIVER_OFFERED → SEARCHING (attempt + 1) sau khi release đã xác nhận; xóa driver/reservation
     *  (chốt N22: driver_id/driver_user_id về NULL khi reject/expire). */
    public Optional<Integer> markSearchingAfterRelease(UUID tripId, int attempt) {
        return updateReturningVersion("UPDATE trips SET state = 'SEARCHING', matching_attempt = matching_attempt + 1,"
                + " driver_id = NULL, driver_user_id = NULL, reservation_id = NULL, version = version + 1,"
                + " updated_at = clock_timestamp() WHERE id = ? AND state = 'DRIVER_OFFERED' AND matching_attempt = ?"
                + " RETURNING version", tripId, attempt);
    }

    /** T19 — DRIVER_OFFERED → NO_DRIVER (hết lượt/deadline) sau khi release đã xác nhận. */
    public Optional<Integer> markNoDriverAfterRelease(UUID tripId, int attempt) {
        return updateReturningVersion("UPDATE trips SET state = 'NO_DRIVER', driver_id = NULL, driver_user_id = NULL,"
                + " reservation_id = NULL, version = version + 1, updated_at = clock_timestamp()"
                + " WHERE id = ? AND state = 'DRIVER_OFFERED' AND matching_attempt = ? RETURNING version", tripId, attempt);
    }

    /** T20 — SEARCHING/DRIVER_OFFERED → CANCELLING (transient, đang bù trừ). */
    public Optional<Integer> markCancelling(UUID tripId, String fromState) {
        return updateReturningVersion("UPDATE trips SET state = 'CANCELLING', version = version + 1,"
                + " updated_at = clock_timestamp() WHERE id = ? AND state = ? RETURNING version", tripId, fromState);
    }

    /** T20 — CANCELLING → CANCELLED sau khi mọi reservation liên quan đã terminal; xóa driver. */
    public Optional<Integer> markCancelled(UUID tripId) {
        return updateReturningVersion("UPDATE trips SET state = 'CANCELLED', driver_id = NULL, driver_user_id = NULL,"
                + " version = version + 1, updated_at = clock_timestamp()"
                + " WHERE id = ? AND state = 'CANCELLING' RETURNING version", tripId);
    }

    /** T22 — ACCEPTED → IN_PROGRESS (state-machines.md §1), version+1; guard state hiện tại. */
    public Optional<Integer> markInProgress(UUID tripId) {
        return updateReturningVersion("UPDATE trips SET state = 'IN_PROGRESS', version = version + 1,"
                + " updated_at = clock_timestamp() WHERE id = ? AND state = 'ACCEPTED' RETURNING version", tripId);
    }

    /** T22 — IN_PROGRESS → COMPLETED (state-machines.md §1): payment PENDING + attempt khởi tạo 1,
     *  version+1; guard state hiện tại. */
    public Optional<Integer> markCompleted(UUID tripId) {
        return updateReturningVersion("UPDATE trips SET state = 'COMPLETED', payment_status = 'PENDING',"
                + " payment_attempt = 1, version = version + 1, updated_at = clock_timestamp()"
                + " WHERE id = ? AND state = 'IN_PROGRESS' RETURNING version", tripId);
    }

    /** T24 — consume PaymentSucceeded/PaymentFailed (state-machines.md §5): payment_status PENDING →
     *  SUCCEEDED/FAILED, version+1; guard state COMPLETED + đúng attempt + payment_status PENDING.
     *  CAS fail (event stale/trùng) → empty → handler rollback + retry bounded. */
    public Optional<Integer> markPaymentResult(UUID tripId, String status, int attempt) {
        return updateReturningVersion("UPDATE trips SET payment_status = ?, version = version + 1,"
                + " updated_at = clock_timestamp() WHERE id = ? AND state = 'COMPLETED'"
                + " AND payment_status = 'PENDING' AND payment_attempt = ? RETURNING version", status, tripId, attempt);
    }

    /** T12-6 (N28): advisory lock transaction-scope theo passenger — serialize các create đồng thời
     * của cùng passenger; tự release khi transaction kết thúc. */
    public void acquirePassengerAdvisoryLock(UUID passengerId) {
        // SELECT qua query() + ResultSetExtractor — PgJDBC mới từ chối executeUpdate() cho
        // statement trả row ("A result was returned when none was expected").
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null, passengerId.toString());
    }

    /** T12-6 (N28): số trip chưa terminal của passenger (terminal = COMPLETED, CANCELLED, NO_DRIVER). */
    public long countNonTerminalForPassenger(UUID passengerId) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trips WHERE passenger_id = ? AND state NOT IN ('COMPLETED','CANCELLED','NO_DRIVER')",
                Long.class, passengerId);
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
                rs.getString("pickup_address"),
                rs.getString("destination_address"),
                rs.getLong("distance_m"),
                rs.getLong("route_duration_s"),
                rs.getString("route_geometry"),
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
