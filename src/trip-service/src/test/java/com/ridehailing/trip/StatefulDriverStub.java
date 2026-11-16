package com.ridehailing.trip;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Stub internal reservation API của driver-service cho test T18–T22, mô phỏng ngữ nghĩa contract §4.1/§4.2:
 * dedup theo commandId (cùng hash → replay, khác → 409 COMMAND_CONFLICT), commandVersion phải tăng
 * (STALE_VERSION), reserve trễ sau release → TOMBSTONED_RESERVATION, confirm chỉ khi RESERVED + đúng
 * identity + chưa hết hạn, release CONFIRMED chỉ với reason CANCELLED, release reservation chưa biết → tombstone,
 * finish chỉ từ CONFIRMED khớp tripId → RELEASED/TRIP_COMPLETED (idempotent trên row đã TRIP_COMPLETED).
 * Không phải driver-service thật — test hệ thống với service thật thuộc T30.
 *
 * <p>Tiêm lỗi: {@link #delayAfterApplyMs} (Driver ĐÃ áp dụng nhưng trả lời muộn hơn read-timeout của Trip),
 * {@link #delayBeforeApplyMs} (lệnh bị chặn trước khi tới Driver), {@link #failGet} (GET cũng timeout).
 */
final class StatefulDriverStub {

    static final class Reservation {
        String state;
        String tripId;
        String driverId;
        Integer attempt;
        Instant expiresAt;
        int lastVersion;
        String terminalReason;
    }

    private record Processed(String hash, int status, String body) {
    }

    private final HttpServer server;
    volatile int getFailureStatus;
    private final ObjectMapper json = new ObjectMapper();
    final Map<String, Reservation> reservations = new HashMap<>();
    private final Map<String, Processed> processed = new HashMap<>();
    /** "PUT:<rid>", "GET:<rid>", "POST:<rid>/confirm", "POST:<rid>/release", "POST:<rid>/finish" theo thứ tự nhận. */
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    /** "METHOD:path" → X-Correlation-ID của lần gọi gần nhất. */
    final Map<String, String> correlationIds = Collections.synchronizedMap(new HashMap<>());
    /** Body lệnh confirm/release đã nhận (kể cả replay). */
    final List<Map<String, Object>> commandBodies = Collections.synchronizedList(new ArrayList<>());

    /** action ("confirm"/"release"/"reserve") → ms trễ SAU khi đã áp dụng. */
    final Map<String, Long> delayAfterApplyMs = new ConcurrentHashMap<>();
    /** action → số lần còn lại bị trễ TRƯỚC khi áp dụng (request không tới được Driver trong thời hạn). */
    final Map<String, Integer> delayBeforeApplyCount = new ConcurrentHashMap<>();
    volatile long delayBeforeApplyMs = 1_500;
    /** action → ms ngủ TRƯỚC khi áp dụng, sau đó áp dụng và trả lời bình thường (lệnh "đang bay"). */
    final Map<String, Long> slowBeforeApplyMs = new ConcurrentHashMap<>();
    /** GET bị trễ (timeout phía Trip) khi > 0, giảm dần mỗi lần. */
    volatile int failGet = 0;
    /** action → số lần còn lại trả 400 không có field {@code code} (ngoài contract), không áp dụng lệnh. */
    final Map<String, Integer> codelessErrorCount = new ConcurrentHashMap<>();

    StatefulDriverStub() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/internal/reservations/", this::handle);
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    int port() {
        return server.getAddress().getPort();
    }

    synchronized void reset() {
        getFailureStatus = 0;
        reservations.clear();
        processed.clear();
        calls.clear();
        correlationIds.clear();
        commandBodies.clear();
        delayAfterApplyMs.clear();
        delayBeforeApplyCount.clear();
        slowBeforeApplyMs.clear();
        codelessErrorCount.clear();
        failGet = 0;
    }

    synchronized Reservation reservation(String rid) {
        return reservations.get(rid);
    }

    synchronized void expire(String rid) {
        reservations.get(rid).expiresAt = Instant.now().minusSeconds(1);
    }

    long countCalls(String prefix) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.startsWith(prefix)).count();
        }
    }

    // ---------------------------------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath().substring("/internal/reservations/".length());
            String method = ex.getRequestMethod();
            String rid = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
            String action = path.contains("/") ? path.substring(path.indexOf('/') + 1)
                    : ("PUT".equals(method) ? "reserve" : "get");
            calls.add(method + ":" + path);
            correlationIds.put(method + ":" + path, ex.getRequestHeaders().getFirst("X-Correlation-ID"));

            if ("get".equals(action)) {
                if (getFailureStatus != 0) {
                    send(ex, getFailureStatus, "{\"code\":\"UNAVAILABLE\",\"message\":\"simulated\"}");
                    return;
                }
                if (failGet > 0) {
                    failGet--;
                    sleep(delayBeforeApplyMs);
                }
                Object[] r = get(rid);
                send(ex, (int) r[0], (String) r[1]);
                return;
            }
            Integer before = delayBeforeApplyCount.computeIfPresent(action, (k, v) -> v - 1);
            if (before != null && before >= 0) {
                sleep(delayBeforeApplyMs);
                if (before == 0) {
                    delayBeforeApplyCount.remove(action);
                }
                send(ex, 503, "{\"code\":\"UNAVAILABLE\",\"message\":\"simulated\"}");
                return;
            }
            Integer codeless = codelessErrorCount.computeIfPresent(action, (k, v) -> v - 1);
            if (codeless != null && codeless >= 0) {
                if (codeless == 0) {
                    codelessErrorCount.remove(action);
                }
                send(ex, 400, "{\"message\":\"simulated error without code\"}");
                return;
            }
            Long slow = slowBeforeApplyMs.get(action);
            if (slow != null) {
                sleep(slow);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> body = json.readValue(ex.getRequestBody().readAllBytes(), LinkedHashMap.class);
            if (!"reserve".equals(action)) {
                commandBodies.add(body);
            }
            Object[] r = command(rid, action, body);
            Long after = delayAfterApplyMs.get(action);
            if (after != null) {
                sleep(after);
            }
            send(ex, (int) r[0], (String) r[1]);
        } catch (Exception e) {
            send(ex, 500, "{\"code\":\"INTERNAL_ERROR\",\"message\":\"stub\"}");
        }
    }

    private synchronized Object[] get(String rid) {
        Reservation r = reservations.get(rid);
        if (r == null) {
            return new Object[]{404, "{\"code\":\"NOT_FOUND\",\"message\":\"Reservation not found.\"}"};
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("reservationId", rid);
        view.put("state", r.state);
        view.put("tripId", r.tripId);
        view.put("driverId", r.driverId);
        view.put("attempt", r.attempt);
        view.put("expiresAt", r.expiresAt == null ? null : r.expiresAt.toString());
        view.put("version", r.lastVersion);
        return new Object[]{200, json.writeValueAsString(view)};
    }

    private synchronized Object[] command(String rid, String action, Map<String, Object> body) {
        String commandId = (String) body.get("commandId");
        int version = ((Number) body.get("commandVersion")).intValue();
        Map<String, Object> hashed = new LinkedHashMap<>(body);
        hashed.remove("commandId");
        hashed.remove("commandVersion");
        if ("reserve".equals(action)) {
            hashed.remove("expiresAt"); // stub: replay sau khi Trip tính lại expiresAt vẫn coi là cùng lệnh
        }
        String hash = action + "|" + rid + "|" + hashed;
        Processed p = processed.get(commandId);
        if (p != null) {
            return p.hash().equals(hash) ? new Object[]{p.status(), p.body()}
                    : error(409, "COMMAND_CONFLICT");
        }
        Reservation r = reservations.get(rid);
        if (r != null && version <= r.lastVersion) {
            return error(409, "STALE_VERSION");
        }
        Object[] result = switch (action) {
            case "reserve" -> reserve(rid, r, body, version);
            case "confirm" -> confirm(rid, r, body, version);
            case "release" -> release(rid, r, (String) body.get("reason"), version);
            case "finish" -> finish(rid, r, body, version);
            default -> error(404, "NOT_FOUND");
        };
        if ((int) result[0] < 300) {
            processed.put(commandId, new Processed(hash, (int) result[0], (String) result[1]));
        }
        return result;
    }

    private Object[] reserve(String rid, Reservation existing, Map<String, Object> body, int version) {
        if (existing != null) {
            return error(409, "RELEASED".equals(existing.state) || "EXPIRED".equals(existing.state)
                    ? "TOMBSTONED_RESERVATION" : "DRIVER_NOT_AVAILABLE");
        }
        String driverId = (String) body.get("driverId");
        boolean driverBusy = reservations.values().stream().anyMatch(o -> driverId.equals(o.driverId)
                && ("RESERVED".equals(o.state) || "CONFIRMED".equals(o.state)));
        if (driverBusy) {
            return error(409, "DRIVER_NOT_AVAILABLE");
        }
        Reservation r = new Reservation();
        r.state = "RESERVED";
        r.tripId = (String) body.get("tripId");
        r.driverId = driverId;
        r.attempt = ((Number) body.get("attempt")).intValue();
        r.expiresAt = Instant.parse((String) body.get("expiresAt"));
        r.lastVersion = version;
        reservations.put(rid, r);
        return new Object[]{201, "{\"reservationId\":\"" + rid + "\",\"state\":\"RESERVED\",\"version\":" + version + "}"};
    }

    private Object[] confirm(String rid, Reservation r, Map<String, Object> body, int version) {
        if (r == null) {
            return error(404, "NOT_FOUND");
        }
        if (!"RESERVED".equals(r.state) || !Objects.equals(r.tripId, body.get("tripId"))
                || !Objects.equals(r.driverId, body.get("driverId"))
                || r.attempt != ((Number) body.get("attempt")).intValue()
                || !r.expiresAt.isAfter(Instant.now())) {
            return error(409, "STATE_CONFLICT");
        }
        r.state = "CONFIRMED";
        r.lastVersion = version;
        return ok(rid, "CONFIRMED");
    }

    private Object[] release(String rid, Reservation r, String reason, int version) {
        if (r == null) {
            Reservation tomb = new Reservation();
            tomb.state = "RELEASED";
            tomb.terminalReason = reason;
            tomb.lastVersion = version;
            reservations.put(rid, tomb);
            return ok(rid, "RELEASED");
        }
        if ("RELEASED".equals(r.state) || "EXPIRED".equals(r.state)) {
            r.lastVersion = version;
            return ok(rid, r.state);
        }
        if ("CONFIRMED".equals(r.state) && !"CANCELLED".equals(reason)) {
            return error(409, "STATE_CONFLICT");
        }
        if ("RESERVED".equals(r.state) && !r.expiresAt.isAfter(Instant.now())) {
            r.state = "EXPIRED"; // như expiry worker: TTL đã qua
            r.lastVersion = version;
            return ok(rid, "EXPIRED");
        }
        r.state = "RELEASED";
        r.terminalReason = reason;
        r.lastVersion = version;
        return ok(rid, "RELEASED");
    }

    /** T22 — finish (contract §4.2): chỉ từ CONFIRMED khớp tripId → RELEASED/TRIP_COMPLETED;
     *  retry trên row đã TRIP_COMPLETED cùng trip trả 200 không giải phóng lại; terminal khác lý do
     *  hoặc sai identity → 409 STATE_CONFLICT; chưa biết reservation → 404. */
    private Object[] finish(String rid, Reservation r, Map<String, Object> body, int version) {
        if (r == null) {
            return error(404, "NOT_FOUND");
        }
        boolean alreadyFinished = "RELEASED".equals(r.state) && "TRIP_COMPLETED".equals(r.terminalReason);
        if (!Objects.equals(r.tripId, body.get("tripId")) || !("CONFIRMED".equals(r.state) || alreadyFinished)) {
            return error(409, "STATE_CONFLICT");
        }
        r.lastVersion = version;
        if (!alreadyFinished) {
            r.state = "RELEASED";
            r.terminalReason = "TRIP_COMPLETED";
        }
        return ok(rid, "RELEASED");
    }

    private Object[] ok(String rid, String state) {
        return new Object[]{200, "{\"reservationId\":\"" + rid + "\",\"state\":\"" + state + "\"}"};
    }

    private static Object[] error(int status, String code) {
        return new Object[]{status, "{\"code\":\"" + code + "\",\"message\":\"stub\"}"};
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // client đã timeout và đóng kết nối — bình thường trong test timeout
        } finally {
            ex.close();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
