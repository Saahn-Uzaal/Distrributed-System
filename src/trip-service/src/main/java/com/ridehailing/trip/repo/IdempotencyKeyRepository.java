package com.ridehailing.trip.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

/**
 * T12 — idempotency HTTP cho POST /api/trips (contract §3.3).
 * Pattern "claim trước, ghi sau" bằng ON CONFLICT DO NOTHING: hai request cạnh tranh cùng key
 * chỉ một bên claim thành công; bên kia đọc row đã lưu và replay/mismatch — không cần xử lý exception unique.
 */
@Repository
public class IdempotencyKeyRepository {

    // Replay ownership is scoped by authenticated principal + operation + client key.

    /** Response gốc đã lưu (status HTTP + body JSON) để replay đúng như lần đầu. */
    public record Stored(String requestHash, int status, String bodyJson) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public IdempotencyKeyRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Claim key: 1 nếu insert thành công (chưa ai dùng key này), 0 nếu đã tồn tại. */
    public int claim(UUID principalId, String operation, UUID key, String requestHash, String storedResponseJson) {
        return jdbc.update("INSERT INTO idempotency_keys (principal_id, operation, key, request_hash, stored_response)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb) ON CONFLICT (principal_id, operation, key) DO NOTHING",
                principalId, operation, key, requestHash, storedResponseJson);
    }

    public Optional<Stored> findByKey(UUID principalId, String operation, UUID key) {
        var rows = jdbc.query(
                "SELECT request_hash, stored_response FROM idempotency_keys WHERE principal_id = ? AND operation = ? AND key = ?",
                (rs, rowNum) -> new Stored(rs.getString("request_hash"), readStatus(rs.getString("stored_response")),
                        rs.getString("stored_response")),
                principalId, operation, key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** stored_response JSONB = {"status":<int>,"body":{...}} (chốt T10, áp dụng lại ở đây). */
    private int readStatus(String jsonText) {
        try {
            return json.readTree(jsonText).path("status").asInt();
        } catch (Exception e) {
            throw new IllegalStateException("Stored idempotency response is malformed.", e);
        }
    }
}
