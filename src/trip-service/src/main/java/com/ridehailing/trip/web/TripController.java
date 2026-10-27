package com.ridehailing.trip.web;

import com.ridehailing.trip.security.JwtAuthenticationFilter;
import com.ridehailing.trip.security.AuthenticatedUser;
import com.ridehailing.trip.security.CorrelationIdFilter;
import com.ridehailing.trip.service.TripCommandService;
import com.ridehailing.trip.service.TripService;
import com.ridehailing.trip.web.dto.AttemptRequest;
import com.ridehailing.trip.web.dto.QuoteRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * T12 — public endpoints trip (contract api-contracts.md §3.3): quote, create (idempotent), list, get.
 * Auth/role đã enforce bởi SecurityFilterChain (T07-1): POST quote/create -> PASSENGER; GET mọi role xác thực.
 * T18–T22: accept/reject/cancel/start/complete ủy quyền cho TripCommandService.
 */
@RestController
@RequestMapping("/api/trips")
public class TripController {

    private final TripService tripService;
    private final TripCommandService commandService;

    public TripController(TripService tripService, TripCommandService commandService) {
        this.tripService = tripService;
        this.commandService = commandService;
    }

    /** T18 — POST /api/trips/{id}/accept (chỉ offered driver; role DRIVER enforce ở SecurityConfig). */
    @PostMapping("/{id}/accept")
    public ResponseEntity<Map<String, Object>> accept(
            @RequestAttribute(JwtAuthenticationFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestBody AttemptRequest body) {
        return toResponse(commandService.accept(id, user, body == null ? null : body.attempt()));
    }

    /** T19 — POST /api/trips/{id}/reject (chỉ offered driver; role DRIVER enforce ở SecurityConfig). */
    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(
            @RequestAttribute(JwtAuthenticationFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedUser user,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) String correlationId,
            @PathVariable UUID id,
            @RequestBody AttemptRequest body) {
        return toResponse(commandService.reject(id, user, body == null ? null : body.attempt(),
                UUID.fromString(correlationId)));
    }

    /** T20 — POST /api/trips/{id}/cancel (chỉ owner passenger; role PASSENGER enforce ở SecurityConfig). */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @RequestAttribute(JwtAuthenticationFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable UUID id) {
        return toResponse(commandService.cancel(id, user));
    }

    /** T22 — POST /api/trips/{id}/start (chỉ assigned driver; role DRIVER enforce ở SecurityConfig). */
    @PostMapping("/{id}/start")
    public ResponseEntity<Map<String, Object>> start(
            @RequestAttribute(JwtAuthenticationFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable UUID id) {
        return toResponse(commandService.start(id, user));
    }

    /** T22 — POST /api/trips/{id}/complete (chỉ assigned driver; role DRIVER enforce ở SecurityConfig). */
    @PostMapping("/{id}/complete")
    public ResponseEntity<Map<String, Object>> complete(
            @RequestAttribute(JwtAuthenticationFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedUser user,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) String correlationId,
            @PathVariable UUID id) {
        return toResponse(commandService.complete(id, user, UUID.fromString(correlationId)));
    }

    private static ResponseEntity<Map<String, Object>> toResponse(TripCommandService.CommandOutcome outcome) {
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }

    /** POST /api/trips/quote — 200 {distanceM, fareVnd}; không tạo trip. */
    @PostMapping("/quote")
    public ResponseEntity<Map<String, Object>> quote(@RequestBody QuoteRequest req) {
        return ResponseEntity.ok(tripService.quote(req));
    }

    /** POST /api/trips — 202 + Location; Idempotency-Key bắt buộc (400 nếu thiếu/sai UUID). */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestAttribute(JwtAuthenticationFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(CorrelationIdFilter.ATTRIBUTE) String correlationId,
            @RequestBody QuoteRequest req) {
        TripService.CreateOutcome outcome = tripService.create(user, idempotencyKey, req, UUID.fromString(correlationId));
        return ResponseEntity.status(outcome.httpStatus())
                .header("Location", "/api/trips/" + outcome.body().get("tripId"))
                .body(outcome.body());
    }

    /** GET /api/trips — phân trang §1.3; passenger thấy chuyến của mình, driver thấy chuyến đã assign. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestAttribute(JwtAuthenticationFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(tripService.list(user, page, size));
    }

    /** GET /api/trips/{id} — owner hoặc assigned driver; không thì 404 (chính sách §1.2). */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(
            @RequestAttribute(JwtAuthenticationFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable UUID id) {
        return ResponseEntity.ok(tripService.get(id, user));
    }

    /** T25 — GET /api/trips/{id}/events (contract §3.3): owner hoặc assigned driver, phân trang,
     *  sắp version DESC; không thì 404 (chính sách §1.2). */
    @GetMapping("/{id}/events")
    public ResponseEntity<Map<String, Object>> events(
            @RequestAttribute(JwtAuthenticationFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(tripService.events(id, user, page, size));
    }
}
