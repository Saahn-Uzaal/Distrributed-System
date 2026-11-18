CREATE TABLE location_search_results (
    token UUID PRIMARY KEY,
    passenger_id UUID NOT NULL,
    provider_place_id TEXT NOT NULL,
    display_name TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 500),
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_location_results_owner_expiry ON location_search_results(passenger_id, expires_at);

CREATE TABLE trip_quotes (
    id UUID PRIMARY KEY,
    passenger_id UUID NOT NULL,
    pickup_token UUID NOT NULL REFERENCES location_search_results(token),
    destination_token UUID NOT NULL REFERENCES location_search_results(token),
    distance_m BIGINT NOT NULL CHECK (distance_m > 0),
    duration_s BIGINT NOT NULL CHECK (duration_s >= 0),
    fare_vnd BIGINT NOT NULL CHECK (fare_vnd > 0),
    route_geometry JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    trip_id UUID
);
CREATE INDEX idx_trip_quotes_owner_expiry ON trip_quotes(passenger_id, expires_at);

ALTER TABLE trips ADD COLUMN pickup_address TEXT;
ALTER TABLE trips ADD COLUMN destination_address TEXT;
ALTER TABLE trips ADD COLUMN route_duration_s BIGINT;
ALTER TABLE trips ADD COLUMN route_geometry JSONB;

UPDATE trips SET
    pickup_address = pickup_lat::text || ', ' || pickup_lng::text,
    destination_address = destination_lat::text || ', ' || destination_lng::text,
    route_duration_s = 0,
    route_geometry = '{"type":"LineString","coordinates":[]}'::jsonb
WHERE pickup_address IS NULL;

ALTER TABLE trips ALTER COLUMN pickup_address SET NOT NULL;
ALTER TABLE trips ALTER COLUMN destination_address SET NOT NULL;
ALTER TABLE trips ALTER COLUMN route_duration_s SET NOT NULL;
ALTER TABLE trips ALTER COLUMN route_geometry SET NOT NULL;
