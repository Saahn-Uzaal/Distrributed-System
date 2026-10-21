package com.ridehailing.identity.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * N09 — fail fast khi thiếu env password DB: dừng startup với thông báo nêu tên biến,
 * không in giá trị. Chạy ở BeanFactoryPostProcessor (sau khi mọi property source đã có mặt,
 * trước khi bean DataSource được tạo) nên test set spring.datasource.password trực tiếp
 * (H2 probe / Testcontainers) không bị chặn.
 */
@Component
public final class RequiredDbPasswordValidator implements BeanFactoryPostProcessor {

    private static final String ENV_VAR = "IDENTITY_DB_PASSWORD";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        Environment environment = beanFactory.getBean(Environment.class);
        String envValue = System.getenv(ENV_VAR);
        if (envValue != null && !envValue.isBlank()) {
            return;
        }
        try {
            String resolved = environment.getProperty("spring.datasource.password");
            if (resolved != null && !resolved.isBlank() && !resolved.contains("${")) {
                return; // test set password trực tiếp (H2 probe / Testcontainers)
            }
        } catch (RuntimeException ignored) {
            // placeholder chưa resolve được — coi như thiếu env
        }
        throw new IllegalStateException(
                "Missing required environment variable '" + ENV_VAR + "'. Set it before starting this service"
                        + " (see README, section 'Nạp env khi chạy jar').");
    }
}
