package com.ridehailing.trip.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * N09 — fail fast khi thiếu env mật khẩu: dừng startup với thông báo nêu tên biến,
 * không in giá trị. Chạy ở BeanFactoryPostProcessor (sau khi mọi property source đã có mặt,
 * trước khi bean DataSource/ConnectionFactory được tạo) nên test set property trực tiếp
 * (H2 probe / Testcontainers) không bị chặn.
 * T13: thêm RABBITMQ_PASSWORD (outbox publisher); T16: thêm INTERNAL_API_TOKEN
 * (internal API driver-service) — cùng quy tắc, không có giá trị mặc định.
 */
@Component
public final class RequiredDbPasswordValidator implements BeanFactoryPostProcessor {

    /** env var -> property tương ứng trong application.yml. */
    private static final Map<String, String> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put("TRIP_DB_PASSWORD", "spring.datasource.password");
        REQUIRED.put("RABBITMQ_PASSWORD", "spring.rabbitmq.password");
        // T16: token internal API driver-service (api-contracts.md §7-8) — không có default.
        REQUIRED.put("INTERNAL_API_TOKEN", "app.trip.internal-api-token");
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        Environment environment = beanFactory.getBean(Environment.class);
        for (Map.Entry<String, String> entry : REQUIRED.entrySet()) {
            requireSet(environment, entry.getKey(), entry.getValue());
        }
    }

    private static void requireSet(Environment environment, String envVar, String property) {
        String envValue = System.getenv(envVar);
        if (envValue != null && !envValue.isBlank()) {
            return;
        }
        try {
            String resolved = environment.getProperty(property);
            if (resolved != null && !resolved.isBlank() && !resolved.contains("${")) {
                return; // test set property trực tiếp (H2 probe / Testcontainers)
            }
        } catch (RuntimeException ignored) {
            // placeholder chưa resolve được — coi như thiếu env
        }
        throw new IllegalStateException(
                "Missing required environment variable '" + envVar + "'. Set it before starting this service"
                        + " (see README, section 'Nạp env khi chạy jar').");
    }
}
