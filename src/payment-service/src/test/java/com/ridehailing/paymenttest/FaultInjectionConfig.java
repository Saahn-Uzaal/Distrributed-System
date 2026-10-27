package com.ridehailing.paymenttest;

import com.ridehailing.payment.inbox.InboxEventHandler;
import com.ridehailing.payment.service.TripCompletedHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T23 — fault injection test-only (spec §13: chỉ bật trong test). Wrapper handler thật
 * ({@link TripCompletedHandler}): ném TransientInboxException cho N lần đầu theo eventId
 * (FAIL_COUNTS), đếm MỌI invocation (ATTEMPTS) để chứng minh "handler chạy đúng một lần"
 * và rollback an toàn (không có payments/outbox row dở dang). Không có knob production —
 * bean @Primary này chỉ tồn tại trong context test.
 *
 * <p>Class nằm ở package {@code com.ridehailing.paymenttest} (KHÔNG thuộc cây scan của app
 * {@code com.ridehailing.payment}): surefire đặt target/test-classes trên classpath và component
 * scan theo tên package sẽ phát hiện @Configuration test-scope nếu cùng package với app — khi đó
 * bean @Primary chiếm injection InboxEventHandler trong MỌI context test, kể cả SecuritySmokeTest.
 * Tách package + khai báo explicit trong {@code @SpringBootTest(classes=...)} giữ hành vi an toàn.
 */
@Configuration
public class FaultInjectionConfig {

    public static final Map<UUID, Integer> FAIL_COUNTS = new ConcurrentHashMap<>();
    public static final Map<UUID, Integer> ATTEMPTS = new ConcurrentHashMap<>();

    /** Fail after real writes, so retry verifies rollback of payment + outbox + inbox together. */
    @Bean
    @Primary
    InboxEventHandler faultInjectedHandler(TripCompletedHandler real) {
        return envelope -> {
            int attempt = ATTEMPTS.merge(envelope.eventId(), 1, Integer::sum);
            int failN = FAIL_COUNTS.getOrDefault(envelope.eventId(), 0);
            real.handle(envelope);
            if (attempt <= failN) {
                throw new TransientInboxException("simulated transient failure " + attempt + "/" + failN);
            }
        };
    }

    static final class TransientInboxException extends RuntimeException {
        TransientInboxException(String message) {
            super(message);
        }
    }
}
