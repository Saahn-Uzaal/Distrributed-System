package com.ridehailing.payment.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** T03 — chạy Flyway migration khi service khởi động (Spring Boot 4 không auto-configure Flyway).
 * R04 fix: điều kiện theo app.flyway.enabled (mặc định true) để test security có thể tắt
 * bean này và chạy hoàn toàn không cần DB. */
@Configuration
@ConditionalOnProperty(name = "app.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .load();
    }
}
