package com.ridehailing.identity;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.ridehailing.identity.logging.JsonLogLayout;
import com.ridehailing.identity.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T06 — integration test trên HTTP thật + PostgreSQL thật (Testcontainers, cần Docker).
 * F01 theo spec mục 13: register/login đúng, hash password, token hợp lệ, role đúng.
 * Đợt sửa 2026-10-19 (brief T01–T06): thêm regression tests cho
 *   F01 — temporal claims (exp hết hạn / thiếu exp / nbf tương lai) + sai iss/aud/sub/role -> 401,
 *          token ký thật bằng private key test (không sửa payload giữ chữ ký cũ);
 *   F03 — 12 register đồng thời cùng email: đúng 1×201, 11×409, không 500; DB chỉ 1 user;
 *   F04 — role thiếu/null -> 400 (không ghi DB), password > 72 byte UTF-8 -> 400 register /
 *          401 login cùng message (không lộ email tồn tại), fullName validate sau trim;
 *   F05 — tamper signature thật: decode byte chữ ký, lật 1 bit, assert byte đã đổi trước khi gọi endpoint.
 * Ghi chú: Spring Boot 4 đã bỏ TestRestTemplate/@AutoConfigureMockMvc -> dùng java.net.http.HttpClient.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthFlowIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine") // F32: pin patch, cùng tag với compose
            .withUsername("identity_test")
            .withPassword("identity_test_pw");

    private static final Path KEYS_DIR;
    private static final String PRIVATE_KEY_PATH;
    private static final String PUBLIC_KEY_PATH;

    static {
        try {
            KEYS_DIR = Files.createTempDirectory("jwt-test-keys");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            Path priv = KEYS_DIR.resolve("jwt-private.pem");
            Path pub = KEYS_DIR.resolve("jwt-public.pem");
            Files.writeString(priv, "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n");
            Files.writeString(pub, "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
            PRIVATE_KEY_PATH = priv.toString();
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
        registry.add("jwt.private-key-path", () -> PRIVATE_KEY_PATH);
        registry.add("jwt.public-key-path", () -> PUBLIC_KEY_PATH);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    private String base() { return "http://localhost:" + port; }

    @Autowired
    JdbcTemplate jdbc;

    // Mỗi test dùng email riêng (UUID suffix) — không phụ thuộc thứ tự test hay dữ liệu lần chạy trước.
    private static final String PASSWORD = "secret123";

    record Resp(int status, Map<String, Object> body) {
    }

    private Resp post(String path, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(
                        JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), parse(resp.body()));
    }

    private Resp get(String path, String bearerToken) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + path))
                .GET();
        if (bearerToken != null) {
            req.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), parse(resp.body()));
    }

    private static Map<String, Object> parse(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return new LinkedHashMap<>();
        }
        return JSON.readValue(body, LinkedHashMap.class);
    }

    /** F01 — ký token thật bằng private key test với claims tùy chỉnh (không sửa payload giữ chữ ký cũ). */
    private String signToken(Map<String, Object> claims) throws Exception {
        return signPayload(new Payload(claims));
    }

    private String signPayload(Payload payload) throws Exception {
        RSASSASigner signer = new RSASSASigner((PrivateKey) JwtService.loadPrivateKey(Path.of(PRIVATE_KEY_PATH)));
        JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.RS256), payload);
        jws.sign(signer);
        return jws.serialize();
    }

    private Map<String, Object> validClaims(String userId) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", userId);
        claims.put("email", "claimtest@example.com");
        claims.put("role", "PASSENGER");
        claims.put("iss", JwtService.ISSUER);
        claims.put("aud", JwtService.AUDIENCE);
        claims.put("exp", Instant.now().getEpochSecond() + 3600);
        return claims;
    }

    private String registerUser(String email) throws Exception {
        Resp reg = post("/api/auth/register", new RegisterBody(email, PASSWORD, "Test User", "PASSENGER"));
        assertThat(reg.status()).isEqualTo(201);
        return (String) ((Map<String, Object>) reg.body().get("user")).get("id");
    }

    private void assert401Unauthorized(String token) throws Exception {
        Resp r = get("/api/auth/me", token);
        assertThat(r.status()).isEqualTo(401);
        assertThat((String) r.body().get("code")).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void registerLoginAndMeFlow() throws Exception {
        String email = "flow-" + UUID.randomUUID() + "@example.com";
        Resp reg = post("/api/auth/register", new RegisterBody(email, PASSWORD, "Passenger One", "PASSENGER"));
        assertThat(reg.status()).isEqualTo(201);
        Map<String, Object> user = (Map<String, Object>) reg.body().get("user");
        assertThat(user.get("email")).isEqualTo(email);
        assertThat(user.get("fullName")).isEqualTo("Passenger One");
        assertThat(user.get("role")).isEqualTo("PASSENGER");
        String userId = (String) user.get("id");

        Resp login = post("/api/auth/login", new LoginBody(email, PASSWORD));
        assertThat(login.status()).isEqualTo(200);
        String token = (String) login.body().get("accessToken");
        assertThat(token).isNotBlank();
        assertThat(((Number) login.body().get("expiresIn")).longValue()).isEqualTo(3600L);

        Resp me = get("/api/auth/me", token);
        assertThat(me.status()).isEqualTo(200);
        assertThat((String) me.body().get("id")).isEqualTo(userId);
        assertThat(me.body().get("email")).isEqualTo(email);
        assertThat(me.body().get("fullName")).isEqualTo("Passenger One");
        assertThat(me.body().get("role")).isEqualTo("PASSENGER");
    }

    @Test
    void duplicateEmailRejected() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        post("/api/auth/register", new RegisterBody(email, PASSWORD, "First User", "DRIVER"));
        Resp dup = post("/api/auth/register", new RegisterBody(email, "otherPass9", "Second User", "PASSENGER"));
        assertThat(dup.status()).isEqualTo(409);
        assertThat((String) dup.body().get("code")).isEqualTo("EMAIL_TAKEN");
    }

    @Test
    void wrongCredentialsRejectedWithoutLeakingWhichField() throws Exception {
        String email = "creds-" + UUID.randomUUID() + "@example.com";
        post("/api/auth/register", new RegisterBody(email, PASSWORD, "Passenger One", "PASSENGER"));
        Resp badPassword = post("/api/auth/login", new LoginBody(email, "wrong-pass-1"));
        assertThat(badPassword.status()).isEqualTo(401);
        assertThat((String) badPassword.body().get("code")).isEqualTo("INVALID_CREDENTIALS");

        Resp unknownEmail = post("/api/auth/login", new LoginBody("nobody-" + UUID.randomUUID() + "@example.com", PASSWORD));
        assertThat(unknownEmail.status()).isEqualTo(401);
        // Cùng message cho email sai và password sai — không lộ chi tiết (contract §3.1)
        assertThat((String) unknownEmail.body().get("message"))
                .isEqualTo((String) badPassword.body().get("message"));
    }

    @Test
    void noPlaintextPasswordInDatabase() throws Exception {
        String email = "dbcheck-" + UUID.randomUUID() + "@example.com";
        post("/api/auth/register", new RegisterBody(email, PASSWORD, "Passenger One", "PASSENGER"));
        List<String> hashes = jdbc.queryForList("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(hashes).hasSize(1);
        assertThat(hashes.get(0)).startsWith("$2"); // BCrypt prefix
        assertThat(hashes.get(0)).doesNotContain(PASSWORD);

        Resp login = post("/api/auth/login", new LoginBody(email, PASSWORD));
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.body().keySet()).containsExactlyInAnyOrder("accessToken", "expiresIn", "user");
        assertThat(((Map<String, Object>) login.body().get("user")).keySet()).doesNotContain("passwordHash");
    }

    @Test
    void meRequiresValidToken() throws Exception {
        Resp noToken = get("/api/auth/me", null);
        assertThat(noToken.status()).isEqualTo(401);
        assertThat((String) noToken.body().get("code")).isEqualTo("UNAUTHORIZED");

        String email = "me-" + UUID.randomUUID() + "@example.com";
        post("/api/auth/register", new RegisterBody(email, PASSWORD, "Passenger One", "PASSENGER"));
        Resp login = post("/api/auth/login", new LoginBody(email, PASSWORD));
        String token = (String) login.body().get("accessToken");

        // F05 — tamper signature THẬT: decode phần signature thành byte array, lật 1 bit của một byte
        // thực, encode lại base64url không padding. (Đổi ký tự base64 cuối có thể chỉ đổi padding bits
        // -> byte chữ ký không đổi, test cũ vì vậy không ổn định.)
        String[] parts = token.split("\\.");
        byte[] origSig = Base64.getUrlDecoder().decode(parts[2]);
        byte[] flipped = origSig.clone();
        flipped[0] ^= 0x01;
        String tampered = parts[0] + "." + parts[1] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(flipped);
        // Assert byte chữ ký thực sự đã đổi (so với bản gốc) trước khi gọi endpoint.
        byte[] decodedTampered = Base64.getUrlDecoder().decode(tampered.substring(tampered.lastIndexOf('.') + 1));
        assertThat(Arrays.equals(origSig, decodedTampered)).isFalse();

        Resp bad = get("/api/auth/me", tampered);
        assertThat(bad.status()).isEqualTo(401);

        Resp garbage = get("/api/auth/me", "not-a-jwt");
        assertThat(garbage.status()).isEqualTo(401);
    }

    /** F01 — token ký đúng chữ ký nhưng sai temporal claims / iss / aud / sub / role đều phải 401. */
    @Test
    void temporalAndClaimValidationAllReturn401() throws Exception {
        String userId = registerUser("jwtval-" + UUID.randomUUID() + "@example.com");
        long now = Instant.now().getEpochSecond();

        Map<String, Object> base = new LinkedHashMap<>();
        base.put("sub", userId);
        base.put("email", "claimtest@example.com");
        base.put("role", "PASSENGER");
        base.put("iss", JwtService.ISSUER);
        base.put("aud", JwtService.AUDIENCE);

        // exp hết hạn (ký đúng chữ ký, exp trong quá khứ)
        Map<String, Object> c = new LinkedHashMap<>(base);
        c.put("iat", now - 7200);
        c.put("exp", now - 3600);
        assert401Unauthorized(signToken(c));

        // nbf ở tương lai (chưa hiệu lực)
        c = new LinkedHashMap<>(base);
        c.put("iat", now);
        c.put("nbf", now + 3600);
        c.put("exp", now + 7200);
        assert401Unauthorized(signToken(c));

        // thiếu exp
        c = new LinkedHashMap<>(base);
        c.put("iat", now);
        assert401Unauthorized(signToken(c));

        // aud không chứa identity-service
        c = new LinkedHashMap<>(base);
        c.put("aud", List.of("other-service"));
        c.put("exp", now + 3600);
        assert401Unauthorized(signToken(c));

        // aud sai kiểu (string thay vì mảng) — không được thành 500
        c = new LinkedHashMap<>(base);
        c.put("aud", "identity-service");
        c.put("exp", now + 3600);
        assert401Unauthorized(signToken(c));

        // sai issuer
        c = new LinkedHashMap<>(base);
        c.put("iss", "other-issuer");
        c.put("exp", now + 3600);
        assert401Unauthorized(signToken(c));

        // sub không phải UUID
        c = new LinkedHashMap<>(base);
        c.put("sub", "not-a-uuid");
        c.put("exp", now + 3600);
        assert401Unauthorized(signToken(c));

        // role ngoài enum
        c = new LinkedHashMap<>(base);
        c.put("role", "ADMIN");
        c.put("exp", now + 3600);
        assert401Unauthorized(signToken(c));
    }

    @Test
    void signedPayloadMustBeAJsonObject() throws Exception {
        for (String rawPayload : List.of("null", "not-json", "42", "[]")) {
            assert401Unauthorized(signPayload(new Payload(rawPayload)));
        }
    }

    @Test
    void audienceMustContainOnlyStrings() throws Exception {
        String userId = registerUser("audtypes-" + UUID.randomUUID() + "@example.com");
        Map<String, Object> claims = validClaims(userId);
        claims.put("aud", List.of("identity-service", 123));
        assert401Unauthorized(signToken(claims));
        claims.put("aud", Arrays.asList("identity-service", null));
        assert401Unauthorized(signToken(claims));
    }

    @Test
    void expirationAtCurrentSecondIsAlreadyExpired() throws Exception {
        String userId = registerUser("expboundary-" + UUID.randomUUID() + "@example.com");
        Map<String, Object> claims = validClaims(userId);
        for (int attempt = 0; attempt < 5; attempt++) {
            long expiration = Instant.now().getEpochSecond();
            claims.put("exp", expiration);
            Resp response = get("/api/auth/me", signToken(claims));
            // Only assess the equality boundary if the request completed in that same second.
            // A request crossing the second boundary would also pass with the old '<' check.
            if (Instant.now().getEpochSecond() == expiration) {
                assertThat(response.status()).isEqualTo(401);
                assertThat(response.body().get("code")).isEqualTo("UNAUTHORIZED");
                return;
            }
        }
        fail("Could not exercise the expiration equality boundary within one second.");
    }

    /** F03 — 12 register đồng thời cùng email (barrier + PostgreSQL thật): đúng 1×201, 11×409, không 500. */
    @Test
    void concurrentRegisterSameEmailExactlyOneWins() throws Exception {
        String email = "conc-" + UUID.randomUUID() + "@example.com";
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    ready.await();
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
                Resp r = post("/api/auth/register", new RegisterBody(email, PASSWORD, "Conc User", "PASSENGER"));
                return r.status();
            }));
        }
        go.countDown(); // mọi request xuất phát đồng thời từ barrier
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long ok201 = statuses.stream().filter(s -> s == 201).count();
        long ok409 = statuses.stream().filter(s -> s == 409).count();
        assertThat(ok201).isEqualTo(1);
        assertThat(ok409).isEqualTo(n - 1L);
        assertThat(statuses).allSatisfy(s -> {
            if (s != 201 && s != 409) {
                fail("unexpected status " + s + " in concurrent register");
            }
        });

        // DB chỉ có một user cho email đó
        List<Integer> counts = jdbc.queryForList(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(counts.get(0)).isEqualTo(1);
    }

    /** F04 — role thiếu/null trả 400 và không ghi user vào DB. */
    @Test
    void missingOrNullRoleRejectedWithoutPersistingUser() throws Exception {
        String email = "role-" + UUID.randomUUID() + "@example.com";

        Map<String, Object> noRole = new LinkedHashMap<>();
        noRole.put("email", email);
        noRole.put("password", PASSWORD);
        noRole.put("fullName", "No Role");
        Resp r1 = post("/api/auth/register", noRole);
        assertThat(r1.status()).isEqualTo(400);
        assertThat((String) r1.body().get("code")).isEqualTo("INVALID_INPUT");

        Map<String, Object> nullRole = new LinkedHashMap<>(noRole);
        nullRole.put("role", null);
        Resp r2 = post("/api/auth/register", nullRole);
        assertThat(r2.status()).isEqualTo(400);
        assertThat((String) r2.body().get("code")).isEqualTo("INVALID_INPUT");

        List<Integer> counts = jdbc.queryForList(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(counts.get(0)).isEqualTo(0);
    }

    /** F04 — password enforce theo byte UTF-8: 73 ASCII bytes và 25×'ạ' (75B) -> 400; 24×'ạ' (72B) -> 201. */
    @Test
    void passwordByteLimitEnforcedAtBoundary() throws Exception {
        String email = "pw-" + UUID.randomUUID() + "@example.com";

        Resp ascii73 = post("/api/auth/register", new RegisterBody(email, "a".repeat(73), "Pw User", "PASSENGER"));
        assertThat(ascii73.status()).isEqualTo(400);
        assertThat((String) ascii73.body().get("code")).isEqualTo("INVALID_INPUT");

        Resp viet25 = post("/api/auth/register", new RegisterBody(email, "ạ".repeat(25), "Pw User", "PASSENGER"));
        assertThat(viet25.status()).isEqualTo(400);
        assertThat((String) viet25.body().get("code")).isEqualTo("INVALID_INPUT");

        Resp viet24 = post("/api/auth/register", new RegisterBody(email, "ạ".repeat(24), "Pw User", "PASSENGER"));
        assertThat(viet24.status()).isEqualTo(201);

        List<Integer> counts = jdbc.queryForList(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(counts.get(0)).isEqualTo(1);
    }

    /** F04 — login password > 72 byte: không 500, cùng message cho email tồn tại / không tồn tại. */
    @Test
    void loginWithOverlongPasswordNo500AndSameMessage() throws Exception {
        String email = "loginpw-" + UUID.randomUUID() + "@example.com";
        registerUser(email);

        String longPw = "x".repeat(80); // 80 byte > 72
        Resp existing = post("/api/auth/login", new LoginBody(email, longPw));
        assertThat(existing.status()).isEqualTo(401);
        assertThat((String) existing.body().get("code")).isEqualTo("INVALID_CREDENTIALS");

        Resp unknown = post("/api/auth/login",
                new LoginBody("unknown-" + UUID.randomUUID() + "@example.com", longPw));
        assertThat(unknown.status()).isEqualTo(401);
        assertThat((String) unknown.body().get("message")).isEqualTo((String) existing.body().get("message"));

        Resp wrong = post("/api/auth/login", new LoginBody(email, "wrong-pass-1"));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat((String) wrong.body().get("message")).isEqualTo((String) existing.body().get("message"));
    }

    /** F04 — fullName validate 2–100 ký tự SAU khi trim; " a" không được thành tài khoản tên một ký tự. */
    @Test
    void fullNameTrimmedBeforeLengthValidation() throws Exception {
        String email = "name-" + UUID.randomUUID() + "@example.com";

        Resp oneCharAfterTrim = post("/api/auth/register", new RegisterBody(email, PASSWORD, " a", "PASSENGER"));
        assertThat(oneCharAfterTrim.status()).isEqualTo(400);
        assertThat((String) oneCharAfterTrim.body().get("code")).isEqualTo("INVALID_INPUT");

        Resp tooLong = post("/api/auth/register", new RegisterBody(email, PASSWORD, "a".repeat(101), "PASSENGER"));
        assertThat(tooLong.status()).isEqualTo(400);

        List<Integer> counts = jdbc.queryForList(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(counts.get(0)).isEqualTo(0);
    }

    @Test
    void fullNameMaximumLengthAppliesAfterTrimming() throws Exception {
        String email = "namemax-" + UUID.randomUUID() + "@example.com";
        String name = "a".repeat(100);
        Resp registered = post("/api/auth/register",
                new RegisterBody(email, PASSWORD, "  " + name + "  ", "PASSENGER"));
        assertThat(registered.status()).isEqualTo(201);
        assertThat(((Map<String, Object>) registered.body().get("user")).get("fullName"))
                .isEqualTo(name);
        assertThat(jdbc.queryForObject("SELECT full_name FROM users WHERE email = ?", String.class, email))
                .isEqualTo(name);

        Resp tooLong = post("/api/auth/register",
                new RegisterBody("too-" + email, PASSWORD, "  " + name + "a  ", "PASSENGER"));
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.body().get("code")).isEqualTo("INVALID_INPUT");
    }

    @Test
    void invalidRoleRejected() throws Exception {
        Resp admin = post("/api/auth/register", new RegisterBody("admintry-" + UUID.randomUUID() + "@example.com", PASSWORD, "Admin Try", "ADMIN"));
        assertThat(admin.status()).isEqualTo(400);
        assertThat((String) admin.body().get("code")).isEqualTo("INVALID_INPUT");
    }

    /** F17 — password toàn whitespace bị @NotBlank chặn (frontend javaTrim khớp semantics này). */
    @Test
    void whitespaceOnlyPasswordRejectedByNotBlank() throws Exception {
        String email = "ws-" + UUID.randomUUID() + "@example.com";
        Resp r = post("/api/auth/register", new RegisterBody(email, " ".repeat(12), "Ws User", "PASSENGER"));
        assertThat(r.status()).isEqualTo(400);
        assertThat((String) r.body().get("code")).isEqualTo("INVALID_INPUT");

        List<Integer> counts = jdbc.queryForList(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(counts.get(0)).isEqualTo(0);
    }

    /** F17 — NBSP (U+00A0) KHÔNG bị Java String.trim() bỏ -> fullName đệm NBSP được chấp nhận và lưu nguyên. */
    @Test
    void nbspPaddedFullNameAcceptedBecauseJavaTrimKeepsNbsp() throws Exception {
        String email = "nbsp-" + UUID.randomUUID() + "@example.com";
        String name = "\u00A0Nguyễn Văn A\u00A0";
        Resp r = post("/api/auth/register", new RegisterBody(email, PASSWORD, name, "PASSENGER"));
        assertThat(r.status()).isEqualTo(201);
        assertThat(((Map<String, Object>) r.body().get("user")).get("fullName")).isEqualTo(name);
        assertThat(jdbc.queryForObject("SELECT full_name FROM users WHERE email = ?", String.class, email))
                .isEqualTo(name);
    }

    /** F17 — U+001F (<= U+0020) bị Java trim() bỏ: "\u001FA\u001F" -> "A" (1 ký tự) bị từ chối. */
    @Test
    void controlCharPaddedFullNameTrimmedByJava() throws Exception {
        String email = "ctrl-" + UUID.randomUUID() + "@example.com";
        Resp one = post("/api/auth/register", new RegisterBody(email, PASSWORD, "\u001FA\u001F", "PASSENGER"));
        assertThat(one.status()).isEqualTo(400);
        assertThat((String) one.body().get("code")).isEqualTo("INVALID_INPUT");

        Resp two = post("/api/auth/register", new RegisterBody(email, PASSWORD, "\u001FAB\u001F", "PASSENGER"));
        assertThat(two.status()).isEqualTo(201); // sau trim -> "AB" (2 ký tự)
    }

    /** F05 (T06-1): fullName chứa ký tự điều khiển \u0000 -> 400 INVALID_INPUT, DB không đổi. */
    @Test
    void controlCharInFullNameRejectedWithoutPersisting() throws Exception {
        String email = "ctrlchar-" + UUID.randomUUID() + "@example.com";
        Resp r = post("/api/auth/register", new RegisterBody(email, PASSWORD, "ab\u0000c", "PASSENGER"));
        assertThat(r.status()).isEqualTo(400);
        assertThat((String) r.body().get("code")).isEqualTo("INVALID_INPUT");

        List<Integer> counts = jdbc.queryForList(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(counts.get(0)).isEqualTo(0); // DB không đổi
    }

    /** Q01 (T07-13): chạy luồng register/login THẬT qua HTTP rồi quét log đã ghi —
     *  không có password mẫu, JWT token thật hay chuỗi "Bearer " trong bất kỳ dòng log nào. */
    @Test
    void registerLoginFlowLogsContainNoSecrets() throws Exception {
        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = lc.getLogger(Logger.ROOT_LOGGER_NAME);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(lc);
        appender.setName("SECRET_SCAN");
        appender.setOutputStream(out);
        JsonLogLayout layout = new JsonLogLayout();
        layout.setService("identity-service");
        appender.setLayout(layout);
        appender.start();
        root.addAppender(appender);
        try {
            String email = "logscan-" + UUID.randomUUID() + "@example.com";
            Resp reg = post("/api/auth/register", new RegisterBody(email, PASSWORD, "Log Scan User", "PASSENGER"));
            assertThat(reg.status()).isEqualTo(201);
            Resp login = post("/api/auth/login", new LoginBody(email, PASSWORD));
            assertThat(login.status()).isEqualTo(200);
            String token = (String) login.body().get("accessToken");
            assertThat(token).isNotBlank();

            String logs = out.toString(StandardCharsets.UTF_8);
            // Luồng thật đã được ghi log (dòng http_request của register + login).
            assertThat(logs).contains("/api/auth/register").contains("/api/auth/login");
            // Không secret trong bất kỳ dòng log: password mẫu, JWT token thật, "Bearer ".
            assertThat(logs).doesNotContain(PASSWORD);
            assertThat(logs).doesNotContain(token);
            assertThat(logs).doesNotContain("Bearer ");
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    /** N03 (T07-11): body > 64 KiB -> 413 PAYLOAD_TOO_LARGE JSON, correlationId echo. */
    @Test
    void oversizedBodyReturns413PayloadTooLarge() throws Exception {
        String correlation = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", "oversize-" + UUID.randomUUID() + "@example.com");
        body.put("password", PASSWORD);
        body.put("fullName", "a".repeat(70_000)); // > 64 KiB
        body.put("role", "PASSENGER");
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/api/auth/register"))
                .header("Content-Type", "application/json")
                .header("X-Correlation-ID", correlation)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        Resp r = new Resp(resp.statusCode(), parse(resp.body()));
        assertThat(r.status()).isEqualTo(413);
        assertThat((String) r.body().get("code")).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat((String) r.body().get("correlationId")).isEqualTo(correlation);
    }

    /** R03 (T06-4): login với email KHÔNG tồn tại vẫn chạy BCrypt (dummy hash) —
     *  thời gian xử lý tương đương login sai password của user tồn tại. */
    @Test
    void loginWithMissingEmailTakesComparableTimeToWrongPassword() throws Exception {
        String existingEmail = "timing-" + UUID.randomUUID() + "@example.com";
        registerUser(existingEmail);
        String missingEmail = "missing-" + UUID.randomUUID() + "@example.com";

        int n = 20;
        long totalExisting = 0;
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            post("/api/auth/login", new LoginBody(existingEmail, "wrong-password-1"));
            totalExisting += System.nanoTime() - t0;
        }
        long totalMissing = 0;
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            post("/api/auth/login", new LoginBody(missingEmail, "wrong-password-1"));
            totalMissing += System.nanoTime() - t0;
        }

        double avgExistingMs = totalExisting / 1e6 / n;
        double avgMissingMs = totalMissing / 1e6 / n;
        // Trước R03: missing bỏ qua BCrypt -> nhanh hơn ~70 ms. Sau fix: cùng chạy BCrypt.
        assertThat(avgMissingMs).isGreaterThanOrEqualTo(0.4 * avgExistingMs);
    }

    /** F17 — email > 254 ký tự bị @Size(max=254) chặn. */
    @Test
    void emailLongerThan254Rejected() throws Exception {
        String longEmail = "user-" + "a".repeat(250) + "@example.com"; // 269 ký tự > 254
        Resp r = post("/api/auth/register", new RegisterBody(longEmail, PASSWORD, "Long Mail", "PASSENGER"));
        assertThat(r.status()).isEqualTo(400);
        assertThat((String) r.body().get("code")).isEqualTo("INVALID_INPUT");

        List<Integer> counts = jdbc.queryForList(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, longEmail);
        assertThat(counts.get(0)).isEqualTo(0);
    }

    private Resp putJson(String path, Object body, String correlationHeader) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base() + path))
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(
                        JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json");
        if (correlationHeader != null) {
            req.header("X-Correlation-ID", correlationHeader);
        }
        HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), parse(resp.body()));
    }

    private Resp postRaw(String path, String rawBody, String contentType) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(rawBody))
                .header("Content-Type", contentType)
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), parse(resp.body()));
    }

    /** F02: exp = now + 0.5 s vẫn hợp lệ; nbf tương lai hoặc "nbf": null -> 401. */
    @Test
    void fractionalSecondExpAcceptedAndNbfFutureOrNullRejected() throws Exception {
        String userId = registerUser("frac-" + UUID.randomUUID() + "@example.com");

        Map<String, Object> claims = validClaims(userId);
        claims.put("exp", Instant.now().toEpochMilli() / 1000.0 + 0.5); // now + 0.5 s
        Resp ok = get("/api/auth/me", signToken(claims));
        assertThat(ok.status()).isEqualTo(200);

        claims = validClaims(userId);
        claims.put("nbf", Instant.now().toEpochMilli() / 1000.0 + 0.9); // chưa hiệu lực
        assert401Unauthorized(signToken(claims));

        claims = validClaims(userId);
        claims.put("nbf", null); // có mặt nhưng null -> invalid (F02)
        assert401Unauthorized(signToken(claims));
    }

    /** F03: role/password/fullName dạng số không được ép kiểu -> 400 INVALID_INPUT, DB không đổi. */
    @Test
    void numericRolePasswordAndFullNameRejectedWithoutPersisting() throws Exception {
        String email = "coerce-" + UUID.randomUUID() + "@example.com";

        Map<String, Object> numRole = new LinkedHashMap<>();
        numRole.put("email", email);
        numRole.put("password", PASSWORD);
        numRole.put("fullName", "Coerce User");
        numRole.put("role", 0); // số thay vì chuỗi enum
        Resp r1 = post("/api/auth/register", numRole);
        assertThat(r1.status()).isEqualTo(400);
        assertThat((String) r1.body().get("code")).isEqualTo("INVALID_INPUT");

        Map<String, Object> numPw = new LinkedHashMap<>();
        numPw.put("email", email);
        numPw.put("password", 12345678); // số thay vì chuỗi
        numPw.put("fullName", "Coerce User");
        numPw.put("role", "PASSENGER");
        Resp r2 = post("/api/auth/register", numPw);
        assertThat(r2.status()).isEqualTo(400);

        Map<String, Object> numName = new LinkedHashMap<>();
        numName.put("email", email);
        numName.put("password", PASSWORD);
        numName.put("fullName", 12345678); // số thay vì chuỗi
        numName.put("role", "PASSENGER");
        Resp r3 = post("/api/auth/register", numName);
        assertThat(r3.status()).isEqualTo(400);

        List<Integer> counts = jdbc.queryForList(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(counts.get(0)).isEqualTo(0); // DB không đổi
    }

    /** F04: sai method -> 405 METHOD_NOT_ALLOWED; sai Content-Type -> 415 UNSUPPORTED_MEDIA_TYPE. */
    @Test
    void wrongMethodAndContentTypeReturnProperStatus() throws Exception {
        String correlation = UUID.randomUUID().toString();
        Resp m = putJson("/api/auth/login",
                new LoginBody("m-" + UUID.randomUUID() + "@example.com", PASSWORD), correlation);
        assertThat(m.status()).isEqualTo(405);
        assertThat((String) m.body().get("code")).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat((String) m.body().get("correlationId")).isEqualTo(correlation);

        Resp ct = postRaw("/api/auth/register", "not-json", "text/plain");
        assertThat(ct.status()).isEqualTo(415);
        assertThat((String) ct.body().get("code")).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    /** N11 — audit log register/login qua appender thật; không lộ email đầy đủ / password / token. */
    @Test
    void businessAuditLogsAreStructuredAndSecretFree() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("com.ridehailing.identity.service.UserService");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.setName("AUDIT_CAPTURE");
        appender.setOutputStream(out);
        JsonLogLayout layout = new JsonLogLayout();
        layout.setService("identity-service");
        appender.setLayout(layout);
        appender.start();
        logger.addAppender(appender);
        try {
            String email = "audit-" + UUID.randomUUID() + "@example.com";
            Resp reg = post("/api/auth/register", new RegisterBody(email, PASSWORD, "Audit User", "PASSENGER"));
            assertThat(reg.status()).isEqualTo(201);

            Resp okLogin = post("/api/auth/login", new LoginBody(email, PASSWORD));
            assertThat(okLogin.status()).isEqualTo(200);

            Resp badLogin = post("/api/auth/login", new LoginBody(email, "wrong-pass-9"));
            assertThat(badLogin.status()).isEqualTo(401);
        } finally {
            logger.detachAndStopAllAppenders();
        }

        String text = out.toString(StandardCharsets.UTF_8);
        List<String> lines = Arrays.stream(text.split("\n")).filter(l -> !l.isBlank()).toList();
        assertThat(lines).hasSizeGreaterThanOrEqualTo(3);
        List<Map<String, Object>> events = new ArrayList<>();
        for (String line : lines) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = JSON.readValue(line.getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
            events.add(m); // mỗi dòng parse được bằng JSON
        }
        assertThat(events).anySatisfy(e -> {
            assertThat(e.get("eventType")).isEqualTo("user_registered");
            assertThat(e.get("result")).isEqualTo("SUCCESS");
        });
        assertThat(events).anySatisfy(e -> {
            assertThat(e.get("eventType")).isEqualTo("login_succeeded");
            assertThat(e.get("result")).isEqualTo("SUCCESS");
        });
        assertThat(events).anySatisfy(e -> {
            assertThat(e.get("eventType")).isEqualTo("login_failed");
            assertThat(e.get("result")).isEqualTo("FAILURE");
        });
        // Không lộ secret: email đầy đủ, password hay "Bearer ".
        assertThat(text).doesNotContain(PASSWORD);
        assertThat(text).doesNotContain("@example.com");
        assertThat(text).doesNotContain("Bearer ");
    }

    record RegisterBody(String email, String password, String fullName, String role) {
    }

    record LoginBody(String email, String password) {
    }

    // ---------- L03/L04 — request bị Spring Security chặn vẫn có log truy cập; firewall trả ApiError ----------

    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> attachAccessLogAppender() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(com.ridehailing.identity.security.CorrelationIdFilter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAccessLogAppender(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                com.ridehailing.identity.security.CorrelationIdFilter.class)).detachAppender(appender);
    }

    /** Dòng log truy cập được ghi sau khi response đã gửi — chờ tối đa 3 s. */
    private static boolean accessLogged(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender,
            String correlation, String status) throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean found = new java.util.ArrayList<>(appender.list).stream().anyMatch(e ->
                    correlation.equals(e.getMDCPropertyMap().get("correlationId"))
                            && status.equals(e.getMDCPropertyMap().get("result"))
                            && "http_request".equals(e.getMDCPropertyMap().get("eventType")));
            if (found) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** L04: URL bị StrictHttpFirewall chặn -> 400 ApiError có correlationId (không phải body mặc định Spring). L03: vẫn có log. */
    @org.junit.jupiter.api.Test
    void firewallRejectionReturnsApiErrorAndIsAccessLogged() throws Exception {
        var appender = attachAccessLogAppender();
        try {
            String correlation = UUID.randomUUID().toString();
            Resp resp = getWithCorrelation("/api/probe;x", correlation);
            assertThat(resp.status()).isEqualTo(400);
            assertThat(resp.body().get("code")).isEqualTo("INVALID_INPUT");
            assertThat(resp.body().get("correlationId")).isEqualTo(correlation);
            assertThat(resp.body()).doesNotContainKey("timestamp");
            assertThat(accessLogged(appender, correlation, "400")).isTrue();
        } finally {
            detachAccessLogAppender(appender);
        }
    }

    /** L03: request bị từ chối ở tầng security (401) vẫn có dòng log truy cập kèm correlationId. */
    @org.junit.jupiter.api.Test
    void unauthorizedRequestIsAccessLogged() throws Exception {
        var appender = attachAccessLogAppender();
        try {
            String correlation = UUID.randomUUID().toString();
            Resp resp = getWithCorrelation("/api/auth/me", correlation);
            assertThat(resp.status()).isEqualTo(401);
            assertThat(accessLogged(appender, correlation, "401")).isTrue();
        } finally {
            detachAccessLogAppender(appender);
        }
    }

    private Resp getWithCorrelation(String path, String correlationHeader) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path))
                .header("X-Correlation-ID", correlationHeader).GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), parse(resp.body()));
    }
}
