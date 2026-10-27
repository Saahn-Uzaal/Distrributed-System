-- T25 — notifications (spec §Trip database; api-contracts.md §3.4): recipient = user_id.
-- Chốt T25: thêm cột version + unique (user_id, trip_id, version, type) để dedup theo transition —
-- redelivery/retry của cùng một transition không tạo thông báo trùng (INSERT ... ON CONFLICT DO NOTHING).
-- Một transition có thể notify nhiều user (vd TRIP_ACCEPTED → passenger + driver) nên key gồm user_id.

CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    trip_id UUID NOT NULL REFERENCES trips(id),
    type TEXT NOT NULL CHECK (type IN ('TRIP_OFFERED','OFFER_RECEIVED','TRIP_ACCEPTED','TRIP_CANCELLED',
                                       'NO_DRIVER','TRIP_COMPLETED','PAYMENT_SUCCEEDED','PAYMENT_FAILED')),
    message TEXT NOT NULL,
    version INT NOT NULL CHECK (version >= 1),
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_user_trip_version_type ON notifications (user_id, trip_id, version, type);
CREATE INDEX IF NOT EXISTS idx_notifications_user_created ON notifications (user_id, created_at DESC);
