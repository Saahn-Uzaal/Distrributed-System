package com.ridehailing.identity.logging;

import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L05 — audit log nghiệp vụ (N11) không làm bẩn MDC và không báo SUCCESS cho việc bị rollback.
 * <ul>
 *   <li>Các key MDC (eventType, result, tripId, reservationId...) chỉ tồn tại trong lúc ghi dòng
 *       audit, sau đó khôi phục giá trị trước đó — không rò sang các dòng log sau trên cùng thread
 *       (quan trọng với thread của scheduler, nơi không có CorrelationIdFilter dọn MDC).</li>
 *   <li>Nếu đang trong transaction: dòng audit chỉ được ghi SAU KHI commit thành công;
 *       transaction rollback thì không ghi.</li>
 * </ul>
 */
public final class AuditLog {

    private AuditLog() {
    }

    /** Cặp key/value MDC theo thứ tự: {@code mdc("eventType", "x", "result", "SUCCESS")}. */
    public static Map<String, String> mdc(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put(keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }

    public static void info(Logger log, Map<String, String> mdc, String message, Object... args) {
        Runnable write = () -> withMdc(mdc, () -> log.info(message, args));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write.run();
                }
            });
        } else {
            write.run();
        }
    }

    /** Chạy {@code action} với các key MDC tạm thời rồi khôi phục MDC như trước. */
    public static void withMdc(Map<String, String> mdc, Runnable action) {
        Map<String, String> previous = new HashMap<>();
        for (Map.Entry<String, String> e : mdc.entrySet()) {
            previous.put(e.getKey(), MDC.get(e.getKey()));
            MDC.put(e.getKey(), e.getValue());
        }
        try {
            action.run();
        } finally {
            for (Map.Entry<String, String> e : previous.entrySet()) {
                if (e.getValue() == null) {
                    MDC.remove(e.getKey());
                } else {
                    MDC.put(e.getKey(), e.getValue());
                }
            }
        }
    }
}
