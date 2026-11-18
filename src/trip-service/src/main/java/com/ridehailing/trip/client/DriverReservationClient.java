package com.ridehailing.trip.client;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * T16 — gọi internal API reservation driver-service (contract api-contracts.md §4.2):
 * PUT {@code /internal/reservations/{id}} RESERVE + GET {@code /internal/reservations/{id}}
 * (reconcile sau timeout HTTP — state-machines.md §2 "Không giữ DB lock khi gọi HTTP: ... reconcile").
 * Timeout/mạng ném RuntimeException → nhánh retry bounded của inbox consumer (events.md §4.3).
 */
@Component
public class DriverReservationClient {

    /** Kết quả PUT reserve: 201 thành công, hoặc 4xx/5xx kèm error code contract §4.2. */
    public record ReserveResult(int status, String errorCode, UUID reservationId, String state, Integer version) {
        static ReserveResult created(UUID reservationId, int version) {
            return new ReserveResult(201, null, reservationId, "RESERVED", version);
        }

        static ReserveResult error(int status, String code) {
            return new ReserveResult(status, code, null, null, null);
        }
    }

    /** Kết quả GET (contract §4.2): 200 → state + identity; 404 → empty. */
    public record ReservationStatus(UUID reservationId, String state, UUID tripId, UUID driverId, int attempt) {
    }

    private final RestClient rest;
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

    public DriverReservationClient(@Qualifier("driverServiceRestClient") RestClient rest) {
        this.rest = rest;
    }

    /** PUT RESERVE (contract §4.2). 201 → created; 4xx/5xx → error code; timeout/mạng → RuntimeException. */
    public ReserveResult reserve(UUID reservationId, Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = rest.put()
                    .uri("/internal/reservations/{id}", reservationId)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (resp == null || !(resp.get("state") instanceof String s)) {
                throw new IllegalStateException("driver reserve response missing 'state'");
            }
            Number v = resp.get("version") instanceof Number n ? n : null;
            return ReserveResult.created(reservationId, v == null ? 1 : v.intValue());
        } catch (RestClientResponseException e) {
            String code = parseErrorCode(e);
            if (code == null) {
                throw new IllegalStateException("driver reserve error without 'code': " + e.getStatusCode(), e);
            }
            return ReserveResult.error(e.getStatusCode().value(), code);
        }
    }

    /** GET reservation status (contract §4.2): 200 → status; 404 → empty; timeout/mạng → RuntimeException. */
    public Optional<ReservationStatus> get(UUID reservationId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = rest.get()
                    .uri("/internal/reservations/{id}", reservationId)
                    .retrieve()
                    .body(Map.class);
            if (resp == null) {
                return Optional.empty();
            }
            UUID rid = parseUuid(resp.get("reservationId"));
            String state = resp.get("state") instanceof String s ? s : null;
            if (rid == null || state == null) {
                // Tombstone: field trip/driver/attempt chưa biết → coi như không xác nhận được.
                return Optional.empty();
            }
            UUID tripId = parseUuid(resp.get("tripId"));
            UUID driverId = parseUuid(resp.get("driverId"));
            Number attemptRaw = resp.get("attempt") instanceof Number n ? n : null;
            if (tripId == null || driverId == null || attemptRaw == null) {
                return Optional.empty();
            }
            return Optional.of(new ReservationStatus(rid, state, tripId, driverId, attemptRaw.intValue()));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty(); // chưa từng biết reservationId (§4.2)
            }
            // Preserve the transport exception type so confirm/finish reconciliation remains pending.
            throw e;
        }
    }

    /** T18–T20 — kết quả lệnh vòng đời (confirm/release): 200 kèm state, hoặc 4xx kèm error code (§4.2). */
    public record CommandResult(int status, String errorCode, String state) {
        public boolean ok() {
            return status == 200;
        }
    }

    /** T18 — POST confirm (contract §4.2). Timeout/mạng → RestClientException (caller reconcile bằng GET). */
    public CommandResult confirm(UUID reservationId, Map<String, Object> body) {
        return lifecycle(reservationId, "confirm", body);
    }

    /** T19/T20 — POST release (contract §4.2). Release reservation chưa từng tồn tại tạo tombstone. */
    public CommandResult release(UUID reservationId, Map<String, Object> body) {
        return lifecycle(reservationId, "release", body);
    }

    /** T22 — POST finish (contract §4.2): giải phóng driver sau khi trip COMPLETED.
     *  Idempotent: retry trên row RELEASED/TRIP_COMPLETED cùng trip trả 200 không giải phóng lại. */
    public CommandResult finish(UUID reservationId, Map<String, Object> body) {
        return lifecycle(reservationId, "finish", body);
    }

    private CommandResult lifecycle(UUID reservationId, String action, Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = rest.post()
                    .uri("/internal/reservations/{id}/" + action, reservationId)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            String state = resp != null && resp.get("state") instanceof String s ? s : null;
            if (state == null) {
                throw new IllegalStateException("driver " + action + " response missing 'state'");
            }
            return new CommandResult(200, null, state);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                // 5xx: kết quả không rõ như timeout — để caller reconcile bằng GET.
                throw e;
            }
            String code = parseErrorCode(e);
            if (code == null) {
                throw new IllegalStateException("driver " + action + " error without 'code': " + e.getStatusCode(), e);
            }
            return new CommandResult(e.getStatusCode().value(), code, null);
        }
    }

    /**
     * T19/T20 — chỉ đọc state của reservation (kể cả tombstone, vốn không có trip/driver/attempt):
     * 200 → state; 404 → empty. Dùng để xác nhận release đã terminal sau timeout.
     */
    public Optional<String> getState(UUID reservationId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = rest.get()
                    .uri("/internal/reservations/{id}", reservationId)
                    .retrieve()
                    .body(Map.class);
            return resp != null && resp.get("state") instanceof String s ? Optional.of(s) : Optional.empty();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Parse field {@code code} của ApiError (contract §1.2: {code,message,correlationId,details}).
     *  Spring Framework 7: RestClientResponseException dùng getResponseBodyAsByteArray(). */
    private String parseErrorCode(RestClientResponseException e) {
        try {
            byte[] body = e.getResponseBodyAsByteArray();
            if (body == null || body.length == 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(body, Map.class);
            return parsed.get("code") instanceof String c ? c : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static UUID parseUuid(Object o) {
        if (!(o instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
