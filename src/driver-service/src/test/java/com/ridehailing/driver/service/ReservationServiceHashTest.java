package com.ridehailing.driver.service;

import com.ridehailing.driver.web.dto.ReserveRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** T10-5 (R01): canonical double trong requestHash — -0.0 chuẩn hóa thành 0.0 trước khi băm. */
class ReservationServiceHashTest {

    private final UUID commandId = UUID.randomUUID();
    private final UUID tripId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();

    /** Cùng identity (command/trip/driver) — chỉ tọa độ pickup thay đổi giữa các lần gọi. */
    private ReserveRequest cmd(double pickupLat, double pickupLng) {
        return new ReserveRequest(commandId, 1, tripId, driverId, 1,
                Instant.parse("2026-10-21T12:00:00Z"),
                new ReserveRequest.GeoPoint(pickupLat, pickupLng),
                new ReserveRequest.GeoPoint(10.8, 106.72), 3316L, 32000L);
    }

    @Test
    void negativeZeroIsCanonicalizedToPositiveZero() {
        assertThat(ReservationService.requestHash(cmd(-0.0, -0.0)))
                .isEqualTo(ReservationService.requestHash(cmd(0.0, 0.0)));
    }

    @Test
    void differentCoordinatesProduceDifferentHashes() {
        assertThat(ReservationService.requestHash(cmd(10.7769, 106.7009)))
                .isNotEqualTo(ReservationService.requestHash(cmd(10.7769, 106.7010)));
    }

    @Test
    void canonicalDoubleKeepsExactDoubleToStringForNonZeroValues() {
        // Chỉ -0.0/0.0 bị chuẩn hóa; các giá trị khác giữ đúng Double.toString (hash cũ ổn định).
        assertThat(ReservationService.canonicalDouble(-0.0)).isEqualTo("0.0");
        assertThat(ReservationService.canonicalDouble(0.0)).isEqualTo("0.0");
        assertThat(ReservationService.canonicalDouble(-0.25)).isEqualTo("-0.25");
        assertThat(ReservationService.canonicalDouble(10.7769)).isEqualTo("10.7769");
    }
}
