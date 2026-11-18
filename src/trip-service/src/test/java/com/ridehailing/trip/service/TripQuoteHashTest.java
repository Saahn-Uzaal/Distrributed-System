package com.ridehailing.trip.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TripQuoteHashTest {
    @Test
    void quoteTokenIsTheOnlyCreatePayloadIncludedInTheHash() {
        UUID token = UUID.fromString("11111111-1111-1111-1111-111111111111");
        assertThat(TripService.requestHash(token)).isEqualTo(
                "a11c3bade535e3820c4dad4619fc9aea7b7f98a1ac86b27d052347edb5149fc4");
    }
}
