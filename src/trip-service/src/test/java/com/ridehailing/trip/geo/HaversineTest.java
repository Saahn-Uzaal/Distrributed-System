package com.ridehailing.trip.geo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T09-2 (F11) — Haversine: điểm đối cực phải cho ~pi*R, không phải 0 m. */
class HaversineTest {

    @Test
    void exactAntipodeGivesHalfCircumference() {
        // (0,0) và (0,180): c = pi -> round(pi * 6_371_008.8).
        assertThat(Haversine.meters(0, 0, 0, 180)).isEqualTo(Math.round(Math.PI * 6_371_008.8));
    }

    @Test
    void nearAntipodalPointsGiveHalfCircumference() {
        // Điểm gần đối cực: kết quả vẫn ~20_015_114 m (không sụp về 0 do a > 1 float).
        assertThat(Haversine.meters(89.846, 14.397, -89.846, -165.602)).isEqualTo(20_015_114L);
    }

    @Test
    void identicalPointsGiveZero() {
        assertThat(Haversine.meters(10.7, 106.7, 10.7, 106.7)).isZero();
    }

    @Test
    void nonFiniteCoordinatesAreRejected() {
        assertThatThrownBy(() -> Haversine.meters(Double.NaN, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Haversine.meters(0, Double.POSITIVE_INFINITY, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
