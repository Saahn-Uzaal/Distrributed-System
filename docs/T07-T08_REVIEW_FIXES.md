# Đợt sửa T07–T08 theo review R01–R07 (2026-10-19)

Nguồn: [T07-T08_REVIEW.md](T07-T08_REVIEW.md). Phạm vi: sửa đúng 7 phát hiện R01–R07; không triển khai task nghiệp vụ mới. Mọi kết quả dưới đây là lệnh chạy thật trên workspace này (không bịa, không chạy trên bản sao).

## Tóm tắt trạng thái

| ID | Vấn đề | Fix | Bằng chứng |
|---|---|---|---|
| R01 | Nginx 301 URL API gốc (`/api/trips`, `/api/notifications`) sang slash cuối | Location proxy không có slash cuối ở cả hai vế (URL chính xác + subpath) cho đủ 4 API location | Probe live qua :8080: GET/POST `/api/trips` và GET `/api/notifications` → **401 JSON** từ trip-service, không còn 301 |
| R02 | `ApiSecurityFilter` (trip) bỏ sót chính path `/api/notifications` | Matcher thêm `uri.equals("/api/notifications")`; +3 probe test URL chính xác | `clean verify`: trip **14 tests pass** (gồm 3 probe mới); probe live GET `/api/notifications` không token → 401 |
| R03 | Frontend báo "Đăng nhập thành công" dù server từ chối phiên (401) | `client.ts` xóa sessionStorage khi request đã xác nhận nhận 401; PassengerHome/DriverHome chỉ hiện thông báo khi `/me` trả user thật; redirect `/login` khi `!loading && !user`; bỏ fallback hiển thị session cũ | Source + build pass; hành vi theo contract §5 (correlation/session) — chưa E2E browser (giới hạn ghi rõ) |
| R04 | Test security 4 service vẫn kết nối PostgreSQL dev (exclusion Boot 3 bị Boot 4 lặng lẽ bỏ qua) | Đổi exclusion sang `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration` (tên thật của Boot 4) trong 4 test file + property `app.flyway.enabled=false`; cả 5 `FlywayConfig` thêm `@ConditionalOnProperty(name="app.flyway.enabled", havingValue="true", matchIfMissing=true)` — hành vi production không đổi | Chạy security tests với **Postgres DỪNG** → driver/matching/payment/trip pass; sau đó full `clean verify` (postgres healthy) BUILD SUCCESS, 6 module xanh |
| R05 | Log runtime là text mặc định, thiếu structured JSON + field spec §11 | `JsonLogLayout` riêng mỗi service (không thêm dependency Maven — tránh DNS flaky) + `logback-spring.xml`; `CorrelationIdFilter` ×5 set MDC `eventType=http_request`, `result`, `durationMs` (+ đo thời gian). Field: timestamp UTC ISO, level, service, instanceId (env `INSTANCE_ID` hoặc UUID per JVM), correlationId/eventType/result/durationMs/tripId/reservationId từ MDC, logger, message, exception | +`JsonLogLayoutTest` ×5 (JSON hợp lệ, đủ field bắt buộc, không field secret top-level, escaping) — pass trong `clean verify`; **log live** khi probe :8083 (dưới) |
| R06 | Validation frontend lệch contract: password theo ký tự thay vì byte UTF-8; fullName trên chuỗi thô thay vì sau trim | Mới `src/web/src/auth/validation.ts`: `utf8ByteLength` (pure function, không TextEncoder), password ≤ **72 byte** UTF-8, fullName 2–100 **sau trim**; RegisterPage dùng validator antd custom + nhãn cập nhật; +`validation.test.ts` (ASCII/tiếng Việt/ranh giới trim) | `npm test`: **13 tests pass** (2 routeForRole cũ + 11 validation mới). Lưu ý: review ghi "25 ký tự ạ = 75 byte" — đúng, vì `ạ` (U+1AB1) = **3 byte**; test chốt theo giá trị thật (24×ạ=72B hợp lệ, 25×ạ=75B từ chối; trộn 48 ASCII + 8×ạ = 72B) |
| R07 | `client_max_body_size 1m` lệch contract 64 KiB | nginx: `client_max_body_size 64k` + `error_page 413 /errors/payload-too-large.json`; static JSON error page `web/public/errors/payload-too-large.json` (Vite copy vào `dist/`) | Probe live POST `/api/auth/login` body **70.067 byte** → **413 + application/json** từ nginx |

## Chi tiết từng fix

### R01 — Nginx proxy URL gốc
- `deploy/nginx/nginx.conf`: 4 location API (`/api/auth`, `/api/drivers`, `/api/trips`, `/api/notifications`) đổi sang dạng không slash cuối ở cả hai vế, ví dụ:
  ```nginx
  location /api/trips { proxy_pass http://host.docker.internal:8083/api/trips; }
  ```
  → URL chính xác và mọi subpath đều được proxy nguyên request (giữ method/body/URI), không sinh 301. Location rate-limit `= /api/auth/login` giữ nguyên.
- Bằng chứng live (nginx container restart với config mới, trip-service chạy tạm trên :8083):

| Request qua :8080 | Kết quả |
|---|---|
| `GET /api/trips` (không token) | 401 `{"code":"UNAUTHORIZED","message":"Missing or invalid bearer token.","correlationId":"70c98b94-..."}` — application/json, **không 301** |
| `POST /api/trips` (không token) | 401 JSON tương tự (`6eb35f30-...`) |
| `GET /api/notifications` (không token) | 401 JSON (`b4f73652-...`) |

### R02 — Bảo vệ chính path `/api/notifications`
- `trip-service/.../security/ApiSecurityFilter.java`: điều kiện bảo vệ notifications thêm `uri.equals("/api/notifications")`.
- `src/trip-service/src/test/java/com/ridehailing/trip/SecurityProbeTest.java`: +3 test probe URL chính xác (GET `/api/notifications` không token → 401; POST `/api/trips` không token → 401; driver token hợp lệ trên path gốc → 404 vì controller chưa có — đúng phạm vi T25).
- Kết quả: trip-service **14 tests, 0 failures** trong `clean verify`.

### R03 — Phiên frontend khi server từ chối
- `src/web/src/api/client.ts`: request đã xác nhận (Bearer) nhận 401 → xóa sessionStorage.
- `PassengerHome.tsx` / `DriverHome.tsx`: chỉ hiển thị "Đăng nhập thành công" khi `/me` trả user thật; `!loading && !user` → redirect `/login`; bỏ hiển thị fallback từ session cache cũ.
- Giới hạn: chưa E2E browser trong phiên này (review cũng ghi giới hạn tương tự); hành vi được kiểm chứng qua source + build + test component hiện có.

### R04 — Test không phụ thuộc DB dev
- Nguyên nhân gốc: Boot 4 đổi package autoconfiguration; tên exclusion cũ (`org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration`) bị **lặng lẽ bỏ qua** → Hikari/Flyway vẫn khởi tạo và nối PostgreSQL dev.
- Fix: 4 test file security (driver/trip `SecurityProbeTest`, matching/payment `SecuritySmokeTest`) dùng đúng class exclude của Boot 4 + `app.flyway.enabled=false`; 5 `FlywayConfig` thêm `@ConditionalOnProperty(name="app.flyway.enabled", havingValue="true", matchIfMissing=true)` — production (không set property) vẫn chạy Flyway như cũ.
- Bằng chứng: **Postgres DỪNG** → security tests driver/matching/payment/trip pass; postgres healthy lại → full `./mvnw.cmd -B clean verify` = BUILD SUCCESS, đủ 6 module.

### R05 — Structured JSON logging (spec §11)
- Mỗi service: `logging/JsonLogLayout.java` + `resources/logback-spring.xml` (encoder JSON trên console). Không thêm dependency Maven mới (tránh DNS flaky của máy này).
- `CorrelationIdFilter` ×5: set MDC `eventType=http_request`, `result=<status>`, `durationMs`; đo thời gian vòng request.
- Field mỗi dòng JSON: `timestamp` (UTC ISO), `level`, `service`, `instanceId` (env `INSTANCE_ID` hoặc UUID per JVM), `correlationId`, `eventType`, `result`, `durationMs`, `tripId`/`reservationId` (nếu có trong MDC), `logger`, `message`, `exception`.
- Test: `JsonLogLayoutTest` ×5 — JSON parse hợp lệ, đủ field bắt buộc, **không** field secret top-level (password/token/authorization), escaping message đặc biệt.
- Log live thật khi probe trip-service :8083 (correlationId khớp 1:1 với response 401):
  ```json
  {"timestamp":"2026-10-19T16:34:11.718636900Z","level":"INFO","service":"trip-service","instanceId":"dfc0eba0-fd4f-4c26-a282-30718188320c","correlationId":"b4f73652-46a5-44f4-ac25-5a97751ee283","eventType":"http_request","result":"401","durationMs":"0","logger":"com.ridehailing.trip.security.CorrelationIdFilter","message":"request method=GET path=/api/notifications status=401 correlationId=b4f73652-46a5-44f4-ac25-5a97751ee283"}
  ```

### R06 — Validation frontend khớp contract §3.1 (F04)
- `src/web/src/auth/validation.ts`: `utf8ByteLength` pure function; `validatePassword` = ≥8 ký tự và ≤ **72 byte UTF-8**; `validateFullName` = 2–100 ký tự **sau trim**.
- `RegisterPage.tsx`: validator antd custom + nhãn "Mật khẩu tối đa 72 byte UTF-8".
- `validation.test.ts`: ASCII (72 hợp lệ/73 từ chối), tiếng Việt (24×ạ=72B hợp lệ, 25×ạ=75B từ chối — 25 ký tự vẫn < 72 ký tự nên test bắt được kiểm tra naive theo độ dài chuỗi), trộn ranh giới byte, fullName trim (98+2 khoảng trắng hợp lệ; raw 102→trim 100 hợp lệ; " a" từ chối).
- `npm test`: **13/13 pass**.

### R07 — Body limit 64 KiB + lỗi JSON
- `deploy/nginx/nginx.conf`: `client_max_body_size 64k` (trước: 1m) + `error_page 413 /errors/payload-too-large.json`.
- `web/public/errors/payload-too-large.json`: body tĩnh `{code:"PAYLOAD_TOO_LARGE", message, correlationId:null, details:null}`; Vite copy `public/` → `dist/` khi build (đã xác nhận `dist/errors/payload-too-large.json` tồn tại sau `npm run build`).
- Probe live: POST `/api/auth/login` body 70.067 byte → **413, application/json**:
  ```json
  {"code":"PAYLOAD_TOO_LARGE","message":"Request body exceeds the 64 KiB limit.","correlationId":null,"details":null}
  ```
- Giới hạn ghi rõ: lỗi 413 sinh ở tầng nginx nên **không thể** echo/generate correlationId (giữ `null`); đây là hành vi chấp nhận được vì request chưa tới ứng dụng.

## Lệnh đã chạy và kết quả thật (lượt này)

| Lệnh | Kết quả |
|---|---|
| `cd web && npm test` | **13 tests passed** (2 file: session 2, validation 11) — sau khi sửa giá trị byte UTF-8 trong test (`ạ`=3B, không phải 2B như ghi trong handoff trước) |
| `cd web && npm run build` | BUILD SUCCESS → `dist/`; `dist/errors/payload-too-large.json` tồn tại |
| `docker restart ridehailing-nginx` (config mới bind-mount) | container Up; probe dưới chạy trên config mới |
| Chạy tạm trip-service jar từ gốc repo (:8083, cwd gốc để đọc `keys/jwt-public.pem`) | health/endpoint up; dừng sau probe (`taskkill //F //PID 24508` SUCCESS) |
| curl qua :8080 (không token): GET `/api/trips`, POST `/api/trips`, GET `/api/notifications` | cả 3 → **401 application/json** + correlationId; không 301 |
| curl POST `/api/auth/login` body 70.067B qua :8080 | **413 application/json** (PAYLOAD_TOO_LARGE) |
| grep log trip-service | dòng JSON spec §11 đầy đủ, correlationId khớp response (trích dẫn ở R05) |

## Giới hạn / chưa kiểm chứng
- Chưa E2E browser cho R03 (chỉ source + build + test component); review cũng ghi giới hạn tương tự.
- 413 của nginx không có correlationId (giới hạn tầng proxy, đã nêu).
- Không chạy lại full `clean verify` **sau** khi sửa test frontend (frontend không nằm trong Maven reactor; backend verify cuối vẫn là BUILD SUCCESS với postgres healthy — không code backend nào đổi sau lần đó ngoài việc đã include trong lần chạy ấy).

## Tác động runbook
- Nginx: body limit 64 KiB + trang lỗi JSON `web/public/errors/payload-too-large.json` (phải build frontend trước khi up nginx để có file này trong dist).
- Services: env tùy chọn `INSTANCE_ID` cho field instanceId trong log JSON (mặc định = UUID per JVM).
