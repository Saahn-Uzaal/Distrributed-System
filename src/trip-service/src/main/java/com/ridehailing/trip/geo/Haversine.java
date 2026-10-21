package com.ridehailing.trip.geo;

/**
 * T12 — khoảng cách haversine trên mặt cầu (contract api-contracts.md §3.3:
 * distanceM = round(haversine(pickup, destination)), meter). Nhân bản từ driver-service (T09)
 * theo quy tắc "class nhỏ nhân bản theo service, không module chung" (chốt T07).
 * Bán kính Trái Đất IUGG mean: 6_371_008.8 m.
 */
public final class Haversine {

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    private Haversine() {
    }

    /** Khoảng cách tròn (meter) giữa hai tọa độ (độ). */
    public static long meters(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        // T09-2 (F11): clamp a vào [0, 1] trước atan2 — điểm đối cực cho a = 1 đúng
        // (pi/2*2 -> pi*R), không phải 0 m; sai số float có thể đẩy a ra ngoài miền.
        a = Math.min(1.0, Math.max(0.0, a));
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        if (!Double.isFinite(c)) {
            throw new IllegalArgumentException("haversine result is not finite for the given coordinates");
        }
        return Math.round(EARTH_RADIUS_M * c);
    }
}
