package com.ridehailing.driver;

import com.ridehailing.driver.service.ProcessedCommandCleanupWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L01 — cleanup processed_commands chạy được trên PostgreSQL thật (trước đây DELETE ... LIMIT lỗi cú pháp)
 * và chỉ xóa row quá hạn.
 * L07 — candidates: LIMIT trong SQL có tie-break id (tập trả về tất định khi nhiều driver trùng tọa độ);
 * bounding box quấn quanh kinh tuyến ±180°.
 * Scheduler của các worker tắt (fixed-delay rất lớn) để test tự gọi và kiểm soát thời điểm.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "processed-command.cleanup.fixed-delay-ms=86400000",
                "reservation.expiry.enabled=false"
        })
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class MaintenanceAndCandidatesIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withUsername("maintenance_test")
            .withPassword("maintenance_test_pw");

    private static final String PUBLIC_KEY_PATH;
    private static final String INTERNAL_TOKEN = "test-internal-token-maintenance";

    static {
        try {
            Path dir = Files.createTempDirectory("jwt-test-keys-maintenance");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            Path pub = dir.resolve("jwt-public.pem");
            Files.writeString(pub, "-----BEGIN PUBLIC KEY-----\n"
                    + java.util.Base64.getEncoder().encodeToString(gen.generateKeyPair().getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
            PUBLIC_KEY_PATH = pub.toString();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jwt.public-key-path", () -> PUBLIC_KEY_PATH);
        registry.add("internal.api-token", () -> INTERNAL_TOKEN);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProcessedCommandCleanupWorker cleanupWorker;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    @AfterEach
    void cleanTables() {
        jdbc.update("DELETE FROM reservations");
        jdbc.update("DELETE FROM processed_commands");
        jdbc.update("DELETE FROM drivers");
        jdbc.update("DELETE FROM vehicles");
    }

    // ---------- L01 ----------

    @Test
    void cleanupDeletesOnlyProcessedCommandsOlderThanRetention() {
        UUID oldCommand = UUID.randomUUID();
        UUID recentCommand = UUID.randomUUID();
        jdbc.update("INSERT INTO processed_commands (command_id, request_hash, stored_response, created_at)"
                + " VALUES (?, 'h', '{}'::jsonb, clock_timestamp() - interval '40 days')", oldCommand);
        jdbc.update("INSERT INTO processed_commands (command_id, request_hash, stored_response, created_at)"
                + " VALUES (?, 'h', '{}'::jsonb, clock_timestamp() - interval '1 day')", recentCommand);

        cleanupWorker.cleanup(); // trước L01: BadSqlGrammarException (DELETE ... LIMIT)

        List<UUID> remaining = jdbc.queryForList("SELECT command_id FROM processed_commands", UUID.class);
        assertThat(remaining).containsExactly(recentCommand);
    }

    // ---------- L07 ----------

    @Test
    void candidatesAreDeterministicWhenManyDriversShareTheSameLocation() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            ids.add(insertAvailableDriver(10.7769, 106.7009));
        }
        List<String> expected = ids.stream().map(UUID::toString).sorted().limit(5).toList();

        for (int round = 0; round < 3; round++) {
            assertThat(candidateIds(10.7769, 106.7009, 5000)).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void candidatesAcrossTheAntimeridianAreFound() throws Exception {
        UUID east = insertAvailableDriver(0.0, 179.99);
        UUID west = insertAvailableDriver(0.0, -179.99); // ~2,2 km từ điểm truy vấn qua kinh tuyến 180°

        assertThat(candidateIds(0.0, 179.995, 5000)).containsExactlyInAnyOrder(east.toString(), west.toString());
        assertThat(candidateIds(0.0, -179.995, 5000)).containsExactlyInAnyOrder(east.toString(), west.toString());
    }

    private UUID insertAvailableDriver(double latitude, double longitude) {
        UUID vehicleId = jdbc.queryForObject(
                "INSERT INTO vehicles (plate_number, vehicle_type) VALUES (?, 'CAR') RETURNING id",
                UUID.class, "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        return jdbc.queryForObject(
                "INSERT INTO drivers (user_id, display_name, vehicle_id, status, latitude, longitude, last_seen_at)"
                        + " VALUES (?, 'Driver', ?, 'AVAILABLE', ?, ?, clock_timestamp()) RETURNING id",
                UUID.class, UUID.randomUUID(), vehicleId, latitude, longitude);
    }

    @SuppressWarnings("unchecked")
    private List<String> candidateIds(double lat, double lng, int radiusM) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/internal/drivers/candidates?lat=" + lat + "&lng=" + lng + "&radiusM=" + radiusM))
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        Map<String, Object> body = JSON.readValue(resp.body().getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) body.get("candidates");
        return candidates.stream().map(c -> (String) c.get("driverId")).toList();
    }
}
