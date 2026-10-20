-- Keep existing CREATE_TRIP replay hashes/responses and recover their authenticated owner
-- from the trip referenced by the stored response. Unresolvable replay data fails migration
-- at SET NOT NULL rather than allowing another principal to receive it.
ALTER TABLE idempotency_keys ADD COLUMN principal_id UUID;
ALTER TABLE idempotency_keys ADD COLUMN operation TEXT NOT NULL DEFAULT 'CREATE_TRIP';

UPDATE idempotency_keys AS replay
SET principal_id = trip.passenger_id
FROM trips AS trip
WHERE trip.id = (replay.stored_response #>> '{body,tripId}')::uuid;

ALTER TABLE idempotency_keys ALTER COLUMN principal_id SET NOT NULL;
ALTER TABLE idempotency_keys ALTER COLUMN operation DROP DEFAULT;
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ADD PRIMARY KEY (principal_id, operation, key);

-- driver_id is the Driver service profile UUID; driver_user_id is its JWT subject.
-- T12 has no assignment handler, so valid existing trips have both fields NULL.
-- A legacy manually assigned profile without an identity must be repaired explicitly;
-- its user identity cannot be inferred safely inside the Trip service database.
ALTER TABLE trips ADD COLUMN driver_user_id UUID;
ALTER TABLE trips ADD CONSTRAINT trips_driver_identity_complete
    CHECK ((driver_id IS NULL) = (driver_user_id IS NULL));
CREATE INDEX idx_trips_driver_user ON trips (driver_user_id, created_at DESC)
    WHERE driver_user_id IS NOT NULL;
