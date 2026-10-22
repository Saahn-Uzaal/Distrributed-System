-- L06 — outbox khớp envelope events.md §2.
--   * schema_version là Integer (consumer chỉ chấp nhận 1), không phải chuỗi 'v1'.
--   * Backfill các row tạo trước V4 (DB dev có row PENDING từ các lần probe T12):
--     V4 gán mặc định aggregate_version = 0 và routing_key = '' — T13 publish với routing key rỗng
--     sẽ không route được. TripRequested do T12 tạo luôn ở trips.version = 1.
ALTER TABLE outbox ALTER COLUMN schema_version DROP DEFAULT;
ALTER TABLE outbox ALTER COLUMN schema_version TYPE INT USING 1;
ALTER TABLE outbox ALTER COLUMN schema_version SET DEFAULT 1;
ALTER TABLE outbox ADD CONSTRAINT ck_outbox_schema_version CHECK (schema_version >= 1);

UPDATE outbox
SET aggregate_version = 1,
    routing_key = 'trip.requested'
WHERE event_type = 'TripRequested'
  AND (routing_key = '' OR aggregate_version = 0);

-- Từ nay producer phải ghi routing key và aggregate version thật.
ALTER TABLE outbox ALTER COLUMN routing_key DROP DEFAULT;
ALTER TABLE outbox ALTER COLUMN aggregate_version DROP DEFAULT;
ALTER TABLE outbox ADD CONSTRAINT ck_outbox_routing_key CHECK (routing_key <> '');
ALTER TABLE outbox ADD CONSTRAINT ck_outbox_aggregate_version CHECK (aggregate_version >= 1);
