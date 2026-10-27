package com.ridehailing.matching.inbox;

import com.ridehailing.matching.event.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * T14 — probe handler (spec §20.4: "dùng handler probe test trước khi có matching thật").
 * Side effect quan sát được của mỗi execution: một row trong {@code matching_probe_events}
 * theo eventId, idempotent bằng ON CONFLICT DO NOTHING (§4.1).
 *
 * <p>T15: KHÔNG còn là bean production — handler thật là {@code MatchingJobHandler}.
 * Class này chỉ dùng trong test (FaultInjectionConfig xây trực tiếp) để acceptance T14
 * vẫn chứng minh "handler chạy đúng một lần" độc lập với matching job.
 */
public class ProbeInboxHandler implements InboxEventHandler {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ProbeInboxHandler(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void handle(EventEnvelope envelope) {
        // JSON text (không byte[]): JDBC bind String → PG cast ::jsonb OK; byte[] → bytea, không cast được.
        String payloadJson;
        try {
            payloadJson = json.writeValueAsString(envelope.payload());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize event payload for eventId=" + envelope.eventId(), e);
        }
        jdbc.update(
                "INSERT INTO matching_probe_events (event_id, event_type, payload) VALUES (?, ?, ?::jsonb) " +
                        "ON CONFLICT (event_id) DO NOTHING",
                envelope.eventId(), envelope.eventType(), payloadJson);
    }
}
