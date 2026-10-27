package com.ridehailing.matchingtest;

import com.ridehailing.matching.inbox.InboxEventHandler;
import com.ridehailing.matching.inbox.ProbeInboxHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T14 — fault injection test-only (spec §13: chỉ bật trong test). Wrapper probe handler: ném
 * TransientInboxException cho N lần đầu theo eventId (FAIL_COUNTS), đếm MỌI invocation (ATTEMPTS)
 * để chứng minh "handler chạy đúng một lần". Không có knob production — bean @Primary này chỉ
 * tồn tại trong context test.
 *
 * <p>T15: class nằm ở package {@code com.ridehailing.matchingtest} (KHÔNG thuộc cây scan của app
 * {@code com.ridehailing.matching}): surefire đặt target/test-classes trên classpath và component
 * scan theo tên package sẽ phát hiện @Configuration test-scope nếu cùng package với app — khi đó
 * bean @Primary probe handler chiếm injection InboxEventHandler trong MỌI context test, kể cả
 * context production-like của MatchingJobIntegrationTest. Tách package + khai báo explicit trong
 * {@code @SpringBootTest(classes=...)} (InboxConsumerIntegrationTest) giữ hành vi T14 nguyên vẹn
 * mà không ảnh hưởng context khác.
 */
@Configuration
public class FaultInjectionConfig {

    public static final Map<UUID, Integer> FAIL_COUNTS = new ConcurrentHashMap<>();
    public static final Map<UUID, Integer> ATTEMPTS = new ConcurrentHashMap<>();

    /** T15: ProbeInboxHandler không còn là bean production (MatchingJobHandler thay thế) —
     *  xây trực tiếp ở đây để acceptance T14 giữ nguyên hành vi probe. */
    @Bean
    @Primary
    InboxEventHandler faultInjectedHandler(JdbcTemplate jdbc, tools.jackson.databind.ObjectMapper json) {
        ProbeInboxHandler probe = new ProbeInboxHandler(jdbc, json);
        return envelope -> {
            int attempt = ATTEMPTS.merge(envelope.eventId(), 1, Integer::sum);
            int failN = FAIL_COUNTS.getOrDefault(envelope.eventId(), 0);
            if (attempt <= failN) {
                throw new TransientInboxException("simulated transient failure " + attempt + "/" + failN);
            }
            probe.handle(envelope);
        };
    }

    static final class TransientInboxException extends RuntimeException {
        TransientInboxException(String message) {
            super(message);
        }
    }
}
