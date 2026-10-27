package com.ridehailing.payment.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * T23 — payments (spec §7/§9): một row mỗi (trip_id, attempt), unique index chống thu trùng.
 * Row chỉ commit trong transaction cùng inbox dedup record + outbox PENDING (events.md §4.2).
 */
@Repository
public class PaymentRepository {

    /** Row payment đủ để assert kết quả nghiệp vụ và replay/debug. */
    public record PaymentRow(UUID id, UUID tripId, int attempt, long amountVnd, String currency,
                            String status, String failureCode) {
    }

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ghi row payment với status cuối (SUCCEEDED/FAILED — chốt T23, không có PENDING trung gian). */
    public void insert(UUID id, UUID tripId, int attempt, long amountVnd, String currency,
                       String status, String failureCode, UUID eventId, UUID correlationId) {
        jdbc.update("INSERT INTO payments (id, trip_id, attempt, amount_vnd, currency, status,"
                        + " failure_code, event_id, correlation_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tripId, attempt, amountVnd, currency, status, failureCode, eventId, correlationId);
    }

    /** Guard duplicate business intent (spec §9): đã có payment cho đúng (trip, attempt)? */
    public Optional<PaymentRow> findByTripAndAttempt(UUID tripId, int attempt) {
        List<PaymentRow> rows = jdbc.query(
                "SELECT id, trip_id, attempt, amount_vnd, currency, status, failure_code"
                        + " FROM payments WHERE trip_id = ? AND attempt = ?",
                (rs, i) -> new PaymentRow(
                        rs.getObject("id", UUID.class), rs.getObject("trip_id", UUID.class),
                        rs.getInt("attempt"), rs.getLong("amount_vnd"), rs.getString("currency"),
                        rs.getString("status"), rs.getString("failure_code")),
                tripId, attempt);
        return rows.stream().findFirst();
    }

    /** Số payment row của một trip (test/verify). */
    public int countByTrip(UUID tripId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE trip_id = ?", Integer.class, tripId);
        return n == null ? 0 : n;
    }
}
