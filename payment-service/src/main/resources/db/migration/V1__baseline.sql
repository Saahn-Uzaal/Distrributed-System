-- T03 baseline cho payment_db — chưa có bảng nghiệp vụ.
CREATE TABLE IF NOT EXISTS schema_meta (
    id BIGSERIAL PRIMARY KEY,
    note TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO schema_meta (note) VALUES ('T03 baseline');
