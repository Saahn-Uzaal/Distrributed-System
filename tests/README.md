# tests/ — test cấp project (C02)

Theo đề §10.1, repository phải có thư mục `src/` và `tests/`.

- **Unit/integration test hiện tại nằm trong từng module** (`src/<service>/src/test/java/...`): AuthFlowIntegrationTest (identity), DriverProfileIntegrationTest / ReserveIntegrationTest / ReservationRestartIntegrationTest / SecurityProbeTest (driver), TripIntegrationTest / SecurityProbeTest (trip), SecuritySmokeTest (matching, payment), JsonLogLayoutTest (tất cả 5 service). Chạy bằng `./mvnw -B clean verify` từ root.
- **Frontend test** nằm trong `src/web/` (`npm test`, Vitest).
- **Thư mục này** dành cho test hệ thống cấp project (end-to-end qua Nginx :8080, replay/outbox/load) sẽ được thêm ở các task tương ứng trong backlog (T26+); hiện chưa có file test nào.
