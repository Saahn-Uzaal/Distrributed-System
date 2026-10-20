package com.ridehailing.driver;

import org.junit.jupiter.api.AfterEach;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T10 — integration test giữ chỗ nguyên tử trên HTTP thật + PostgreSQL thật (Testcontainers, cần Docker).
 * Flyway chạy V1+V2+V3 lên DB container. Acceptance T10:
 *  - hai reservation cạnh tranh một driver chỉ một thành công;
 *  - retry cùng command không tạo thêm record;
 *  - payload khác cùng commandId trả 409 COMMAND_CONFLICT;
 *  + STALE_VERSION, DRIVER_NOT_AVAILABLE (OFFLINE/heartbeat stale), TOMBSTONED_RESERVATION.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"server.port=18101", "reservation.expiry.fixed-delay-ms=50"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class ReservationLifecycleIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withUsername("driver_test")
            .withPassword("driver_test_pw");

    private static final Path KEYS_DIR;
    private static final String PUBLIC_KEY_PATH;

    static {
        try {
            KEYS_DIR = Files.createTempDirectory("jwt-test-keys-driver-t10");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            Path pub = KEYS_DIR.resolve("jwt-public.pem");
            Files.writeString(pub, "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
            PUBLIC_KEY_PATH = pub.toString();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final String INTERNAL_TOKEN = "test-internal-token-t10";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Internal endpoints không cần JWT, nhưng bean JwtVerifier vẫn khởi tạo — dùng public key test.
        registry.add("jwt.public-key-path", () -> PUBLIC_KEY_PATH);
        registry.add("internal.api-token", () -> INTERNAL_TOKEN);
    }

    @Autowired
    private JdbcTemplate jdbc;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String BASE = "http://localhost:18101";

    record Resp(int status, Map<String, Object> body) {
    }

    @AfterEach
    void cleanTables() {
        jdbc.update("DELETE FROM reservations");
        jdbc.update("DELETE FROM processed_commands");
        jdbc.update("DELETE FROM drivers");
        jdbc.update("DELETE FROM vehicles");
    }

    // --- helpers HTTP/DB ---

    private Resp send(String method, String path, String internalToken, String jsonBody) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(BASE + path));
        switch (method) {
            case "GET" -> req.GET();
            case "POST" -> req.POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
            case "PUT" -> req.PUT(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
            default -> throw new IllegalArgumentException(method);
        }
        if (internalToken != null) {
            req.header("X-Internal-Token", internalToken);
        }
        req.header("Content-Type", "application/json");
        HttpResponse<String> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), parse(resp.body()));
    }

    private static Map<String, Object> parse(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = JSON.readValue(body.getBytes(StandardCharsets.UTF_8), LinkedHashMap.class);
        return m;
    }

    /** Seed driver row trực tiếp vào DB (kiểm soát state/heartbeat cho các nhánh lỗi). */
    private UUID seedDriver(UUID userId, String status, Instant lastSeenAt, Double lat, Double lng) {
        UUID vehicleId = jdbc.queryForObject(
                "INSERT INTO vehicles (plate_number, vehicle_type) VALUES (?, 'CAR') RETURNING id",
                (rs, rowNum) -> toUuid(rs.getObject("id")),
                "T10-" + userId);
        return jdbc.queryForObject(
                "INSERT INTO drivers (user_id, display_name, vehicle_id, status, latitude, longitude, last_seen_at)"
                        + " VALUES (?, 'Seed Driver', ?, ?, ?, ?, ?) RETURNING id",
                (rs, rowNum) -> toUuid(rs.getObject("id")),
                userId, vehicleId, status, lat, lng, Timestamp.from(lastSeenAt));
    }

    private static UUID toUuid(Object id) {
        if (id instanceof UUID u) {
            return u;
        }
        return UUID.fromString(id.toString());
    }

    /** Body reserve theo contract §4.2; fareVnd thay đổi được để test COMMAND_CONFLICT. */
    private String reserveJson(UUID commandId, long version, UUID tripId, UUID driverId, int attempt,
                              Instant expiresAt, long fareVnd) {
        return "{\"commandId\":\"" + commandId + "\","
                + "\"commandVersion\":" + version + ","
                + "\"tripId\":\"" + tripId + "\","
                + "\"driverId\":\"" + driverId + "\","
                + "\"attempt\":" + attempt + ","
                + "\"expiresAt\":\"" + expiresAt.toString() + "\","
                + "\"pickup\":{\"latitude\":10.7769,\"longitude\":106.7009},"
                + "\"destination\":{\"latitude\":10.8000,\"longitude\":10.7200},"
                + "\"distanceM\":3316,"
                + "\"fareVnd\":" + fareVnd + "}";
    }

    private Resp reserve(UUID reservationId, UUID commandId, long version, UUID tripId, UUID driverId,
                         int attempt, Instant expiresAt, long fareVnd) throws Exception {
        return send("PUT", "/internal/reservations/" + reservationId, INTERNAL_TOKEN,
                reserveJson(commandId, version, tripId, driverId, attempt, expiresAt, fareVnd));
    }

    private int countReservations() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM reservations", Integer.class);
        return c == null ? 0 : c;
    }

    private int countProcessed(UUID commandId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_commands WHERE command_id = ?", Integer.class, commandId);
        return c == null ? 0 : c;
    }


    private Resp post(UUID reservation, String action, UUID command, long version, String fields) throws Exception {
        return send("POST", "/internal/reservations/"+reservation+"/"+action, INTERNAL_TOKEN,
                "{\"commandId\":\""+command+"\",\"commandVersion\":"+version+(fields.isEmpty()?"":","+fields)+"}");
    }

    private String confirmFields(UUID trip, UUID driver, int attempt) {
        return "\"tripId\":\""+trip+"\",\"driverId\":\""+driver+"\",\"attempt\":"+attempt;
    }

    private UUID availableDriver() {
        return seedDriver(UUID.randomUUID(), "AVAILABLE", Instant.now(),10.7769,106.7009);
    }

    private void reserveOk(UUID reservation, UUID trip, UUID driver) throws Exception {
        assertThat(reserve(reservation,UUID.randomUUID(),1,trip,driver,1,Instant.now().plusSeconds(30),32000).status()).isEqualTo(201);
    }

    private Map<String,Object> driverRow(UUID id) {
        return jdbc.queryForMap("SELECT status,current_reservation_id,current_trip_id FROM drivers WHERE id=?",id);
    }

    private String state(UUID id) {
        return jdbc.queryForObject("SELECT state FROM reservations WHERE id=?",String.class,id);
    }

    private void awaitState(UUID id, String expected) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline && !expected.equals(state(id))) Thread.sleep(20);
        assertThat(state(id)).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void releaseBeforeReserveCreatesDurableTombstone() throws Exception {
        UUID reservation=UUID.randomUUID(), driver=availableDriver();
        Resp release=post(reservation,"release",UUID.randomUUID(),2,"\"reason\":\"CANCELLED\"");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("state")).isEqualTo("RELEASED");
        Resp late=reserve(reservation,UUID.randomUUID(),1,UUID.randomUUID(),driver,1,Instant.now().plusSeconds(30),32000);
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.body().get("code")).isEqualTo("TOMBSTONED_RESERVATION");
        assertThat(driverRow(driver).get("status")).isEqualTo("AVAILABLE");
        Resp get=send("GET","/internal/reservations/"+reservation,INTERNAL_TOKEN,null);
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.body().get("tripId")).isNull();
        assertThat(get.body().get("driverId")).isNull();
        assertThat(get.body().get("attempt")).isNull();
        assertThat(((Number)get.body().get("version")).intValue()).isEqualTo(2);
    }

    @org.junit.jupiter.api.Test
    void confirmSetsBusyAndReplaysOriginalResponse() throws Exception {
        UUID r=UUID.randomUUID(),trip=UUID.randomUUID(),driver=availableDriver(),cmd=UUID.randomUUID();
        reserveOk(r,trip,driver);
        Resp first=post(r,"confirm",cmd,2,confirmFields(trip,driver,1));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("state")).isEqualTo("CONFIRMED");
        assertThat(driverRow(driver)).containsEntry("status","BUSY").containsEntry("current_reservation_id",r).containsEntry("current_trip_id",trip);
        Resp retry=post(r,"confirm",cmd,2,confirmFields(trip,driver,1));
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(countProcessed(cmd)).isEqualTo(1);
    }

    @org.junit.jupiter.api.Test
    void confirmRejectsWrongIdentityAndRollsBackCommandClaim() throws Exception {
        UUID r=UUID.randomUUID(),trip=UUID.randomUUID(),driver=availableDriver(); reserveOk(r,trip,driver);
        for(String fields:List.of(confirmFields(UUID.randomUUID(),driver,1),confirmFields(trip,UUID.randomUUID(),1),confirmFields(trip,driver,2))) {
            UUID cmd=UUID.randomUUID(); Resp wrong=post(r,"confirm",cmd,2,fields);
            assertThat(wrong.status()).isEqualTo(409);
            assertThat(wrong.body().get("code")).isEqualTo("STATE_CONFLICT");
            assertThat(countProcessed(cmd)).isZero();
        }
        assertThat(state(r)).isEqualTo("RESERVED");
        assertThat(driverRow(driver).get("status")).isEqualTo("RESERVED");
    }

    @org.junit.jupiter.api.Test
    void releaseCompensatesConfirmedAssignment() throws Exception {
        UUID r=UUID.randomUUID(),trip=UUID.randomUUID(),driver=availableDriver(); reserveOk(r,trip,driver);
        assertThat(post(r,"confirm",UUID.randomUUID(),2,confirmFields(trip,driver,1)).status()).isEqualTo(200);
        assertThat(post(r,"release",UUID.randomUUID(),3,"\"reason\":\"CANCELLED\"").status()).isEqualTo(200);
        assertThat(state(r)).isEqualTo("RELEASED");
        assertThat(driverRow(driver)).containsEntry("status","AVAILABLE").containsEntry("current_reservation_id",null).containsEntry("current_trip_id",null);
        assertThat(jdbc.queryForObject("SELECT terminal_reason FROM reservations WHERE id=?",String.class,r)).isEqualTo("CANCELLED");
    }

    @org.junit.jupiter.api.Test
    void finishChecksTripAndReleasesOnlyConfirmedAssignment() throws Exception {
        UUID r=UUID.randomUUID(),trip=UUID.randomUUID(),driver=availableDriver(),cmd=UUID.randomUUID(); reserveOk(r,trip,driver);
        assertThat(post(r,"finish",UUID.randomUUID(),2,"\"tripId\":\""+trip+"\"").status()).isEqualTo(409);
        assertThat(post(r,"confirm",UUID.randomUUID(),2,confirmFields(trip,driver,1)).status()).isEqualTo(200);
        Resp wrong=post(r,"finish",UUID.randomUUID(),3,"\"tripId\":\""+UUID.randomUUID()+"\"");
        assertThat(wrong.status()).isEqualTo(409);
        assertThat(driverRow(driver).get("status")).isEqualTo("BUSY");
        Resp finished=post(r,"finish",cmd,3,"\"tripId\":\""+trip+"\"");
        assertThat(finished.status()).isEqualTo(200);
        assertThat(finished.body().get("state")).isEqualTo("RELEASED");
        assertThat(driverRow(driver)).containsEntry("status","AVAILABLE").containsEntry("current_reservation_id",null).containsEntry("current_trip_id",null);
        assertThat(jdbc.queryForObject("SELECT terminal_reason FROM reservations WHERE id=?",String.class,r)).isEqualTo("TRIP_COMPLETED");
        assertThat(post(r,"finish",cmd,3,"\"tripId\":\""+trip+"\"").body()).isEqualTo(finished.body());
    }

    @org.junit.jupiter.api.Test
    void oldReleaseDoesNotFreeNewReservation() throws Exception {
        UUID old=UUID.randomUUID(),driver=availableDriver(),releaseCmd=UUID.randomUUID(); reserveOk(old,UUID.randomUUID(),driver);
        assertThat(post(old,"release",releaseCmd,2,"\"reason\":\"REJECTED\"").status()).isEqualTo(200);
        UUID current=UUID.randomUUID(); reserveOk(current,UUID.randomUUID(),driver);
        assertThat(post(old,"release",releaseCmd,2,"\"reason\":\"REJECTED\"").status()).isEqualTo(200);
        assertThat(post(old,"release",UUID.randomUUID(),3,"\"reason\":\"CANCELLED\"").status()).isEqualTo(200);
        assertThat(driverRow(driver)).containsEntry("status","RESERVED").containsEntry("current_reservation_id",current);
        assertThat(jdbc.queryForObject("SELECT terminal_reason FROM reservations WHERE id=?",String.class,old)).isEqualTo("REJECTED");
    }

    @org.junit.jupiter.api.Test
    void oldFinishDoesNotFreeNewBusyTrip() throws Exception {
        UUID old=UUID.randomUUID(),trip=UUID.randomUUID(),driver=availableDriver(),cmd=UUID.randomUUID(); reserveOk(old,trip,driver);
        assertThat(post(old,"confirm",UUID.randomUUID(),2,confirmFields(trip,driver,1)).status()).isEqualTo(200);
        assertThat(post(old,"finish",cmd,3,"\"tripId\":\""+trip+"\"").status()).isEqualTo(200);
        UUID next=UUID.randomUUID(),nextTrip=UUID.randomUUID(); reserveOk(next,nextTrip,driver);
        assertThat(post(next,"confirm",UUID.randomUUID(),2,confirmFields(nextTrip,driver,1)).status()).isEqualTo(200);
        assertThat(post(old,"finish",cmd,3,"\"tripId\":\""+trip+"\"").status()).isEqualTo(200);
        assertThat(post(old,"finish",UUID.randomUUID(),4,"\"tripId\":\""+trip+"\"").status()).isEqualTo(200);
        assertThat(driverRow(driver)).containsEntry("status","BUSY").containsEntry("current_reservation_id",next).containsEntry("current_trip_id",nextTrip);
    }

    @org.junit.jupiter.api.Test
    void lifecycleRejectsStaleVersionsAndInvalidInputs() throws Exception {
        UUID r=UUID.randomUUID(),trip=UUID.randomUUID(),driver=availableDriver(); reserveOk(r,trip,driver);
        for(String action:List.of("confirm","release","finish")) {
            String fields=action.equals("confirm")?confirmFields(trip,driver,1):action.equals("release")?"\"reason\":\"CANCELLED\"":"\"tripId\":\""+trip+"\"";
            Resp stale=post(r,action,UUID.randomUUID(),1,fields);
            assertThat(stale.status()).isEqualTo(409);
            assertThat(stale.body().get("code")).isEqualTo("STALE_VERSION");
            assertThat(post(r,action,UUID.randomUUID(),2147483648L,fields).status()).isEqualTo(400);
        }
        assertThat(post(r,"release",UUID.randomUUID(),2,"\"reason\":\"TRIP_COMPLETED\"").status()).isEqualTo(400);
        assertThat(post(r,"confirm",UUID.randomUUID(),2,"").status()).isEqualTo(400);
        assertThat(post(r,"finish",UUID.randomUUID(),2,"").status()).isEqualTo(400);
    }

    @org.junit.jupiter.api.Test
    void commandIdCannotBeReusedForDifferentOperation() throws Exception {
        UUID r=UUID.randomUUID(),trip=UUID.randomUUID(),driver=availableDriver(),cmd=UUID.randomUUID();
        assertThat(reserve(r,cmd,1,trip,driver,1,Instant.now().plusSeconds(30),32000).status()).isEqualTo(201);
        Resp conflict=post(r,"release",cmd,2,"\"reason\":\"CANCELLED\"");
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body().get("code")).isEqualTo("COMMAND_CONFLICT");
        assertThat(state(r)).isEqualTo("RESERVED");
    }

    @org.junit.jupiter.api.Test
    void expiryWorkerExpiresReservedButNeverConfirmed() throws Exception {
        UUID r=UUID.randomUUID(),driver=availableDriver(),confirmed=UUID.randomUUID(),busyDriver=availableDriver(),trip=UUID.randomUUID();
        reserveOk(r,UUID.randomUUID(),driver); reserveOk(confirmed,trip,busyDriver);
        assertThat(post(confirmed,"confirm",UUID.randomUUID(),2,confirmFields(trip,busyDriver,1)).status()).isEqualTo(200);
        jdbc.update("UPDATE reservations SET expires_at=now()-interval '1 second' WHERE id IN (?,?)",r,confirmed);
        awaitState(r,"EXPIRED");
        assertThat(driverRow(driver).get("status")).isEqualTo("AVAILABLE");
        assertThat(state(confirmed)).isEqualTo("CONFIRMED");
        assertThat(driverRow(busyDriver)).containsEntry("status","BUSY").containsEntry("current_trip_id",trip);
        assertThat(jdbc.queryForObject("SELECT last_command_version FROM reservations WHERE id=?",Integer.class,r)).isEqualTo(1);
        Resp late=post(r,"confirm",UUID.randomUUID(),2,confirmFields(UUID.randomUUID(),driver,1));
        assertThat(late.status()).isEqualTo(409);
    }

    @org.junit.jupiter.api.Test
    void releaseOfExpiredReservationKeepsTerminalState() throws Exception {
        UUID r=UUID.randomUUID(),driver=availableDriver(); reserveOk(r,UUID.randomUUID(),driver);
        jdbc.update("UPDATE reservations SET expires_at=now()-interval '1 second' WHERE id=?",r);
        awaitState(r,"EXPIRED");
        Resp release=post(r,"release",UUID.randomUUID(),2,"\"reason\":\"CANCELLED\"");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("state")).isEqualTo("EXPIRED");
        assertThat(state(r)).isEqualTo("EXPIRED");
    }

    @org.junit.jupiter.api.Test
    void missingReservationConfirmAndFinishReturn404() throws Exception {
        assertThat(post(UUID.randomUUID(),"confirm",UUID.randomUUID(),1,confirmFields(UUID.randomUUID(),UUID.randomUUID(),1)).status()).isEqualTo(404);
        assertThat(post(UUID.randomUUID(),"finish",UUID.randomUUID(),1,"\"tripId\":\""+UUID.randomUUID()+"\"").status()).isEqualTo(404);
    }

    @org.junit.jupiter.api.Test
    void lifecycleRequiresInternalCredential() throws Exception {
        for(String action:List.of("confirm","release","finish")) {
            Resp response=send("POST","/internal/reservations/"+UUID.randomUUID()+"/"+action,null,"{}");
            assertThat(response.status()).isEqualTo(401);
            assertThat(response.body().get("code")).isEqualTo("INVALID_INTERNAL_CREDENTIAL");
        }
    }

    @org.junit.jupiter.api.Test
    void releaseAndReserveOverlapNeverReviveTombstone() throws Exception {
        UUID r=UUID.randomUUID(),driver=availableDriver();
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var reserved=pool.submit(()->{barrier.await();return reserve(r,UUID.randomUUID(),1,UUID.randomUUID(),driver,1,Instant.now().plusSeconds(30),32000);});
            var released=pool.submit(()->{barrier.await();return post(r,"release",UUID.randomUUID(),2,"\"reason\":\"CANCELLED\"");});
            assertThat(released.get(10,java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(reserved.get(10,java.util.concurrent.TimeUnit.SECONDS).status()).isIn(201,409);
        }
        assertThat(state(r)).isEqualTo("RELEASED");
        assertThat(driverRow(driver).get("status")).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT last_command_version FROM reservations WHERE id=?",Integer.class,r)).isEqualTo(2);
    }

    @org.junit.jupiter.api.Test
    void confirmAndReleaseOverlapEndsReleased() throws Exception {
        UUID r=UUID.randomUUID(),trip=UUID.randomUUID(),driver=availableDriver(); reserveOk(r,trip,driver);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var confirmed=pool.submit(()->{barrier.await();return post(r,"confirm",UUID.randomUUID(),2,confirmFields(trip,driver,1));});
            var released=pool.submit(()->{barrier.await();return post(r,"release",UUID.randomUUID(),3,"\"reason\":\"CANCELLED\"");});
            assertThat(released.get(10,java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(confirmed.get(10,java.util.concurrent.TimeUnit.SECONDS).status()).isIn(200,409);
        }
        assertThat(state(r)).isEqualTo("RELEASED");
        assertThat(driverRow(driver).get("status")).isEqualTo("AVAILABLE");
    }


    @org.junit.jupiter.api.Test
    void confirmWaitingForDriverLockMustRecheckOfferDeadline() throws Exception {
        UUID r=UUID.randomUUID(),trip=UUID.randomUUID(),driver=availableDriver();
        Instant deadline=Instant.now().plusSeconds(2);
        assertThat(reserve(r,UUID.randomUUID(),1,trip,driver,1,deadline,32000).status()).isEqualTo(201);
        var pool=Executors.newSingleThreadExecutor();
        try(var lock=jdbc.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            try(var statement=lock.prepareStatement("SELECT id FROM drivers WHERE id=? FOR UPDATE")) {
                statement.setObject(1,driver);statement.executeQuery().close();
            }
            var confirm=pool.submit(()->post(r,"confirm",UUID.randomUUID(),2,confirmFields(trip,driver,1)));
            int waiting=0;
            long timeout=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<timeout) {
                waiting=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND (query LIKE 'UPDATE drivers SET status=%' OR query LIKE 'SELECT id FROM drivers%')",Integer.class);
                if(waiting>0) break;
                Thread.sleep(10);
            }
            assertThat(waiting).isGreaterThan(0);
            while(Instant.now().isBefore(deadline.plusMillis(100))) Thread.sleep(10);
            lock.commit();
            Resp result=confirm.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(409);
            assertThat(result.body().get("code")).isEqualTo("STATE_CONFLICT");
        } finally {pool.shutdownNow();}
        awaitState(r,"EXPIRED");
        assertThat(driverRow(driver).get("status")).isEqualTo("AVAILABLE");
    }

    @org.junit.jupiter.api.Test
    void finishCannotFreeDriverUnlessBothAssignmentPointersMatch() throws Exception {
        UUID r=UUID.randomUUID(),trip=UUID.randomUUID(),driver=availableDriver(); reserveOk(r,trip,driver);
        assertThat(post(r,"confirm",UUID.randomUUID(),2,confirmFields(trip,driver,1)).status()).isEqualTo(200);
        // A delayed command must remain fenced even against a partially repaired driver row.
        jdbc.update("UPDATE drivers SET status='RESERVED',current_trip_id=NULL WHERE id=?",driver);
        assertThat(post(r,"finish",UUID.randomUUID(),3,"\"tripId\":\""+trip+"\"").status()).isEqualTo(200);
        assertThat(driverRow(driver)).containsEntry("status","RESERVED").containsEntry("current_reservation_id",r);
    }

}
