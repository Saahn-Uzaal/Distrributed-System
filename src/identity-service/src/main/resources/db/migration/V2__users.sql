-- T06 — identity_db.users (spec DS03 §7; contract api-contracts.md §3.1)
-- UUID PK sinh phía Java; email unique; password_hash = BCrypt (không bao giờ plaintext).
CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('PASSENGER', 'DRIVER')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
