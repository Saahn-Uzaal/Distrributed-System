-- T09-6 (N32 driver): cột theo spec §7.
--   * drivers.version — version row driver; fencing thực vẫn qua current_reservation_id/
--     current_trip_id + advisory lock, version tăng mỗi lần đổi state (CAS availability/reserve/confirm/release).
--   * vehicles.updated_at — thời điểm cập nhật cuối của vehicle.
-- T09-4 (N31): chuẩn hóa biển số dữ liệu cũ và unique không phân biệt hoa/thường.

ALTER TABLE drivers ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE vehicles ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE vehicles SET plate_number = upper(trim(both ' ' from regexp_replace(plate_number, '\s+', ' ', 'g')));
ALTER TABLE vehicles DROP CONSTRAINT vehicles_plate_number_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_vehicles_plate_upper ON vehicles (upper(plate_number));
