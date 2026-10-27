package com.ridehailing.trip.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * T16 — Saga orchestrator row (state-machines.md §2): một saga per trip, phase theo bảng §2.
 * CAS {@code version + phase} guard serialize các intent của cùng trip: hai event/worker
 * đồng thời chỉ một bên thắng update, bên kia nhận 0 rows → RuntimeException → rollback +
 * retry bounded của listener (events.md §4.3).
 */
@Repository
public class SagaRepository {

    /** Row saga đủ để đọc phase/deadline/context và CAS update. */
    public record SagaRow(UUID id, UUID tripId, String phase, String desiredAction, Instant nextRetryAt,
                          int retryCount, Instant deadlineAt, String lastError, String contextJson, int version,
                          String leaseOwner) {
    }

    private final JdbcTemplate jdbc;

    public SagaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** T16 — insert saga WAITING_MATCH khi tạo trip (state-machines.md §1: side effect create).
     *  deadline_at = createdAt + TRIP_MATCH_DEADLINE_SECONDS; context khởi tạo excludedDriverIds rỗng. */
    public UUID insert(UUID tripId, Instant deadlineAt, String contextJson) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sagas (id, trip_id, phase, desired_action, next_retry_at, retry_count,"
                        + " deadline_at, last_error, context, version)"
                        + " VALUES (?, ?, 'WAITING_MATCH', 'NONE', NULL, 0, ?::timestamptz, NULL, ?::jsonb, 0)",
                id, tripId, Timestamp.from(deadlineAt), contextJson);
        return id;
    }

    public Optional<SagaRow> findByTripId(UUID tripId) {
        List<SagaRow> rows = jdbc.query(
                "SELECT id, trip_id, phase, desired_action, next_retry_at, retry_count, deadline_at,"
                        + " last_error, context, version, lease_owner FROM sagas WHERE trip_id = ?",
                (rs, i) -> mapSagaRow(rs),
                tripId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** CAS từ WAITING_MATCH: guard {@code phase='WAITING_MATCH' AND version=expected} —
     *  update thắng trả 1, thua trả 0 (caller ném RuntimeException → rollback + retry). */
    public int casFromWaitingMatch(UUID tripId, int expectedVersion, String newPhase,
                                  Instant nextRetryAt, String lastError, String contextJson) {
        return jdbc.update("UPDATE sagas SET phase = ?, desired_action = 'NONE', next_retry_at = ?,"
                        + " last_error = ?, context = ?::jsonb, version = version + 1, updated_at = clock_timestamp()"
                        + " WHERE trip_id = ? AND phase = 'WAITING_MATCH' AND version = ?",
                newPhase, nextRetryAt == null ? null : Timestamp.from(nextRetryAt), lastError, contextJson,
                tripId, expectedVersion);
    }

    /**
     * T18–T20 — khóa row saga của trip trong transaction hiện tại ({@code FOR UPDATE}).
     * Accept / reject / cancel cùng khóa row này nên chỉ MỘT intent thắng cho mỗi trip; listener T16
     * (CAS theo version) thấy version đã tăng và rollback/retry. Chỉ dùng trong transaction NGẮN —
     * không bao giờ giữ khóa này khi gọi HTTP.
     */
    public Optional<SagaRow> lockByTripId(UUID tripId) {
        List<SagaRow> rows = jdbc.query(
                "SELECT id, trip_id, phase, desired_action, next_retry_at, retry_count, deadline_at,"
                        + " last_error, context, version, lease_owner FROM sagas WHERE trip_id = ? FOR UPDATE",
                (rs, i) -> mapSagaRow(rs),
                tripId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static SagaRow mapSagaRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SagaRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("trip_id"),
                rs.getString("phase"),
                rs.getString("desired_action"),
                rs.getTimestamp("next_retry_at") == null ? null : rs.getTimestamp("next_retry_at").toInstant(),
                rs.getInt("retry_count"),
                rs.getTimestamp("deadline_at") == null ? null : rs.getTimestamp("deadline_at").toInstant(),
                rs.getString("last_error"),
                rs.getString("context"), // jsonb → PGobject; getString() trả JSON text
                rs.getInt("version"),
                rs.getString("lease_owner"));
    }

    /** T18–T20 — cập nhật saga khi ĐANG giữ khóa row ({@link #lockByTripId}); version + 1. */
    public void updateLocked(UUID tripId, String newPhase, String desiredAction, String lastError, String contextJson) {
        int rows = jdbc.update("UPDATE sagas SET phase = ?, desired_action = ?, next_retry_at = NULL,"
                        + " last_error = ?, context = ?::jsonb, version = version + 1, updated_at = clock_timestamp()"
                        + " WHERE trip_id = ?",
                newPhase, desiredAction, lastError, contextJson, tripId);
        if (rows != 1) {
            throw new IllegalStateException("saga row missing for trip " + tripId);
        }
    }

    /** T18–T20 — ghi lỗi tạm thời của intent đang chờ (vẫn giữ phase/desired_action), tăng retry_count. */
    public void recordPendingError(UUID tripId, String lastError) {
        jdbc.update("UPDATE sagas SET last_error = ?, retry_count = retry_count + 1, updated_at = clock_timestamp()"
                + " WHERE trip_id = ?", lastError, tripId);
    }

    /**
     * T21/T22 — saga chưa terminal có việc cần worker xử lý và ĐÃ đến hạn (state-machines.md §2):
     * <ul>
     *   <li>{@code WAITING_MATCH}: deadline tổng tìm tài xế đã qua (matching im lặng/crash);</li>
     *   <li>{@code WAITING_DRIVER}: offer TTL hết hạn theo {@code context.offerExpiresAt} (ghi ở T16/T21);</li>
     *   <li>{@code CONFIRMING}/{@code COMPENSATING}: intent treo chờ reconcile (accept/release chưa rõ)
     *       — phase một mình đã là việc pending;</li>
     *   <li>{@code AWAITING_FINISH} + {@code desired_action = 'FINISH_ASSIGNMENT'}: intent finish treo
     *       sau complete (T22) — trip COMPLETED nhưng driver chưa được giải phóng xác nhận.</li>
     * </ul>
     * Điều kiện chung: {@code next_retry_at IS NULL OR <= now} — sau khi worker claim, row re-arm
     * {@code next_retry_at = now + lease} nên không bị claim lại trong lúc đang xử lý; worker chết
     * giữa chừng thì lease hết hạn và intent được replay idempotent (commandId tất định + CAS).
     * Mọi so sánh thời gian theo đồng hồ DB ({@code clock_timestamp()}) — R04.
     */
    public List<SagaRow> findActionableDue(int limit) {
        return jdbc.query(
                "SELECT id, trip_id, phase, desired_action, next_retry_at, retry_count, deadline_at,"
                        + " last_error, context, version, lease_owner FROM sagas"
                        + " WHERE phase IN ('WAITING_MATCH','WAITING_DRIVER','CONFIRMING','COMPENSATING','AWAITING_FINISH')"
                        + "   AND (next_retry_at IS NULL OR next_retry_at <= clock_timestamp())"
                        + "   AND ("
                        + "        phase IN ('CONFIRMING','COMPENSATING')"
                        + "     OR (phase = 'WAITING_MATCH' AND deadline_at IS NOT NULL AND deadline_at <= clock_timestamp())"
                        + "     OR (phase = 'WAITING_DRIVER' AND context->>'offerExpiresAt' IS NOT NULL"
                        + "         AND (context->>'offerExpiresAt')::timestamptz <= clock_timestamp())"
                        + "     OR (phase = 'AWAITING_FINISH' AND desired_action = 'FINISH_ASSIGNMENT')"
                        + "   )"
                        + " ORDER BY updated_at ASC, id LIMIT ?",
                (rs, i) -> mapSagaRow(rs),
                Math.max(1, limit));
    }

    /**
     * T21 — claim saga trong transaction hiện tại (sau {@link #lockByTripId}): CAS {@code phase + version},
     * ghi {@code lease_owner} và re-arm {@code next_retry_at = now + lease}. Trả true nếu thắng;
     * false khi worker/handler khác đã thay đổi row trước đó.
     */
    public boolean applyLease(UUID tripId, int expectedVersion, String phase, String leaseOwner, long leaseMs) {
        return jdbc.update("UPDATE sagas SET lease_owner = ?,"
                        + " next_retry_at = clock_timestamp() + make_interval(secs => ?),"
                        + " version = version + 1, updated_at = clock_timestamp()"
                        + " WHERE trip_id = ? AND phase = ? AND version = ?",
                leaseOwner, Math.max(0, leaseMs) / 1000.0, tripId, phase, expectedVersion) == 1;
    }

    /** T21 — trả lease sau khi worker xử lý xong (hygiene; không chặn claim vì điều kiện due dùng next_retry_at). */
    public void releaseLease(UUID tripId, String leaseOwner) {
        jdbc.update("UPDATE sagas SET lease_owner = NULL WHERE trip_id = ? AND lease_owner = ?", tripId, leaseOwner);
    }

    /** T19 — deadline tổng tìm tài xế đã qua chưa, theo đồng hồ DB (cùng nguồn với các CAS khác). */
    public boolean isPastDeadline(UUID tripId) {
        Boolean past = jdbc.queryForObject(
                "SELECT deadline_at IS NOT NULL AND deadline_at <= clock_timestamp() FROM sagas WHERE trip_id = ?",
                Boolean.class, tripId);
        return Boolean.TRUE.equals(past);
    }

    /** Re-check the current offer, since a delayed worker may have scanned an older attempt. */
    public boolean isOfferExpired(UUID tripId) {
        Boolean expired = jdbc.queryForObject(
                "SELECT (context->>'offerExpiresAt')::timestamptz <= clock_timestamp()"
                        + " FROM sagas WHERE trip_id = ?", Boolean.class, tripId);
        return Boolean.TRUE.equals(expired);
    }

    /** Số saga row của một trip (test/verify). */
    public int countByTrip(UUID tripId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sagas WHERE trip_id = ?", Integer.class, tripId);
        return n == null ? 0 : n;
    }

    /** Phase hiện tại của saga (test/verify). */
    public String phaseOf(UUID tripId) {
        return jdbc.queryForObject("SELECT phase FROM sagas WHERE trip_id = ?", String.class, tripId);
    }
}
