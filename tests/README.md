# tests/ — test cấp project (C02)

Theo đề §10.1, repository phải có thư mục `src/` và `tests/`.

- **Unit/integration test hiện tại nằm trong từng module** (`src/<service>/src/test/java/...`): AuthFlowIntegrationTest (identity), DriverProfileIntegrationTest / ReserveIntegrationTest / ReservationRestartIntegrationTest / SecurityProbeTest (driver), TripIntegrationTest / SecurityProbeTest (trip), SecuritySmokeTest (matching, payment), JsonLogLayoutTest (tất cả 5 service). Chạy bằng `./mvnw -B clean verify` từ root.
- **Frontend test** nằm trong `src/web/` (`npm test`, Vitest).
- **Thư mục này** dành cho test hệ thống cấp project (end-to-end qua Nginx :8080, replay/outbox/load), thêm theo từng task backlog.
- `tests/e2e/ui-flow.cjs` — E2E UI trên trình duyệt thật (headless Chrome) qua Nginx :8080; cách chạy ghi ở đầu file.
- `tests/concurrency/` — raw results concurrency C01/C02 (T30): `c01-results.jsonl`, `c01-snapshots.jsonl`, `c01-summary.txt`, `c02-results.jsonl`, `c02-summary.txt`. Chạy bằng `bash scripts/concurrency-c01.sh` / `bash scripts/concurrency-c02.sh` trên stack Docker Compose (T29) đã seed.
- `tests/recovery/` — raw results failure/recovery R01/R02 (T31): `r01-results.jsonl`, `r01-summary.txt`, `r02-results.jsonl`, `r02-summary.txt`. Chạy bằng `bash scripts/recovery-r01.sh` / `bash scripts/recovery-r02.sh` trên stack Docker Compose (T29); R02 bật failpoint test-only qua compose override tạm.
- `tests/security/` — raw results security/input S01/S02 (T32): `s01-results.jsonl`, `s01-summary.txt`, `s02-results.jsonl`, `s02-summary.txt`. Chạy bằng `bash scripts/security-s01.sh` / `bash scripts/security-s02.sh` trên stack Docker Compose (T29).
