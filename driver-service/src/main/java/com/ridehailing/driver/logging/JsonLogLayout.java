package com.ridehailing.driver.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.LayoutBase;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T07 (R05 fix) — structured JSON log layout theo spec §11: mỗi dòng là một JSON object với
 * timestamp UTC, service, instanceId, level, eventType, correlationId, tripId/reservationId
 * nếu có, result, durationMs. Layout chỉ ghi message + các field MDC đã khai báo —
 * KHÔNG bao giờ tự thêm header request/body; app code không được đặt password/token vào
 * MDC hay message (spec §11 "không log mật khẩu/token").
 */
public class JsonLogLayout extends LayoutBase<ILoggingEvent> {

    /** MDC keys trở thành top-level JSON field khi có mặt (spec §11). */
    private static final List<String> STRUCTURED_MDC_KEYS = List.of(
            "correlationId", "eventType", "result", "durationMs", "tripId", "reservationId");

    private static final ObjectMapper JSON = new ObjectMapper();

    private String service;
    private final String instanceId = resolveInstanceId();

    /** Set từ logback-spring.xml (springProperty spring.application.name). */
    public void setService(String service) {
        this.service = service;
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        // logback 1.5.x: getInstant() trả Instant; format ISO-8601 UTC (kết thúc Z) theo spec §11.
        fields.put("timestamp", event.getInstant().atOffset(ZoneOffset.UTC).toString());
        fields.put("level", event.getLevel().toString());
        fields.put("service", service != null ? service : "unknown");
        fields.put("instanceId", instanceId);
        Map<String, String> mdc = event.getMDCPropertyMap();
        for (String key : STRUCTURED_MDC_KEYS) {
            if (mdc.containsKey(key)) {
                fields.put(key, mdc.get(key));
            }
        }
        fields.put("logger", event.getLoggerName());
        fields.put("message", event.getFormattedMessage());
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            fields.put("exception", throwable.getClassName() + ": " + throwable.getMessage());
        }
        try {
            return JSON.writeValueAsString(fields);
        } catch (Exception e) {
            // Fallback an toàn: layout không bao giờ ném exception.
            return "{\"message\":\"" + escape(event.getFormattedMessage()) + "\"}";
        }
    }

    /** INSTANCE_ID từ env (compose/VM sẽ set per instance); fallback UUID duy nhất per JVM run. */
    private static String resolveInstanceId() {
        String fromEnv = System.getenv("INSTANCE_ID");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return UUID.randomUUID().toString();
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
