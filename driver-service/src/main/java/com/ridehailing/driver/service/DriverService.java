package com.ridehailing.driver.service;

import com.ridehailing.driver.exception.InvalidInputException;
import com.ridehailing.driver.exception.NotFoundException;
import com.ridehailing.driver.exception.PlateTakenException;
import com.ridehailing.driver.exception.StateConflictException;
import com.ridehailing.driver.geo.Haversine;
import com.ridehailing.driver.repo.DriverRepository;
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

    private final DriverRepository repo;
    private final long heartbeatStaleAfterMs;

    public DriverService(DriverRepository repo,
                         @Value("${driver.heartbeat-stale-after-ms:15000}") long heartbeatStaleAfterMs) {
        this.repo = repo;
        this.heartbeatStaleAfterMs = heartbeatStaleAfterMs;
    }

    /** POST /api/drivers/me/profile — idempotent theo userId (chốt §3.2). */
    @Transactional
    public ProfileResult createProfile(UUID userId, String displayName, String plateNumber, String vehicleType) {
        validateProfileInput(displayName, plateNumber, vehicleType);
        // Serialize creation by authenticated user, including the first request with no row yet.
        repo.lockProfileUser(userId);
        var existing = repo.findByUserId(userId);
        if (existing.isPresent()) {
            // Gọi lại: trả profile đã tạo, KHÔNG cập nhật body mới.
            return new ProfileResult(existing.get().id(), false, toProfile(existing.get()));
        }
        try {
            DriverRepository.VehicleRow vehicle = repo.insertVehicle(plateNumber, vehicleType);
            DriverRepository.DriverRow driver = repo.insertDriver(userId, displayName, vehicle.id());
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
        Instant now = Instant.now();
        int updated = repo.updateAvailabilityIfFree(driver.id(), status, now);
        if (updated == 0) {
            // State hiện tại RESERVED/BUSY — mọi request availability đều 409 (chốt T09).
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("state", driver.status());
            details.put("tripId", driver.currentTripId());
            throw new StateConflictException(
                    "Driver is not free to change availability while " + driver.status() + ".", details);
        }
        return new AvailabilityResult(driver.id(), status, now);
    }

    /** PUT /api/drivers/me/location — cập nhật tọa độ + heartbeat; mọi state (chốt §3.2). */
    @Transactional
    public LocationResult updateLocation(UUID userId, Double latitude, Double longitude) {
        validateCoordinates(latitude, longitude);
        DriverRepository.DriverRow driver = repo.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Driver profile not found."));
        Instant now = Instant.now();
        int updated = repo.updateLocation(driver.id(), latitude, longitude, now);
        if (updated == 0) {
            throw new NotFoundException("Driver profile not found.");
        }
        return new LocationResult(driver.id(), now);
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

        Instant cutoff = Instant.now().minusMillis(heartbeatStaleAfterMs);
        List<DriverRepository.DriverRow> rows = repo.findAvailableWithFreshHeartbeat(cutoff);
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

    private static void validateProfileInput(String displayName, String plateNumber, String vehicleType) {
        if (displayName == null || displayName.trim().length() < 2 || displayName.length() > 60) {
            throw new InvalidInputException("displayName must be 2-60 characters.");
        }
        if (plateNumber == null || plateNumber.length() < 3 || plateNumber.length() > 20) {
            throw new InvalidInputException("plateNumber must be 3-20 characters.");
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
        // PostgreSQL: message chứa tên unique key (vd 'violates unique constraint \"vehicles_plate_number_key\"').
        String msg = e.getMessage() == null ? "" : e.getMessage();
        return msg.contains("vehicles_plate_number");
    }

    private Profile toProfile(DriverRepository.DriverRow driver) {
        DriverRepository.VehicleRow vehicle = repo.findVehicle(driver.vehicleId());
        return new Profile(driver.id(), driver.displayName(),
                new Profile.Vehicle(vehicle.plateNumber(), vehicle.vehicleType()), driver.status());
    }
}
