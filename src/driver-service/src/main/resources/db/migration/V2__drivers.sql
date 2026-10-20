-- T09 — bảng driver/vehicle (contract api-contracts.md §3.2; state-machines.md §4).
-- vehicles: plate unique trong driver_db; vehicleType chốt {MOTORBIKE, CAR}.
CREATE TABLE IF NOT EXISTS vehicles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plate_number TEXT NOT NULL UNIQUE,
    vehicle_type TEXT NOT NULL CHECK (vehicle_type IN ('MOTORBIKE', 'CAR')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- drivers: 1 user identity = tối đa 1 driver profile (user_id unique).
-- status theo state machine §4; current_reservation_id/current_trip_id là cột CAS
-- cho invariant 1 (T10/T18+ set, T09 chỉ khai báo + đọc trong STATE_CONFLICT details).
CREATE TABLE IF NOT EXISTS drivers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    vehicle_id UUID NOT NULL REFERENCES vehicles(id),
    status TEXT NOT NULL DEFAULT 'OFFLINE' CHECK (status IN ('OFFLINE', 'AVAILABLE', 'RESERVED', 'BUSY')),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    last_seen_at TIMESTAMPTZ,
    current_reservation_id UUID,
    current_trip_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Candidates (T09): lọc status + heartbeat mới -> index hỗ trợ truy vấn.
CREATE INDEX IF NOT EXISTS idx_drivers_status_last_seen ON drivers (status, last_seen_at);
