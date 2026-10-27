package com.ridehailing.trip.web;

import com.ridehailing.trip.security.AuthenticatedUser;
import com.ridehailing.trip.security.JwtAuthenticationFilter;
import com.ridehailing.trip.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** T25 — GET /api/notifications (contract §3.4): mọi role đã xác thực, chỉ thông báo của caller. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** GET /api/notifications — phân trang §1.3, sắp created_at DESC (§3.4). */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestAttribute(JwtAuthenticationFilter.PRINCIPAL_ATTRIBUTE) AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(notificationService.list(user.userId(), page, size));
    }
}
