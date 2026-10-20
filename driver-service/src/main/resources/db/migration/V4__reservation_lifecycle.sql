-- T11: a release-before-reserve tombstone has no trip, driver or offer metadata yet.
-- Existing live reservations retain their completeness guarantees via the CHECK below.
ALTER TABLE reservations
    ALTER COLUMN trip_id DROP NOT NULL,
    ALTER COLUMN driver_id DROP NOT NULL,
    ALTER COLUMN attempt DROP NOT NULL,
    ALTER COLUMN expires_at DROP NOT NULL,
    ALTER COLUMN pickup_lat DROP NOT NULL,
    ALTER COLUMN pickup_lng DROP NOT NULL,
    ALTER COLUMN destination_lat DROP NOT NULL,
    ALTER COLUMN destination_lng DROP NOT NULL,
    ALTER COLUMN distance_m DROP NOT NULL,
    ALTER COLUMN fare_vnd DROP NOT NULL;

ALTER TABLE reservations ADD CONSTRAINT ck_reservations_complete_or_tombstone CHECK (
    (trip_id IS NOT NULL AND driver_id IS NOT NULL AND attempt IS NOT NULL
     AND expires_at IS NOT NULL AND pickup_lat IS NOT NULL AND pickup_lng IS NOT NULL
     AND destination_lat IS NOT NULL AND destination_lng IS NOT NULL
     AND distance_m IS NOT NULL AND fare_vnd IS NOT NULL)
    OR
    (state = 'RELEASED' AND terminal_reason IS NOT NULL AND terminal_reason IN ('CANCELLED','REJECTED','OFFER_EXPIRED')
     AND trip_id IS NULL AND driver_id IS NULL AND attempt IS NULL AND expires_at IS NULL
     AND pickup_lat IS NULL AND pickup_lng IS NULL
     AND destination_lat IS NULL AND destination_lng IS NULL
     AND distance_m IS NULL AND fare_vnd IS NULL)
);
ALTER TABLE reservations ADD CONSTRAINT ck_reservations_command_version CHECK (last_command_version >= 0);
CREATE INDEX idx_reservations_expiry ON reservations (expires_at) WHERE state = 'RESERVED';

-- Existing records were all produced by T10 RESERVE. Wrap their original body hash
-- with operation/resource identity, preserving successful replay after upgrade.
UPDATE processed_commands
SET request_hash = encode(sha256(convert_to(
    'RESERVE|' || (stored_response #>> '{body,reservationId}') || '|' || request_hash,
    'UTF8')), 'hex');
