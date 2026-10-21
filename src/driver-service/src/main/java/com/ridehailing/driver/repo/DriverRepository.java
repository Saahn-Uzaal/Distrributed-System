package com.ridehailing.driver.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * T09 — truy cập bảng drivers/vehicles (driver_db) bằng JdbcTemplate.
 * Không JPA: giữ stack JDBC + Flyway như baseline T03.
 */
@Repository
public class DriverRepository {

    public record VehicleRow(UUID id, String plateNumber, String vehicleType) {
    }

    public record DriverRow(
            UUID id,
            UUID userId,
            String displayName,
            UUID vehicleId,
            String status,
            Double latitude,
            Double longitude,
            Instant lastSeenAt,
            UUID currentReservationId,
            UUID currentTripId) {
    }

    private final JdbcTemplate jdbc;

    public DriverRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- vehicles ---

    public VehicleRow insertVehicle(String plateNumber, String vehicleType) {
        List<VehicleRow> rows = jdbc.query(
                "INSERT INTO vehicles (plate_number, vehicle_type) VALUES (?, ?) "
                        + "RETURNING id, plate_number, vehicle_type",
                new Object[]{plateNumber, vehicleType}, this::mapVehicle);
        return rows.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("vehicle insert returned no row"));
    }

    public VehicleRow findVehicle(UUID id) {
        List<VehicleRow> rows = jdbc.query(
                "SELECT * FROM vehicles WHERE id = ?", new Object[]{id}, this::mapVehicle);
        return rows.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("vehicle row missing for id " + id));
    }

    // --- drivers ---

    public void lockProfileUser(UUID userId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (rs, rowNum) -> Boolean.TRUE, "driver-profile:" + userId);
    }

    public Optional<DriverRow> findByUserId(UUID userId) {
        List<DriverRow> rows = jdbc.query(
                "SELECT * FROM drivers WHERE user_id = ?", new Object[]{userId}, this::mapDriver);
        return rows.stream().findFirst();
    }

    /** T10 — đọc theo driverId (details lỗi DRIVER_NOT_AVAILABLE). */
    public Optional<DriverRow> findById(UUID id) {
        List<DriverRow> rows = jdbc.query(
                "SELECT * FROM drivers WHERE id = ?", new Object[]{id}, this::mapDriver);
        return rows.stream().findFirst();
    }

    public DriverRow insertDriver(UUID userId, String displayName, UUID vehicleId) {
        List<DriverRow> rows = jdbc.query(
                "INSERT INTO drivers (user_id, display_name, vehicle_id, status) VALUES (?, ?, ?, 'OFFLINE') "
                        + "RETURNING *",
                new Object[]{userId, displayName, vehicleId}, this::mapDriver);
        return rows.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("driver insert returned no row"));
    }

    /**
     * CAS availability: chỉ cho phép khi state hiện tại OFFLINE/AVAILABLE (state machine §4 —
     * PUT không có transition từ RESERVED/BUSY).
     * T09-1 (F07): DB tự gán last_seen_at SAU khi giành được khóa row bằng
     * GREATEST(last_seen_at, clock_timestamp()) ... RETURNING — timestamp không lùi,
     * cùng nguồn đồng hồ với cutoff heartbeat (T10-1/R04). Trả giá trị thực đã lưu.
     */
    public Optional<Instant> updateAvailabilityIfFree(UUID driverId, String newStatus) {
        List<Instant> rows = jdbc.query(
                "UPDATE drivers SET status = ?, last_seen_at = GREATEST(last_seen_at, clock_timestamp()),"
                        + " updated_at = clock_timestamp(), version = version + 1"
                        + " WHERE id = ? AND status IN ('OFFLINE', 'AVAILABLE') RETURNING last_seen_at",
                new Object[]{newStatus, driverId}, (rs, rowNum) -> rs.getTimestamp("last_seen_at").toInstant());
        return rows.stream().findFirst();
    }

    /** Cập nhật tọa độ + heartbeat; cho phép ở mọi state (contract §3.2). T09-1: DB gán thời gian.
     * Trả last_seen_at thực đã lưu (null nếu không có row). */
    public Optional<Instant> updateLocation(UUID driverId, double latitude, double longitude) {
        List<Instant> rows = jdbc.query(
                "UPDATE drivers SET latitude = ?, longitude = ?,"
                        + " last_seen_at = GREATEST(last_seen_at, clock_timestamp()), updated_at = clock_timestamp()"
                        + " WHERE id = ? RETURNING last_seen_at",
                new Object[]{latitude, longitude, driverId}, (rs, rowNum) -> rs.getTimestamp("last_seen_at").toInstant());
        return rows.stream().findFirst();
    }

    /**
     * T09-5 (R05): candidates — lọc sơ bộ trong SQL: status + heartbeat mới (cutoff tính bằng
     * clock_timestamp() - make_interval, cùng nguồn đồng hồ với CAS reserve) + bounding box
     * (lat ± radius/111 km, lng ± /(cos lat)) + ORDER BY khoảng cách xấp xỉ trên mặt cầu,
     * LIMIT hữu hạn. Haversine chính xác vẫn tính trong Java phía caller.
     */
    public List<DriverRow> findCandidatesInBox(double latitude, double longitude, int radiusM,
                                               long heartbeatStaleAfterMs, int limit) {
        double latDelta = (radiusM / 1000.0) / 111.0;
        double cosLat = Math.max(Math.cos(Math.toRadians(latitude)), 1e-6);
        double lngDelta = (radiusM / 1000.0) / (111.0 * cosLat);
        return jdbc.query(
                "SELECT * FROM drivers"
                        + " WHERE status = 'AVAILABLE'"
                        + " AND last_seen_at > clock_timestamp() - make_interval(secs => ?)"
                        + " AND latitude IS NOT NULL AND longitude IS NOT NULL"
                        + " AND latitude BETWEEN ? AND ?"
                        + " AND longitude BETWEEN ? AND ?"
                        + " ORDER BY (latitude - ?) * (latitude - ?)"
                        + "          + (longitude - ?) * (longitude - ?) * cos(radians(?)) * cos(radians(?)) ASC"
                        + " LIMIT ?",
                new Object[]{heartbeatStaleAfterMs / 1000.0,
                        latitude - latDelta, latitude + latDelta,
                        longitude - lngDelta, longitude + lngDelta,
                        latitude, latitude, longitude, longitude, latitude, latitude, limit},
                this::mapDriver);
    }

    // RowMapper-compatible: (ResultSet, int). Method reference 1-arg (ResultSet)
    // would match the Spring 7 ResultSetExtractor<T> overload and break inference.
    private VehicleRow mapVehicle(ResultSet rs, int rowNum) throws SQLException {
        return new VehicleRow((UUID) rs.getObject("id"), rs.getString("plate_number"), rs.getString("vehicle_type"));
    }

    private DriverRow mapDriver(ResultSet rs, int rowNum) throws SQLException {
        Timestamp lastSeen = rs.getTimestamp("last_seen_at");
        double latitude = rs.getDouble("latitude");
        boolean latNull = rs.wasNull();
        double longitude = rs.getDouble("longitude");
        boolean lngNull = rs.wasNull();
        return new DriverRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("user_id"),
                rs.getString("display_name"),
                (UUID) rs.getObject("vehicle_id"),
                rs.getString("status"),
                latNull ? null : latitude,
                lngNull ? null : longitude,
                lastSeen == null ? null : lastSeen.toInstant(),
                (UUID) rs.getObject("current_reservation_id"),
                (UUID) rs.getObject("current_trip_id"));
    }
}
