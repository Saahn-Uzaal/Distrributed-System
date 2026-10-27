package com.ridehailing.payment.inbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * T23 — dedup record inbox (events.md §4.1): unique {@code (consumer_name, event_id)}.
 * Row chỉ được commit trong transaction cùng thay đổi nghiệp vụ (§4.2); attempt thất bại
 * rollback → không còn row → redelivery/retry xử lý lại từ đầu. Mỗi service một consumer
 * name riêng để dedup không trộn giữa các consumer (§1).
 */
@Component
public class InboxEventRepository {

    private final JdbcTemplate jdbc;

    public InboxEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert record nếu chưa có. Trả về true khi row mới được insert (event lần đầu).
     *  Payload là JSON text — JDBC bind String → PG cast {@code ::jsonb} OK
     *  (byte[] sẽ bind thành bytea và KHÔNG cast được sang jsonb). */
    public boolean insertIfAbsent(String consumerName, UUID eventId, String eventType,
                                 UUID correlationId, UUID aggregateId, String payloadJson) {
        int rows = jdbc.update(
                "INSERT INTO inbox_events (consumer_name, event_id, event_type, correlation_id, aggregate_id, payload) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (consumer_name, event_id) DO NOTHING",
                consumerName, eventId, eventType, correlationId, aggregateId, payloadJson);
        return rows == 1;
    }

    /** Số record inbox của một eventId (test/verify). */
    public int countByEventId(UUID eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE event_id = ?", Integer.class, eventId);
        return n == null ? 0 : n;
    }
}
