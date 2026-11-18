package com.ridehailing.trip.web.dto;

import java.util.UUID;

public record RouteQuoteRequest(UUID pickupLocationToken, UUID destinationLocationToken) {}
