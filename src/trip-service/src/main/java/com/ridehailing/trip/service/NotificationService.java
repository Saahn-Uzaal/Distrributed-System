package com.ridehailing.trip.service;

import com.ridehailing.trip.exception.InvalidInputException;
import com.ridehailing.trip.repo.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** T25 — notifications (spec §20.5, api-contracts.md §3.4): ghi tại các transition đã có,
 * trong CÙNG transaction với trip_event tương ứng (atomic rollback — không có notification
 * mà thiếu transition của nó).
 *
 * <p>Chốt thiết kế T25:
 * <ul>
 *   <li><b>Recipient mapping</b> (spec chỉ chốt DRIVER_OFFERED → cả hai, bước 4 flow):
 *       TRIP_OFFERED → passenger; OFFER_RECEIVED → driver; TRIP_ACCEPTED/TRIP_COMPLETED → cả hai;
 *       TRIP_CANCELLED → passenger luôn + driver nếu trip đã có offered/assigned driver lúc hủy
 *       (đọc row TRƯỚC khi markCancelled NULL-out); NO_DRIVER/PAYMENT_* → passenger.</li>
 *   <li><b>Dedup theo transition</b>: unique {@code (user_id, trip_id, version, type)} + ON CONFLICT
 *       DO NOTHING — retry/redelivery của cùng một transition không tạo thông báo trùng. Cùng user
 *       nhận cùng type ở các attempt khác nhau là hợp lệ vì version khác.</li>
 *   <li><b>MVP không có endpoint đánh dấu đã đọc</b> (contract §3.4 chốt); {@code read_at} giữ null.</li>
 * </ul>
 */
@Service
public class NotificationService {

    private final NotificationRepository repo;

    public NotificationService(NotificationRepository repo) {
        this.repo = repo;
    }

    /** Ghi notification cho MỘT user tại version transition đã bump (idempotent theo unique index). */
    public void notify(UUID userId, UUID tripId, String type, int version) {
        repo.insert(userId, tripId, type, version, messageFor(type));
    }

    static String messageFor(String type) {
        return switch (type) {
            case "TRIP_OFFERED" -> "Tài xế đã sẵn sàng đón bạn."; // contract §3.4 example
            case "OFFER_RECEIVED" -> "Bạn có một chuyến đi mới cần đón khách.";
            case "TRIP_ACCEPTED" -> "Chuyến đi đã được chấp nhận.";
            case "TRIP_CANCELLED" -> "Chuyến đi đã bị hủy.";
            case "NO_DRIVER" -> "Không tìm thấy tài xế cho chuyến đi này.";
            case "TRIP_COMPLETED" -> "Chuyến đi đã hoàn thành.";
            case "PAYMENT_SUCCEEDED" -> "Thanh toán thành công.";
            case "PAYMENT_FAILED" -> "Thanh toán thất bại.";
            default -> throw new IllegalArgumentException("Unknown notification type: " + type);
        };
    }

    /** GET /api/notifications — phân trang §1.3, sắp created_at DESC (§3.4), chỉ caller.
     *  REPEATABLE_READ như TripService.list (T12-5): count và content đọc cùng một snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(UUID userId, int page, int size) {
        if (page < 0 || size < 1) {
            throw new InvalidInputException("page must be >= 0 and size must be >= 1.");
        }
        int effectiveSize = Math.min(size, 100); // max 100 — lớn hơn bị clamp (§1.3)
        long total = repo.countForUser(userId);
        List<Map<String, Object>> content = repo.listForUser(userId, (long) page * effectiveSize, effectiveSize).stream()
                .map(NotificationService::item).toList();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("content", content);
        envelope.put("page", page);
        envelope.put("size", effectiveSize);
        envelope.put("totalElements", total);
        envelope.put("totalPages", (int) ((total + effectiveSize - 1) / effectiveSize));
        return envelope;
    }

    private static Map<String, Object> item(NotificationRepository.NotificationRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("notificationId", row.notificationId().toString());
        m.put("tripId", row.tripId().toString());
        m.put("type", row.type());
        m.put("message", row.message());
        m.put("readAt", row.readAt() == null ? null : row.readAt().truncatedTo(ChronoUnit.SECONDS));
        m.put("createdAt", row.createdAt().truncatedTo(ChronoUnit.SECONDS));
        return m;
    }
}
