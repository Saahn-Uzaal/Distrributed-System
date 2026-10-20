package com.ridehailing.driver;

import com.ridehailing.driver.service.DriverService;
import com.ridehailing.driver.service.ReservationService;
import com.ridehailing.driver.web.dto.ConfirmRequest;
import com.ridehailing.driver.web.dto.ReserveRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Two real application contexts against the same DB; the first is closed before recovery starts. */
@Testcontainers
class ReservationRestartIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withUsername("driver_restart").withPassword("driver_restart_pw");

    private ConfigurableApplicationContext start(Path publicKey) {
        return new SpringApplication(DriverServiceApplication.class).run(
                "--server.port=0", "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--jwt.public-key-path=" + publicKey,
                "--internal.api-token=restart-test-token",
                "--reservation.expiry.fixed-delay-ms=50");
    }

    private UUID driver(DriverService service) {
        UUID user = UUID.randomUUID();
        UUID id = service.createProfile(user, "Restart Driver", "R-" + user.toString().substring(0, 8), "CAR").driverId();
        service.setAvailability(user, "AVAILABLE");
        return id;
    }

    private void reserve(ReservationService service, UUID id, UUID trip, UUID driver) {
        assertThat(service.reserve(id, new ReserveRequest(UUID.randomUUID(), 1, trip, driver, 1,
                Instant.now().plusSeconds(60), new ReserveRequest.GeoPoint(10.7769, 106.7009),
                new ReserveRequest.GeoPoint(10.8, 106.72), 3309, 32000)).httpStatus()).isEqualTo(201);
    }

    @Test
    void restartRecoversOverdueReservationsAndPreservesConfirmedAssignments() throws Exception {
        Path keyDir = Files.createTempDirectory("driver-restart-public-key-");
        Path publicKey = keyDir.resolve("public.pem");
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
        UUID reserved = UUID.randomUUID(), confirmed = UUID.randomUUID(), trip = UUID.randomUUID();
        UUID freeAfterRestart, remainsBusy;
        try {
            try (var first = start(publicKey)) {
                var drivers = first.getBean(DriverService.class);
                var reservations = first.getBean(ReservationService.class);
                freeAfterRestart = driver(drivers);
                remainsBusy = driver(drivers);
                reserve(reservations, reserved, UUID.randomUUID(), freeAfterRestart);
                reserve(reservations, confirmed, trip, remainsBusy);
                assertThat(reservations.confirm(confirmed,
                        new ConfirmRequest(UUID.randomUUID(), 2, trip, remainsBusy, 1)).httpStatus()).isEqualTo(200);
            }

            // Make persisted deadlines elapse while no application/expiry worker is running.
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                    POSTGRES.getUsername(), POSTGRES.getPassword()));
            jdbc.update("UPDATE reservations SET expires_at=now()-interval '1 second' WHERE id IN (?,?)", reserved, confirmed);
            assertThat(jdbc.queryForObject("SELECT state FROM reservations WHERE id=?", String.class, reserved)).isEqualTo("RESERVED");

            try (var restarted = start(publicKey)) {
                var service = restarted.getBean(ReservationService.class);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!"EXPIRED".equals(service.get(reserved).state()) && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                assertThat(service.get(reserved).state()).isEqualTo("EXPIRED");
                assertThat(service.get(reserved).version()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT status FROM drivers WHERE id=?", String.class, freeAfterRestart)).isEqualTo("AVAILABLE");
                assertThat(service.get(confirmed).state()).isEqualTo("CONFIRMED");
                assertThat(jdbc.queryForMap("SELECT status,current_reservation_id,current_trip_id FROM drivers WHERE id=?", remainsBusy))
                        .containsEntry("status", "BUSY").containsEntry("current_reservation_id", confirmed)
                        .containsEntry("current_trip_id", trip);
            }
        } finally {
            Files.deleteIfExists(publicKey);
            Files.deleteIfExists(keyDir);
        }
    }
}
