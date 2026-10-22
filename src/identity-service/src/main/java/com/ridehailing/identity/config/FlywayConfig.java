package com.ridehailing.identity.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** T03 — chạy Flyway migration khi service khởi động (Spring Boot 4 không auto-configure Flyway).
 * R04 fix: điều kiện theo app.flyway.enabled (mặc định true) để test security có thể tắt
 * bean này và chạy hoàn toàn không cần DB.
 * N17 (C06): retry có giới hạn quanh flyway.migrate() — DB có thể chưa sẵn sàng lúc khởi động;
 * hết lượt retry thì service dừng với thông báo rõ ràng (fail-fast). */
@Configuration
@ConditionalOnProperty(name = "app.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean(initMethod = "migrateWithRetry")
    public FlywayMigrationRunner flyway(DataSource dataSource,
                                       @Value("${app.flyway.retry-attempts:10}") int retryAttempts,
                                       @Value("${app.flyway.retry-delay-ms:2000}") long retryDelayMs) {
        return new FlywayMigrationRunner(Flyway.configure().dataSource(dataSource).load(), retryAttempts, retryDelayMs);
    }

    /** N17 (C06): bọc flyway.migrate() bằng bounded retry. */
    public static final class FlywayMigrationRunner {
        private final Flyway flyway;
        private final int attempts;
        private final long delayMs;

        public FlywayMigrationRunner(Flyway flyway, int attempts, long delayMs) {
            this.flyway = flyway;
            this.attempts = Math.max(1, attempts);
            this.delayMs = Math.max(0, delayMs);
        }

        public void migrateWithRetry() {
            for (int i = 1; ; i++) {
                try {
                    flyway.migrate();
                    if (i > 1) {
                        log.info("Flyway migration succeeded on attempt {} of {}", i, attempts);
                    }
                    return;
                } catch (Exception e) {
                    if (i >= attempts) {
                        throw new IllegalStateException(
                                "Flyway migration failed after " + attempts + " attempts — database may be unavailable.", e);
                    }
                    log.warn("Flyway migration attempt {} of {} failed ({}); retrying in {} ms",
                            i, attempts, String.valueOf(e.getMessage()), delayMs);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted during Flyway migration retry.", ie);
                    }
                }
            }
        }
    }
}
