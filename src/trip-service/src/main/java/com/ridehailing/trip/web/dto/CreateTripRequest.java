package com.ridehailing.trip.web.dto;

import java.util.UUID;

/** quoteToken is the production flow; coordinate fields remain for existing scripted regression fixtures. */
public record CreateTripRequest(UUID quoteToken, QuoteRequest.Point pickup, QuoteRequest.Point destination) {}
