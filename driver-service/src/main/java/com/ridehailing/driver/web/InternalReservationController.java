package com.ridehailing.driver.web;

import com.ridehailing.driver.service.ReservationService;
import com.ridehailing.driver.web.dto.ReserveRequest;
import com.ridehailing.driver.web.dto.ConfirmRequest;
import com.ridehailing.driver.web.dto.ReleaseRequest;
import com.ridehailing.driver.web.dto.FinishRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * T10 — internal API reservation (contract api-contracts.md §4.2). Credential X-Internal-Token
 * enforce bởi ApiSecurityFilter (T07); trip-service gọi endpoint này ở T16.
 */
@RestController
@RequestMapping("/internal/reservations")
public class InternalReservationController {

    private final ReservationService service;

    public InternalReservationController(ReservationService service) {
        this.service = service;
    }

    /** RESERVE — 201 lần đầu; replay cùng commandId+hash trả status/body gốc (chốt §4.1). */
    @PutMapping("/{reservationId}")
    public ResponseEntity<java.util.Map<String, Object>> reserve(@PathVariable UUID reservationId,
                                                                 @RequestBody ReserveRequest request) {
        ReservationService.ReserveOutcome outcome = service.reserve(reservationId, request);
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }

    /** Đọc trạng thái sau timeout (chốt §4.2) — 404 nếu chưa từng biết reservationId. */
    @GetMapping("/{reservationId}")
    public ReservationService.ReservationView get(@PathVariable UUID reservationId) {
        return service.get(reservationId);
    }

    @PostMapping("/{reservationId}/confirm")
    public ResponseEntity<java.util.Map<String, Object>> confirm(@PathVariable UUID reservationId,
                                                               @RequestBody ConfirmRequest request) {
        return outcome(service.confirm(reservationId, request));
    }

    @PostMapping("/{reservationId}/release")
    public ResponseEntity<java.util.Map<String, Object>> release(@PathVariable UUID reservationId,
                                                               @RequestBody ReleaseRequest request) {
        return outcome(service.release(reservationId, request));
    }

    @PostMapping("/{reservationId}/finish")
    public ResponseEntity<java.util.Map<String, Object>> finish(@PathVariable UUID reservationId,
                                                              @RequestBody FinishRequest request) {
        return outcome(service.finish(reservationId, request));
    }

    private ResponseEntity<java.util.Map<String, Object>> outcome(ReservationService.ReserveOutcome result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
