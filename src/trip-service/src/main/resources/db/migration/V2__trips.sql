-- T12 — trips + outbox (ý định matching pending) + trip_events + idempotency keys HTTP.
-- Contract: api-contracts.md §3.3/§3.5; state-machines.md §1; events.md §2/§3.1/§4.6.
-- Chốt T12: outbox schema tạo ở T12 (ghi intent TripRequested pending cùng transaction với trip);
-- publisher worker thuộc T13. Saga row (WAITING_MATCH) thuộc T16, notifications thuộc T25 — chưa ghi ở đây.

CREATE TABLE IF NOT EXISTS trips (
    id UUID PRIMARY KEY,
    passenger_id UUID NOT NULL,
    pickup_lat DOUBLE PRECISION NOT NULL CHECK (pickup_lat BETWEEN -90 AND 90),
    pickup_lng DOUBLE PRECISION NOT NULL CHECK (pickup_lng BETWEEN -180 AND 180),
    destination_lat DOUBLE PRECISION NOT NULL CHECK (destination_lat BETWEEN -90 AND 90),
    destination_lng DOUBLE PRECISION NOT NULL CHECK (destination_lng BETWEEN -180 AND 180),
    distance_m BIGINT NOT NULL CHECK (distance_m >= 0),
    fare_vnd BIGINT NOT NULL CHECK (fare_vnd > 0),
    state TEXT NOT NULL DEFAULT 'SEARCHING'
        CHECK (state IN ('SEARCHING','DRIVER_OFFERED','CANCELLING','ACCEPTED','IN_PROGRESS',
                         'COMPLETED','CANCELLED','NO_DRIVER')),
    matching_attempt INT NOT NULL DEFAULT 1 CHECK (matching_attempt >= 1),
    driver_id UUID,
    payment_status TEXT NOT NULL DEFAULT 'NOT_REQUESTED'
        CHECK (payment_status IN ('NOT_REQUESTED','PENDING','SUCCEEDED','FAILED')),
    payment_attempt INT NOT NULL DEFAULT 0 CHECK (payment_attempt >= 0),
    version INT NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_trips_passenger ON trips (passenger_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_trips_driver ON trips (driver_id) WHERE driver_id IS NOT NULL;

-- Outbox producer (events.md §4.6): id = eventId ổn định khi publish lại.
-- T12 chỉ ghi row PENDING; worker publish + đánh dấu SENT thuộc T13.
CREATE TABLE IF NOT EXISTS outbox (
    id UUID PRIMARY KEY,
    event_type TEXT NOT NULL CHECK (event_type IN ('TripRequested','DriverProposed','NoDriverFound',
                                                   'TripCompleted','PaymentSucceeded','PaymentFailed')),
    aggregate_id UUID NOT NULL,
    payload JSONB NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT')),
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox (created_at) WHERE status = 'PENDING';

-- Trip event history (api-contracts.md §3.5): ghi tại mỗi transition; version = trips.version lúc đó.
CREATE TABLE IF NOT EXISTS trip_events (
    id BIGSERIAL PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trips(id),
    type TEXT NOT NULL CHECK (type IN ('TRIP_CREATED','DRIVER_OFFERED','OFFER_REJECTED','OFFER_EXPIRED',
                                       'RESERVATION_RELEASED','TRIP_ACCEPTED','TRIP_STARTED','TRIP_COMPLETED',
                                       'CANCELLING','TRIP_CANCELLED','NO_DRIVER','PAYMENT_SUCCEEDED','PAYMENT_FAILED')),
    version INT NOT NULL CHECK (version >= 1),
    actor_id UUID,
    summary TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_trip_events_trip ON trip_events (trip_id, version DESC);

-- Idempotency HTTP cho POST /api/trips (contract §3.3): key UUID + request_hash + response gốc đã lưu.
CREATE TABLE IF NOT EXISTS idempotency_keys (
    key UUID PRIMARY KEY,
    request_hash TEXT NOT NULL,
    stored_response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
