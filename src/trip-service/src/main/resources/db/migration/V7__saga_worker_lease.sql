-- T21 — Saga recovery worker (state-machines.md §2 "Quy tắc worker"):
-- claim bằng transaction ngắn: CAS version + ghi lease_owner. Worker chết giữa chừng ->
-- next_retry_at (re-arm khi claim) hết hạn -> saga được claim lại, intent replay idempotent.
ALTER TABLE sagas ADD COLUMN IF NOT EXISTS lease_owner TEXT;
