package com.ridehailing.driver.service;

import com.ridehailing.driver.exception.InvalidInputException;
import com.ridehailing.driver.exception.NotFoundException;
import com.ridehailing.driver.exception.PlateTakenException;
import com.ridehailing.driver.exception.StateConflictException;
import com.ridehailing.driver.geo.Haversine;
import com.ridehailing.driver.repo.DriverRepository;
import com.ridehailing.driver.repo.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T09 — nghiệp vụ driver profile/availability/location/candidates (contract api-contracts.md §3.2/§4.2).
 * Chưa reserve/matching orchestration (T10+).
 */
@Service
public class DriverService {

    public record Profile(UUID driverId, String displayName, Vehicle vehicle, String status) {
        public record Vehicle(String plateNumber, String vehicleType) {
        }
    }

    public record AvailabilityResult(UUID driverId, String status, Instant lastSeenAt) {
    }

    public record LocationResult(UUID driverId, Instant lastSeenAt) {
    }

    public record Candidate(UUID driverId, UUID driverUserId, long distanceM) {
    }

    private static final int MAX_CANDIDATES = 5;

    /** N11 — audit log: driver_availability_changed; không ghi secret. */
    private static final Logger log = LoggerFactory.getLogger(DriverService.class);

    private final DriverRepository repo;
    private final ReservationRepository reservationRepo;
    private final long heartbeatStaleAfterMs;

    public DriverService(DriverRepository repo, ReservationRepository reservationRepo,
                         @Value("${driver.heartbeat-stale-after-ms:15000}") long heartbeatStaleAfterMs) {
        this.repo = repo;
        this.reservationRepo = reservationRepo;
        this.heartbeatStaleAfterMs = heartbeatStaleAfterMs;
    }

    /** POST /api/drivers/me/profile — idempotent theo userId (chốt §3.2).
     * T09-4 (N31): plate chuẩn hóa (trim + uppercase + gộp khoảng trắng) trước khi kiểm tra/lưu;
     * displayName lưu đã trim (như Identity với fullName). */
    @Transactional
    public ProfileResult createProfile(UUID userId, String displayName, String plateNumber, String vehicleType) {
        String normalizedPlate = normalizePlate(plateNumber);
        String trimmedName = displayName == null ? null : displayName.trim();
        validateProfileInput(trimmedName, normalizedPlate, vehicleType);
        // Serialize creation by authenticated user, including the first request with no row yet.
        repo.lockProfileUser(userId);
        var existing = repo.findByUserId(userId);
        if (existing.isPresent()) {
            // Gọi lại: trả profile đã tạo, KHÔNG cập nhật body mới.
            return new ProfileResult(existing.get().id(), false, toProfile(existing.get()));
        }
        try {
            DriverRepository.VehicleRow vehicle = repo.insertVehicle(normalizedPlate, vehicleType);
            DriverRepository.DriverRow driver = repo.insertDriver(userId, trimmedName, vehicle.id());
            return new ProfileResult(driver.id(), true, toProfile(driver));
        } catch (DataIntegrityViolationException e) {
            if (isPlateUniqueViolation(e)) {
                throw new PlateTakenException("Vehicle plate number is already taken.");
            }
            throw e;
        }
    }

    public record ProfileResult(UUID driverId, boolean created, Profile profile) {
    }

    /** PUT /api/drivers/me/availability — CAS theo state machine §4. */
    @Transactional
    public AvailabilityResult setAvailability(UUID userId, String status) {
        if (!"AVAILABLE".equals(status) && !"OFFLINE".equals(status)) {
            throw new InvalidInputException("status must be AVAILABLE or OFFLINE.");
        }
        DriverRepository.DriverRow driver = repo.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Driver profile not found."));
        // T09-1 (F07): DB gán last_seen_at sau khi giành khóa row; trả giá trị thực đã lưu.
        java.util.Optional<Instant> updated = repo.updateAvailabilityIfFree(driver.id(), status);
        if (updated.isEmpty()) {
            // State hiện tại RESERVED/BUSY — mọi request availability đều 409 (chốt T09).
            // T09-3 (F13): đọc LẠI sau CAS thất bại để lấy state/tripId mới nhất
            // (tripId từ current_trip_id, hoặc từ reservation của current_reservation_id nếu RESERVED).
            DriverRepository.DriverRow fresh = repo.findById(driver.id())
                    .orElseThrow(() -> new NotFoundException("Driver profile not found."));
            UUID tripId = fresh.currentTripId();
            if (tripId == null && fresh.currentReservationId() != null) {
                tripId = reservationRepo.findById(fresh.currentReservationId())
                        .map(ReservationRepository.ReservationRow::tripId)
                        .orElse(null);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("state", fresh.status());
            details.put("tripId", tripId);
            throw new StateConflictException(
                    "Driver is not free to change availability while " + fresh.status() + ".", details);
        }
        // N11 audit: driver_availability_changed — chỉ userId + status mới.
        MDC.put("eventType", "driver_availability_changed");
        MDC.put("result", "SUCCESS");
        log.info("driver availability changed userId={} status={}", userId, status);
        return new AvailabilityResult(driver.id(), status, updated.get());
    }

    /** PUT /api/drivers/me/location — cập nhật tọa độ + heartbeat; mọi state (chốt §3.2). */
    @Transactional
    public LocationResult updateLocation(UUID userId, Double latitude, Double longitude) {
        validateCoordinates(latitude, longitude);
        DriverRepository.DriverRow driver = repo.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Driver profile not found."));
        // T09-1 (F07): DB gán last_seen_at sau khi giành khóa row; trả giá trị thực đã lưu.
        java.util.Optional<Instant> updated = repo.updateLocation(driver.id(), latitude, longitude);
        if (updated.isEmpty()) {
            throw new NotFoundException("Driver profile not found.");
        }
        return new LocationResult(driver.id(), updated.get());
    }

    /** GET /internal/drivers/candidates — AVAILABLE + heartbeat mới, deterministic order. */
    public List<Candidate> findCandidates(double lat, double lng, int radiusM, int limit) {
        validateCoordinates(lat, lng);
        if (radiusM <= 0 || !Double.isFinite((double) radiusM)) {
            throw new InvalidInputException("radiusM must be a positive number.");
        }
        if (limit < 1) {
            throw new InvalidInputException("limit must be at least 1.");
        }
        int capped = Math.min(limit, MAX_CANDIDATES);

        // T09-5 (R05): bounding box + LIMIT trong SQL; cutoff heartbeat tính bằng clock_timestamp()
        // trong chính câu SQL (cùng nguồn đồng hồ với CAS reserve — T10-1/R04).
        int fetchLimit = Math.max(capped * 4, 20);
        List<DriverRepository.DriverRow> rows = repo.findCandidatesInBox(lat, lng, radiusM,
                heartbeatStaleAfterMs, fetchLimit);
        List<Candidate> candidates = new ArrayList<>();
        for (DriverRepository.DriverRow row : rows) {
            long distanceM = Haversine.meters(lat, lng, row.latitude(), row.longitude());
            if (distanceM <= radiusM) {
                candidates.add(new Candidate(row.id(), row.userId(), distanceM));
            }
        }
        // Deterministic: distanceM tăng; tie-break driverId lexicographic (chốt §4.2).
        candidates.sort(Comparator.comparingLong(Candidate::distanceM)
                .thenComparing(c -> c.driverId().toString()));
        return candidates.stream().limit(capped).toList();
    }

    /** T09-4 (N31): chuẩn hóa biển số — trim, uppercase, gộp chuỗi khoảng trắng thành một. */
    static String normalizePlate(String raw) {
        if (raw == null) return null;
        return raw.trim().toUpperCase().replaceAll("\\s+", " ");
    }

    private static void validateProfileInput(String displayName, String plateNumber, String vehicleType) {
        // displayName/plate đã được chuẩn hóa trước khi gọi vào đây (T09-4).
        if (displayName == null || displayName.length() < 2 || displayName.length() > 60) {
            throw new InvalidInputException("displayName must be 2-60 characters.");
        }
        // F05 (T06-1): từ chối ký tự điều khiển (\p{Cc}, đặc biệt \u0000) — PostgreSQL sẽ lỗi → 500.
        if (displayName.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidInputException("displayName must not contain control characters.");
        }
        if (plateNumber == null || plateNumber.length() < 3 || plateNumber.length() > 20
                || !plateNumber.matches("^[A-Z0-9.\\- ]+$")) {
            throw new InvalidInputException(
                    "plateNumber must be 3-20 characters of [A-Z0-9.-space].");
        }
        if (!"MOTORBIKE".equals(vehicleType) && !"CAR".equals(vehicleType)) {
            throw new InvalidInputException("vehicleType must be MOTORBIKE or CAR.");
        }
    }

    private static void validateCoordinates(Double latitude, Double longitude) {
        if (latitude == null || !Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new InvalidInputException("latitude must be a finite number in [-90, 90].");
        }
        if (longitude == null || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new InvalidInputException("longitude must be a finite number in [-180, 180].");
        }
    }

    private static boolean isPlateUniqueViolation(DataIntegrityViolationException e) {
        // PostgreSQL: message chứa tên unique key. V5 đổi constraint thành expression index
        // uq_vehicles_plate_upper; giữ cả hai chuỗi để an toàn với dữ liệu/schema cũ.
        String msg = e.getMessage() == null ? "" : e.getMessage();
        return msg.contains("uq_vehicles_plate_upper") || msg.contains("vehicles_plate_number");
    }

    private Profile toProfile(DriverRepository.DriverRow driver) {
        DriverRepository.VehicleRow vehicle = repo.findVehicle(driver.vehicleId());
        return new Profile(driver.id(), driver.displayName(),
                new Profile.Vehicle(vehicle.plateNumber(), vehicle.vehicleType()), driver.status());
    }
}
