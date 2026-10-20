# Backlog triển khai DS03 — Ride-Hailing Microservices

Nguồn: `DS03_AI_IMPLEMENTATION_SPEC.md` v1.1 (mục 20). Quy tắc bắt buộc: một task mỗi lượt; dependency phải DONE với bằng chứng trước khi làm task kế tiếp; không code sẵn chức năng của task tương lai.

Trạng thái: `TODO` | `IN_PROGRESS` | `BLOCKED` | `DONE`

| ID — Tên task | Owner | Status | Dependencies | Evidence | Blocker |
|---|---|---|---|---|---|
| T01 — Khảo sát và backlog | C | DONE | – | docs/backlog.md, progress.md, handoff.md (lượt 2026-10-17) | – |
| T02 — Backend skeleton | C | DONE | T01 | docs/tasks/T02.md (build pass + 5 service health UP :8081–:8085) | – |
| T03 — PostgreSQL riêng quyền | C | DONE | T02 | docs/tasks/T03.md (compose up + 5 service Flyway baseline + cross-access denied + persistence) | – |
| T04 — RabbitMQ và network | C | DONE | T02 | docs/tasks/T04.md (topology AMQP + publish/consume probe + DNS + persistence qua restart) | – |
| T05 — Contract API và event | C | DONE | T01 | docs/tasks/T05.md + docs/api-contracts.md, events.md, state-machines.md (chốt 2026-10-18) | – |
| T06 — Identity đăng ký/đăng nhập | C | DONE | T03, T05 | docs/tasks/T06.md (6 integration tests pass trên Testcontainers PG + live curl :8081; BCrypt verified trong DB) | – |
| T07 — Security và log tối thiểu | C | DONE | T06 | docs/tasks/T07.md (probe/smoke tests: 41 tests pass, 0 failures; 401/403/internal-credential/correlation theo spec §20.3) | – |
| T08 — Frontend skeleton | C | DONE | T05, T06 | docs/tasks/T08.md (build pass; login thật qua Nginx :8080 201/200/200; route role unit test + Guarded; token không trong console/log) | – |
| T09 — Driver profile và vị trí | B | DONE | T03, T05, T07 | docs/tasks/T09.md (28 tests driver-service pass: 14 integration Testcontainers PG + 11 probe + 3 log; full repo 79/0; live probe :8081/:8082 + nginx :8080) | – |
| T10 — Giữ chỗ nguyên tử (atomic reserve) | B | DONE | T09 | docs/tasks/T10.md (41 tests driver-service pass: 13 reserve integration + 14 profile + 11 probe + 3 log; full repo 86/0; live probe :8081/:8082 + replay/conflict/tombstone) | – |
| T11 — Confirm/release/finish | B | DONE | T10 | docs/tasks/T11.md (17 lifecycle HTTP tests + restart recovery; Driver 67/0, full repo 130/0; tombstone/version/fencing/expiry và nâng cấp giữ replay) | – |
| T12 — Quote và tạo trip | A | DONE | T03, T05, T07 | docs/tasks/T12.md (25 tests trip-service pass: 11 integration Testcontainers PG + 14 probe/log; full repo 97/0; live probe nginx :8080 quote/create/replay/mismatch/list/get + outbox pending verify) | – |
| T13 — Outbox publisher Trip | A | TODO | T04, T12 | – | – |
| T14 — Consumer inbox và retry | B | TODO | T04, T05, T07 | – | – |
| T15 — Matching job thật | B | TODO | T09, T13, T14 | – | – |
| T16 — Trip nhận proposal và reserve | A | TODO | T11, T12, T13, T14, T15 | – | – |
| T17 — Driver đọc offer | B | TODO | T11, T16 | – | – |
| T18 — Accept chuyến | A | TODO | T16, T17 | – | – |
| T19 — Reject và tìm lại | A | TODO | T18 | – | – |
| T20 — Cancel và tranh chấp accept | A | TODO | T18, T19 | – | – |
| T21 — Timeout và restart Saga (có thể chia T21.a/b/c) | A+B | TODO | T20 | – | – |
| T22 — Start/complete và finish driver | A | TODO | T18, T21 | – | – |
| T23 — Payment consumer | A | TODO | T13, T14, T22 | – | – |
| T24 — Trip nhận payment result | A | TODO | T23 | – | – |
| T25 — Notifications và trip event history | A | TODO | T19, T20, T22, T24 | – | – |
| T26 — Seed và smoke script | C | TODO | T06, T09, T22, T24, T25 | – | – |
| T27 — UI passenger | C | TODO | T08, T12, T20, T24, T25 | – | – |
| T28 — UI driver | C | TODO | T08, T09, T17, T18, T19, T22 | – | – |
| T29 — Màn hình demo và triển khai đầy đủ | C | TODO | T26, T27, T28 | – | – |
| T30 — Test concurrency hệ thống (C01/C02) | A+B | TODO | T20, T21, T26 | – | – |
| T31 — Test failure/recovery (R01/R02) | A+B | TODO | T21, T23, T26 | – | – |
| T32 — Security và input tests (S01/S02) | C (+A/B) | TODO | T07, T25, T29 | – | – |
| T33 — Load experiment (k6 P01) | C | TODO | T26, T29, T30 | – | – |
| T34 — CI và clean deploy | C (+A/B) | TODO | T29, T30, T31, T32 | – | – |
| T35 — Report và demo guide | A+B+C | TODO | T30, T31, T32, T33, T34 | – | – |
| T36 — Rà soát cuối và handoff | A+B+C | TODO | T35 | – | – |

## Ghi chú phạm vi từng task (tóm tắt từ spec mục 20)

- **T01**: chỉ tài liệu, chưa scaffold code. ✅ hoàn thành lượt này.
- **T02**: root Maven + wrapper, pin phiên bản, 5 module Spring Boot độc lập với health; chưa nghiệp vụ/DB/JWT/queue. ✅ hoàn thành lượt này (Spring Boot 4.1.1, JDK Temurin 21 tại `~/dev-tools`).
- **T03/T04**: Compose PostgreSQL (5 DB/5 user) và RabbitMQ (topology theo mục 9); chưa consumer nghiệp vụ. ✅ cả hai hoàn thành.
- **T05**: chốt contract API/event/state machine thành tài liệu, chưa code handler. ✅ hoàn thành lượt 2026-10-18 (3 docs + 10 quyết định chốt).
- **T06–T08**: Identity auth, security/log tối thiểu, frontend skeleton (chưa màn hình nghiệp vụ thật).
- **T09–T17**: driver profile/location, atomic reserve, confirm/release/finish, quote/create trip, outbox Trip, inbox+retry Matching, matching job thật, offer.
- **T18–T26**: accept/reject/cancel + tranh chấp, Saga timeout/restart, start/complete, payment, notifications, seed/smoke.
- **T27–T36**: UI passenger/driver/demo, test concurrency/fault/security/load, CI clean deploy, report, handoff cuối.

## Ghi chú đợt sửa nền tảng T01–T06 (2026-10-19)
Đợt review `docs/T01-T06_REVIEW_AND_FIX_BRIEF.md` đã hoàn thành F01–F07 (lỗi nền tảng + mâu thuẫn contract; không triển khai T07/T08 hay business handler). **Trạng thái các task trong bảng trên không đổi** — đây là đợt sửa, không phải task mới. Nhật ký chi tiết: `docs/tasks/fix-2026-10-19.md`; bằng chứng build/test và broker thật ghi trong đó và trong progress.md.

Sau review lần hai, đã sửa bổ sung F01/F04/F07: JWT payload/audience/expiry, fullName sau trim và Java trên PATH. `clean verify` thành công; Identity có **16 tests pass**. Nhật ký mới nhất: `docs/tasks/fix-followup-2026-10-19.md`.

## Ghi chú T07 (2026-10-19)
T07 DONE: security filter + correlation/log tối thiểu ở cả 5 service; probe/smoke tests mới 25 (driver 11, trip 8, matching 3, payment 3) + identity 16 hiện có = **41 tests pass** trong `clean verify`. Chưa code nghiệp vụ (T09+); matching/payment không có API surface theo contract §3/§4 nên chỉ hạ tầng correlation + JSON error. Chi tiết: `docs/tasks/T07.md`.

## Ghi chú T08 (2026-10-19)
T08 DONE: frontend skeleton Vite/React/TS/Ant Design ở `web/` (login/register thật, route theo role, API client, placeholder home); Nginx container trong compose (:8080) serve build + proxy `/api/*` theo contract §3, rate limit login + payload limit theo spec §11. Login thật qua Nginx đã verify bằng curl; token không xuất hiện trong console/log (source + nginx access log). Chi tiết: `docs/tasks/T08.md`. **Gate nền tảng T01–T08 DONE.**

## Ghi chú đợt sửa T07–T08 theo review R01–R07 (2026-10-19)
Review `docs/T07-T08_REVIEW.md` đã hoàn thành đủ 7 phát hiện: R01 nginx proxy URL gốc (hết 301, probe live 401 JSON qua :8080); R02 filter trip bảo vệ chính `/api/notifications` (+3 probe test, trip 14 tests); R03 frontend xóa session khi server trả 401 + không báo thành công từ cache; R04 security tests không còn phụ thuộc PostgreSQL dev (exclude đúng class Boot 4 + `app.flyway.enabled=false`, FlywayConfig conditional — production không đổi); R05 structured JSON logging spec §11 ở cả 5 service (+JsonLogLayoutTest ×5, log live verify); R06 validation frontend khớp contract (password ≤72 byte UTF-8, fullName sau trim; `npm test` 13/13); R07 nginx body limit 64 KiB + JSON error page 413 (probe live 413 application/json). **Trạng thái các task trong bảng trên không đổi** — đây là đợt sửa. Chi tiết + bằng chứng: `docs/T07-T08_REVIEW_FIXES.md`.

## Ghi chú T09 (2026-10-20)
T09 DONE: driver-service có migrations V2 (`vehicles` + `drivers`), profile/availability/location/heartbeat theo contract §3.2, internal candidates API lọc AVAILABLE + heartbeat mới + radius với thứ tự deterministic (§4.2). Chốt thêm vào contract: 409 `PLATE_TAKEN`, PUT availability khi RESERVED/BUSY → 409 mọi status (details state/tripId), limit/radius rules. 14 integration tests mới (Testcontainers PG, port 18099) + sửa SecurityProbeTest sang H2 in-memory (bean T09 cần JdbcTemplate). Full repo `clean verify` **79/0**. Live probe end-to-end :8081/:8082 + nginx :8080. Chi tiết: `docs/tasks/T09.md`.

## Ghi chú T10 (2026-10-20)
T10 DONE: driver-service có migrations V3 (`reservations` + `processed_commands`; thêm cột terminal_reason, pickup/destination/distance_m/fare_vnd; unique `(trip_id, attempt)`), PUT/GET `/internal/reservations/{id}` với reserve **nguyên tử** trong 1 transaction (dedup commandId → tombstone → STALE_VERSION → CAS driver AVAILABLE + heartbeat cutoff). Chốt thêm vào contract §4.1: `request_hash` = SHA-256 payload canonical trừ `commandId`/`commandVersion`; replay trả status/body gốc; thứ tự kiểm tra reserve. 13 integration tests mới (Testcontainers PG, port 18100) gồm concurrency barrier + tombstone simulation; sửa SecurityProbeTest probe path. Full repo `clean verify` **86/0**. Live probe end-to-end :8081/:8082: reserve 201, replay 201, COMMAND_CONFLICT 409, DRIVER_NOT_AVAILABLE 409, GET 200. Chi tiết: `docs/tasks/T10.md`.

## Ghi chú T12 (2026-10-20)
T12 DONE: trip-service có migrations V2 (`trips` + `outbox` + `trip_events` + `idempotency_keys`), POST `/api/trips/quote` (fare server-side: haversine + `FARE_BASE_VND + ceil(distanceM/1000)*FARE_PER_KM_VND`, env mới), POST `/api/trips` với Idempotency-Key UUID bắt buộc — 202 + Location, replay response gốc cùng key/payload, 409 `IDEMPOTENCY_MISMATCH` khác payload, 400 thiếu/sai key; GET list (phân trang §1.3, clamp size 100) + GET detail (owner hoặc assigned driver, không thì 404). Side effects tạo trip cùng transaction: outbox PENDING `TripRequested` attempt 1 (publisher = T13), trip_event `TRIP_CREATED` v1; saga WAITING_MATCH = T16, notifications = T25 — chưa ghi. Chốt T12: outbox schema ở T12, claim idempotency bằng ON CONFLICT DO NOTHING, request_hash chỉ tọa độ. 11 integration tests mới (Testcontainers PG, port **18102**) + sửa SecurityProbeTest (H2 in-memory + probe path sang /events). Full repo `clean verify` **97/0**. Live probe nginx :8080: quote/create/replay/mismatch/list/get + psql verify outbox pending. Chi tiết: `docs/tasks/T12.md`.

## Sửa review T09/T10/T12 và hoàn tất T11 (2026-10-20)

Đã sửa R01–R07: Trip idempotency theo principal/operation/key, driver profile/identity tách biệt, phân trang long; Driver claim command nguyên tử, hash bind operation/resource, profile retry đồng thời, version bounds. T11 DONE: confirm/release/finish, tombstone thật, fencing hai pointer, expiry RESERVED có worker bền vững và kiểm tra deadline sau khóa driver. Migrations mới Driver V4/Trip V3 có test nâng cấp giữ replay cũ. Full repo clean verify **130 tests/0 failures/errors/skipped**; web 13/13 + build pass. Chi tiết mới nhất: `docs/T09-T12_REVIEW_FIXES.md`, `docs/tasks/T11.md`. Các ghi chú T09/T10/T12 phía trên là lịch sử, áp dụng các chốt sửa mới trong contract. T13/T14/T15 vẫn TODO; không tự triển khai task kế tiếp.
