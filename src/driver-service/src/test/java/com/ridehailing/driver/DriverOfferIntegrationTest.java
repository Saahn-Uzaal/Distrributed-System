package com.ridehailing.driver;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T17 — GET /api/drivers/me/offer (contract §3.2) trên HTTP thật + PostgreSQL thật.
 * Reservation tạo qua internal API PUT /internal/reservations/{id} (đúng đường Trip dùng ở T16).
 * Acceptance spec §20.4: chỉ driver được đề nghị xem offer; release/expire không còn offer;
 * thời gian hết hạn hiển thị đúng. Expiry worker TẮT để chứng minh offer hết hạn biến mất theo
 * đồng hồ DB ngay cả khi worker chưa chạy.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"reservation.expiry.enabled=false"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class DriverOfferIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine")
            .withUsername("offer_test")
            .withPassword("offer_test_pw");

    private static final String PUBLIC_KEY_PATH;
    private static final RSASSASigner SIGNER;
    private static final String INTERNAL_TOKEN = "test-internal-token-offer";

    static {
        try {
            Path dir = Files.createTempDirectory("jwt-test-keys-driver-offer");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            Path pub = dir.resolve("jwt-public.pem");
            Files.writeString(pub, "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
            PUBLIC_KEY_PATH = pub.toString();
            SIGNER = new RSASSASigner(pair.getPrivate());
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

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    record Raw(int status, String contentType, String body) {
    }

    /** Driver đã có profile: id profile + user id (JWT sub). */
    record DriverRef(UUID driverId, UUID userId) {
    }

    @AfterEach
    void cleanTables() {
        jdbc.update("DELETE FROM reservations");
        jdbc.update("DELETE FROM processed_commands");
        jdbc.update("DELETE FROM drivers");
        jdbc.update("DELETE FROM vehicles");
    }

    // ---------- helpers ----------

    private static String token(UUID userId, String role) throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", userId.toString());
        claims.put("email", "offer-" + role.toLowerCase() + "@example.com");
        claims.put("role", role);
        claims.put("iss", "ridehailing-identity");
        claims.put("aud", List.of("driver-service"));
        claims.put("iat", now);
        claims.put("exp", now + 3600);
        JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.RS256), new Payload(claims));
        jws.sign(SIGNER);
        return jws.serialize();
    }

    private DriverRef availableDriver() {
        UUID userId = UUID.randomUUID();
        UUID vehicleId = jdbc.queryForObject(
                "INSERT INTO vehicles (plate_number, vehicle_type) VALUES (?, 'MOTORBIKE') RETURNING id",
                UUID.class, "O" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        UUID driverId = jdbc.queryForObject(
                "INSERT INTO drivers (user_id, display_name, vehicle_id, status, latitude, longitude, last_seen_at)"
                        + " VALUES (?, 'Offer Driver', ?, 'AVAILABLE', 10.7769, 106.7009, clock_timestamp()) RETURNING id",
                UUID.class, userId, vehicleId);
        return new DriverRef(driverId, userId);
    }

    private Raw getOffer(String bearer) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/drivers/me/offer")).GET();
        if (bearer != null) {
            req.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Raw(resp.statusCode(), resp.headers().firstValue("Content-Type").orElse(""), resp.body());
    }

    private int internal(String method, String path, Map<String, Object> body) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .header("Content-Type", "application/json");
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        if ("PUT".equals(method)) {
            req.PUT(publisher);
        } else {
            req.POST(publisher);
        }
        return HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static Map<String, Object> point(double lat, double lng) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("latitude", lat);
        p.put("longitude", lng);
        return p;
    }

    /** Reserve qua internal API như Trip (T16). Trả lại body đã gửi để so sánh với offer. */
    private Map<String, Object> reserve(UUID reservationId, UUID tripId, DriverRef driver, Instant expiresAt) throws Exception {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("commandId", UUID.randomUUID().toString());
        cmd.put("commandVersion", 1);
        cmd.put("tripId", tripId.toString());
        cmd.put("driverId", driver.driverId().toString());
        cmd.put("attempt", 2);
        cmd.put("expiresAt", expiresAt.toString());
        cmd.put("pickup", point(10.7769, 106.7009));
        cmd.put("destination", point(10.8, 106.72));
        cmd.put("distanceM", 3309);
        cmd.put("fareVnd", 32000);
        assertThat(internal("PUT", "/internal/reservations/" + reservationId, cmd)).isEqualTo(201);
        return cmd;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String body) throws Exception {
        return JSON.readValue(body.getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
    }

    private static void assertNoOffer(Raw raw) {
        assertThat(raw.status()).isEqualTo(200);
        assertThat(raw.contentType()).startsWith("application/json");
        assertThat(raw.body().trim()).isEqualTo("null"); // contract §3.2: 200 body null, không dùng 404
    }

    // ---------- tests ----------

    /** Driver được đề nghị thấy đúng dữ liệu chuyến từ reservation local; expiresAt đúng giá trị Trip gửi. */
    @Test
    void offeredDriverSeesOfferFromLocalReservation() throws Exception {
        DriverRef driver = availableDriver();
        UUID tripId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        reserve(UUID.randomUUID(), tripId, driver, expiresAt);

        Raw raw = getOffer(token(driver.userId(), "DRIVER"));
        assertThat(raw.status()).isEqualTo(200);
        Map<String, Object> offer = parseObject(raw.body());
        assertThat(offer.keySet()).containsExactly(
                "tripId", "attempt", "pickup", "destination", "distanceM", "fareVnd", "expiresAt");
        assertThat(offer.get("tripId")).isEqualTo(tripId.toString());
        assertThat(((Number) offer.get("attempt")).intValue()).isEqualTo(2);
        assertThat(offer.get("pickup")).isEqualTo(Map.of("latitude", 10.7769, "longitude", 106.7009));
        assertThat(offer.get("destination")).isEqualTo(Map.of("latitude", 10.8, "longitude", 106.72));
        assertThat(((Number) offer.get("distanceM")).longValue()).isEqualTo(3309);
        assertThat(((Number) offer.get("fareVnd")).longValue()).isEqualTo(32000);
        String expires = (String) offer.get("expiresAt");
        assertThat(expires).endsWith("Z"); // ISO-8601 UTC (contract §1)
        assertThat(Instant.parse(expires)).isEqualTo(expiresAt);
    }

    /** Driver khác (không được đề nghị) chỉ thấy null; passenger bị 403; thiếu token 401. */
    @Test
    void onlyTheOfferedDriverSeesTheOffer() throws Exception {
        DriverRef offered = availableDriver();
        DriverRef other = availableDriver();
        reserve(UUID.randomUUID(), UUID.randomUUID(), offered, Instant.now().plusSeconds(30));

        assertNoOffer(getOffer(token(other.userId(), "DRIVER")));
        assertThat(getOffer(token(offered.userId(), "PASSENGER")).status()).isEqualTo(403);
        assertThat(getOffer(null).status()).isEqualTo(401);
        assertThat(getOffer(token(offered.userId(), "DRIVER")).body()).contains("tripId");
    }

    @Test
    void driverWithoutProfileGets404() throws Exception {
        Raw raw = getOffer(token(UUID.randomUUID(), "DRIVER"));
        assertThat(raw.status()).isEqualTo(404);
        assertThat(parseObject(raw.body()).get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void driverWithoutReservationGetsNull() throws Exception {
        DriverRef driver = availableDriver();
        assertNoOffer(getOffer(token(driver.userId(), "DRIVER")));
    }

    /** Release (REJECTED) -> offer biến mất. */
    @Test
    void releasedOfferDisappears() throws Exception {
        DriverRef driver = availableDriver();
        UUID reservationId = UUID.randomUUID();
        reserve(reservationId, UUID.randomUUID(), driver, Instant.now().plusSeconds(30));
        assertThat(getOffer(token(driver.userId(), "DRIVER")).body()).contains("tripId");

        Map<String, Object> release = new LinkedHashMap<>();
        release.put("commandId", UUID.randomUUID().toString());
        release.put("commandVersion", 2);
        release.put("reason", "REJECTED");
        assertThat(internal("POST", "/internal/reservations/" + reservationId + "/release", release)).isEqualTo(200);

        assertNoOffer(getOffer(token(driver.userId(), "DRIVER")));
    }

    /** Hết hạn -> không còn offer NGAY theo đồng hồ DB, dù expiry worker (đang tắt) chưa chuyển row sang EXPIRED. */
    @Test
    void expiredOfferDisappearsEvenBeforeExpiryWorkerRuns() throws Exception {
        DriverRef driver = availableDriver();
        UUID reservationId = UUID.randomUUID();
        reserve(reservationId, UUID.randomUUID(), driver, Instant.now().plusMillis(1500));
        assertThat(getOffer(token(driver.userId(), "DRIVER")).body()).contains("tripId");

        Thread.sleep(2_000);
        assertNoOffer(getOffer(token(driver.userId(), "DRIVER")));
        // Row vẫn RESERVED (worker tắt) — chứng minh offer bị ẩn bởi điều kiện thời gian, không phải nhờ worker.
        assertThat(jdbc.queryForObject("SELECT state FROM reservations WHERE id = ?", String.class, reservationId))
                .isEqualTo("RESERVED");
    }

    /** Đã confirm (driver nhận chuyến) -> reservation CONFIRMED không còn là offer. */
    @Test
    void confirmedReservationIsNoLongerAnOffer() throws Exception {
        DriverRef driver = availableDriver();
        UUID reservationId = UUID.randomUUID();
        UUID tripId = UUID.randomUUID();
        reserve(reservationId, tripId, driver, Instant.now().plusSeconds(30));

        Map<String, Object> confirm = new LinkedHashMap<>();
        confirm.put("commandId", UUID.randomUUID().toString());
        confirm.put("commandVersion", 2);
        confirm.put("tripId", tripId.toString());
        confirm.put("driverId", driver.driverId().toString());
        confirm.put("attempt", 2);
        assertThat(internal("POST", "/internal/reservations/" + reservationId + "/confirm", confirm)).isEqualTo(200);

        assertNoOffer(getOffer(token(driver.userId(), "DRIVER")));
    }

    /** GET offer là thao tác chỉ đọc: không đổi heartbeat/state của driver. */
    @Test
    void readingOfferDoesNotTouchDriverState() throws Exception {
        DriverRef driver = availableDriver();
        reserve(UUID.randomUUID(), UUID.randomUUID(), driver, Instant.now().plusSeconds(30));
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT status, last_seen_at, version FROM drivers WHERE id = ?", driver.driverId());

        getOffer(token(driver.userId(), "DRIVER"));

        Map<String, Object> after = jdbc.queryForMap(
                "SELECT status, last_seen_at, version FROM drivers WHERE id = ?", driver.driverId());
        assertThat(after).isEqualTo(before);
    }
}
