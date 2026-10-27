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
 * T10 — truy cập bảng reservations + processed_commands (driver_db) bằng JdbcTemplate.
 * Không JPA: giữ stack JDBC + Flyway như baseline T03/T09.
 */
@Repository
public class ReservationRepository {

    public record ReservationRow(
            UUID id,
            UUID tripId,
            UUID driverId,
            Integer attempt,
            String state,
            String terminalReason,
            Instant expiresAt,
            int lastCommandVersion,
            Double pickupLat,
            Double pickupLng,
            Double destinationLat,
            Double destinationLng,
            Long distanceM,
            Long fareVnd) {
    }

    public record ProcessedCommand(UUID commandId, String requestHash, String storedResponseJson) {
    }

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- processed_commands (contract §4.1) ---

    public Optional<ProcessedCommand> findProcessed(UUID commandId) {
        List<ProcessedCommand> rows = jdbc.query(
                "SELECT command_id, request_hash, stored_response FROM processed_commands WHERE command_id = ?",
                new Object[]{commandId}, this::mapProcessed);
        return rows.stream().findFirst();
    }

    public int claimProcessed(UUID commandId, String requestHash) {
        // The placeholder cannot become visible before the enclosing business transaction commits.
        return jdbc.update("INSERT INTO processed_commands (command_id, request_hash, stored_response)"
                        + " VALUES (?, ?, '{}'::jsonb) ON CONFLICT (command_id) DO NOTHING",
                commandId, requestHash);
    }

    public void completeProcessed(UUID commandId, String storedResponseJson) {
        jdbc.update("UPDATE processed_commands SET stored_response=?::jsonb WHERE command_id=?",
                storedResponseJson, commandId);
    }

    /** Covers missing rows too: reserve and an early release serialize on the same resource. */
    public void lockReservation(UUID id) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (rs, rowNum) -> Boolean.TRUE, "reservation:" + id);
    }

    /** T11-1 (F08): non-blocking variant — false nếu item đang bị giữ bởi transaction khác. */
    public boolean tryLockReservation(UUID id) {
        Boolean locked = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                Boolean.class, "reservation:" + id);
        return Boolean.TRUE.equals(locked);
    }

    /** T11-1 (F08): giới hạn thời gian chờ khóa row trong transaction hiện tại. */
    public void setLocalLockTimeout(String value) {
        jdbc.execute("SET LOCAL lock_timeout = '" + value.replace("'", "") + "'");
    }

    /** T10-4 (R04): đồng hồ duy nhất cho mọi so sánh hạn — PostgreSQL clock_timestamp(). */
    public Instant dbNow() {
        Timestamp now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        return now.toInstant();
    }

    /** Dọn processed_commands cũ hơn N ngày (T09-6, batch hữu hạn). */
    public int deleteOldProcessedCommands(int days) {
        return jdbc.update(
                // L01: PostgreSQL không hỗ trợ DELETE ... LIMIT — giới hạn batch bằng subquery theo khóa chính.
                "DELETE FROM processed_commands WHERE command_id IN ("
                        + "SELECT command_id FROM processed_commands"
                        + " WHERE created_at < clock_timestamp() - make_interval(days => ?)"
                        + " ORDER BY created_at LIMIT 1000)",
                days);
    }

    // --- reservations ---

    public Optional<ReservationRow> findById(UUID id) {
        List<ReservationRow> rows = jdbc.query(
                "SELECT * FROM reservations WHERE id = ?", new Object[]{id}, this::mapReservation);
        return rows.stream().findFirst();
    }

    /**
     * T17 — offer hiện tại của một driver (contract §3.2 GET /api/drivers/me/offer), đọc từ dữ liệu local.
     * Chỉ trả reservation khi đồng thời:
     * <ul>
     *   <li>là {@code current_reservation_id} của driver và driver đang RESERVED (fencing — reservation cũ
     *       đã release/expire hoặc của driver khác không bao giờ lộ ra);</li>
     *   <li>reservation thuộc chính driver đó và còn state RESERVED (CONFIRMED = đã nhận, không còn là offer);</li>
     *   <li>chưa hết hạn theo {@code clock_timestamp()} của PostgreSQL — cùng nguồn đồng hồ với expiry worker
     *       và confirm (R04), nên offer biến mất đúng lúc hết hạn dù worker chưa kịp chạy.</li>
     * </ul>
     */
    public Optional<ReservationRow> findCurrentOffer(UUID driverId) {
        List<ReservationRow> rows = jdbc.query(
                "SELECT r.* FROM drivers d JOIN reservations r ON r.id = d.current_reservation_id"
                        + " WHERE d.id = ? AND d.status = 'RESERVED' AND d.current_trip_id IS NULL"
                        + " AND r.driver_id = d.id AND r.state = 'RESERVED'"
                        + " AND r.expires_at > clock_timestamp()",
                new Object[]{driverId}, this::mapReservation);
        return rows.stream().findFirst();
    }

    /** Insert a complete live reservation; release-before-reserve uses insertTombstone instead. */
    public void insertReserved(UUID id, UUID tripId, UUID driverId, int attempt, Instant expiresAt,
                              int lastCommandVersion, double pickupLat, double pickupLng,
                              double destinationLat, double destinationLng, long distanceM, long fareVnd) {
        jdbc.update(
                "INSERT INTO reservations (id, trip_id, driver_id, attempt, state, expires_at, last_command_version,"
                        + " pickup_lat, pickup_lng, destination_lat, destination_lng, distance_m, fare_vnd)"
                        + " VALUES (?, ?, ?, ?, 'RESERVED', ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tripId, driverId, attempt, Timestamp.from(expiresAt), lastCommandVersion,
                pickupLat, pickupLng, destinationLat, destinationLng, distanceM, fareVnd);
    }

    /**
     * CAS reserve (state-machines.md §3): chỉ thành công khi driver AVAILABLE và heartbeat còn mới.
     * affected rows != 1 -> caller rollback toàn transaction (reservation insert bị hủy).
     * T10-1 (F06) + T10-4 (R04): cutoff heartbeat được đánh giá TRONG câu SQL bằng
     * clock_timestamp() - make_interval(secs => ?), tức là SAU khi PostgreSQL giành được
     * khóa row — không dùng timestamp JVM tính trước.
     */
    public int casReserveDriver(UUID driverId, UUID reservationId, double heartbeatStaleAfterSecs) {
        return jdbc.update(
                "UPDATE drivers SET status = 'RESERVED', current_reservation_id = ?,"
                        + " updated_at = clock_timestamp(), version = version + 1"
                        + " WHERE id = ? AND status = 'AVAILABLE'"
                        + " AND last_seen_at > clock_timestamp() - make_interval(secs => ?)"
                        + " AND current_reservation_id IS NULL AND current_trip_id IS NULL",
                reservationId, driverId, heartbeatStaleAfterSecs);
    }

    public void insertTombstone(UUID id, int version, String reason) {
        jdbc.update("INSERT INTO reservations (id, state, terminal_reason, last_command_version)"
                + " VALUES (?, 'RELEASED', ?, ?)", id, reason, version);
    }

    public int confirmDriver(UUID driverId, UUID reservationId, UUID tripId) {
        return jdbc.update("UPDATE drivers SET status='BUSY', current_trip_id=?, updated_at=now()"
                        + " WHERE id=? AND status='RESERVED' AND current_reservation_id=?"
                        + " AND current_trip_id IS NULL", tripId, driverId, reservationId);
    }

    public void lockDriver(UUID driverId) {
        jdbc.query("SELECT id FROM drivers WHERE id=? FOR UPDATE",
                (rs, rowNum) -> rs.getObject("id"), driverId);
    }

    /** Both pointers fence BUSY assignments; an old command cannot clear a newer assignment. */
    public int releaseDriver(ReservationRow row) {
        return jdbc.update("UPDATE drivers SET status='AVAILABLE', current_reservation_id=NULL,"
                        + " current_trip_id=NULL, updated_at=now() WHERE id=? AND current_reservation_id=?"
                        + " AND ((?='RESERVED' AND status='RESERVED' AND current_trip_id IS NULL)"
                        + " OR (?='CONFIRMED' AND status='BUSY' AND current_trip_id=?))",
                row.driverId(), row.id(), row.state(), row.state(), row.tripId());
    }

    public void transition(UUID id, String state, String reason, int version) {
        jdbc.update("UPDATE reservations SET state=?, terminal_reason=?, last_command_version=?,"
                + " updated_at=now() WHERE id=?", state, reason, version, id);
    }

    /** T10-4 (R04): clock_timestamp() thay now() — now() là thời điểm bắt đầu transaction,
     * còn expiry phải so sánh với thời điểm thực tại. */
    public List<UUID> findExpiredIds(int limit) {
        return jdbc.query("SELECT id FROM reservations WHERE state='RESERVED' AND expires_at<=clock_timestamp()"
                + " ORDER BY expires_at, id LIMIT ?", (rs, n) -> (UUID) rs.getObject("id"), limit);
    }

    public int expireReserved(UUID id) {
        return jdbc.update("UPDATE reservations SET state='EXPIRED', terminal_reason='OFFER_EXPIRED',"
                + " updated_at=clock_timestamp() WHERE id=? AND state='RESERVED' AND expires_at<=clock_timestamp()", id);
    }

    private ProcessedCommand mapProcessed(ResultSet rs, int rowNum) throws SQLException {
        return new ProcessedCommand(
                (UUID) rs.getObject("command_id"),
                rs.getString("request_hash"),
                rs.getString("stored_response"));
    }

    private ReservationRow mapReservation(ResultSet rs, int rowNum) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires_at");
        return new ReservationRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("trip_id"),
                (UUID) rs.getObject("driver_id"),
                (Integer) rs.getObject("attempt"),
                rs.getString("state"),
                rs.getString("terminal_reason"),
                expires == null ? null : expires.toInstant(),
                rs.getInt("last_command_version"),
                (Double) rs.getObject("pickup_lat"),
                (Double) rs.getObject("pickup_lng"),
                (Double) rs.getObject("destination_lat"),
                (Double) rs.getObject("destination_lng"),
                (Long) rs.getObject("distance_m"),
                (Long) rs.getObject("fare_vnd"));
    }
}
