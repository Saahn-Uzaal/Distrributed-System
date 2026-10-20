# Rà soát toàn bộ dự án DS03 — 21/10/2026 (lần 2)

## Kết luận

**Kể từ bản rà soát 20/10 chưa có thay đổi nào trong code, cấu hình hay script.** File mới nhất trong repo là `docs/FULL_PROJECT_REVIEW_2026-10-20.md` (23:53 ngày 20/10). Vì vậy **cả 32 phát hiện F01–F32, 5 rủi ro R01–R05 và 6 khoảng trống kiểm thử Q01–Q06 đều vẫn còn tồn đọng**. Lần này còn tìm thêm **40 vấn đề mới N01–N40** mà bản 20/10 chưa ghi nhận.

Tổng số mục còn tồn đọng:

| Nhóm | Số lượng | Mục |
|---|---:|---|
| Phát hiện cũ chưa sửa | 32 | §1 (F01–F32) |
| Rủi ro cũ chưa chốt | 5 | §1 (R01–R05) |
| Khoảng trống kiểm thử cũ | 6 | §1 (Q01–Q06) |
| **Phát hiện mới** | **40** | §2 (N01–N40) |
| Task chưa làm | 24 | §3 (T13–T36) |
| File/thư mục nộp bài còn thiếu | 20 | §3 |

Mức ưu tiên: **P1** phải xử lý trước khi viết thêm code nghiệp vụ hoặc trước khi cho truy cập backend; **P2** ảnh hưởng tính đúng, an toàn, khả năng phục hồi hoặc điều kiện đạt môn; **P3** là trường hợp biên, chẩn đoán, độ nhất quán hoặc tài liệu.
Bằng chứng: **D** = đã tái hiện trong lượt này (HTTP, JDK, build log, công cụ); **S** = xác nhận qua code/config/contract, chưa tái hiện toàn luồng.

Chỉ rà soát: không sửa code, cấu hình hay tài liệu nào khác ngoài việc tạo file này. Không dùng DB dev. Các probe HTTP chỉ chạm Nginx đang chạy khi backend tắt (mọi request đều trả 502/503), nên không tạo dữ liệu nào.

---

## 1. Tồn đọng từ bản 20/10 — vẫn còn nguyên

Không có file nguồn nào thay đổi sau 23:20 ngày 20/10, nên mọi mục dưới đây vẫn đúng như mô tả trong [bản 20/10](FULL_PROJECT_REVIEW_2026-10-20.md). Cột "Kiểm lại 21/10" ghi những mục đã tái hiện lại trong lượt này.

| ID | Ưu tiên | Tóm tắt | Kiểm lại 21/10 |
|---|---|---|---|
| F01 | **P1** | Filter so URI thô, MVC lại bỏ matrix/decode → `/internal;x/...` reserve 201 không cần token | Code không đổi; N01, N02 làm tác động rộng hơn |
| F02 | P2 | JWT `longValue()` cắt phần lẻ NumericDate; `nbf:null` được bỏ qua | Code không đổi |
| F03 | P2 | Jackson tự ép kiểu: role số, `commandVersion` 1.9→1, tọa độ dạng chuỗi | Code không đổi |
| F04 | P2 | Lỗi client (UUID/query sai, 405/415, path không tồn tại ở Identity) bị gom thành 500 | Code không đổi |
| F05 | P2 | U+0000 qua validation → PostgreSQL lỗi → 500 | Code không đổi |
| F06 | P2 | Reserve lấy heartbeat cutoff trước khi chờ khóa driver | Code không đổi |
| F07 | P2 | `lastSeenAt` lấy trước UPDATE có thể bị chặn → heartbeat trả về đã cũ | Code không đổi |
| F08 | P2 | Expiry worker chặn đầu hàng đợi, khóa blocking, không cô lập lỗi từng item | Code không đổi |
| F09 | P2 | Confirm với version INT_MAX làm driver BUSY không còn release/finish được | Code không đổi |
| F10 | P2 | Reserve nhận `expiresAt` đã qua | Code không đổi; xem thêm N29 (không có giới hạn trên) |
| F11 | P2 | Haversine không clamp → điểm đối cực ra 0 m (cả Driver và Trip) | Code không đổi |
| F12 | P3 | `expiresAt` ngoài miền PG bị báo nhầm `DRIVER_NOT_AVAILABLE` | Code không đổi |
| F13 | P3 | Chi tiết lỗi availability dùng snapshot trước CAS; `tripId` null khi RESERVED | Code không đổi |
| F14 | P2 | `createdAt` trong response create/replay khác với GET (đồng hồ JVM và DB) | Code không đổi |
| F15 | P2 | 401 muộn của token A xóa phiên B | Code không đổi |
| F16 | P2 | Login/register cũ hoàn tất muộn ghi đè phiên mới | Code không đổi |
| F17 | P3 | Validation frontend và backend lệch nhau (trim Unicode, mật khẩu toàn dấu cách, email >254) | Code không đổi |
| F18 | P3 | Register 201 nhưng auto-login lỗi bị hiển thị như đăng ký thất bại | Code không đổi |
| F19 | P3 | `/me` lỗi mạng/5xx bị coi như chưa đăng nhập | Code không đổi |
| F20 | P2 | Layout log không có newline → nhiều event dính `}{` | **D lại**: build log 21/10 có 19 chỗ `}{"timestamp"` |
| F21 | P2 | Log exception mất stack trace và cause | Code không đổi |
| F22 | P2 | Lỗi 502/504/rate-limit của Nginx trả HTML, không phải ApiError | **D lại**: `502 text/html`, `Content-Length: 157` |
| F23 | P3 | Lỗi Nginx không có correlationId | Code không đổi; xem N12 |
| F24 | P3 | `/api/does-not-exist` rơi vào SPA fallback, trả 200 HTML | Code không đổi |
| F25 | P2 | `generate-dev-keys.sh` dùng CRLF → Linux Bash dừng ngay | **D lại**: `cat -A` thấy CRLF ở mọi dòng |
| F26 | P2 | Thiếu public key nhưng còn private → script tạo private mới, mất key cũ | Code không đổi |
| F27 | P2 | Mật khẩu DB nối thẳng vào SQL literal trong init script | Code không đổi |
| F28 | P2 | Credential RabbitMQ không percent-encode trong AMQP URI | Code không đổi |
| F29 | P2 | Script topology `source .env` kiểu Bash ≠ dotenv của Compose | Code không đổi |
| F30 | P2 | `npm audit`: 8 package entries (2 critical, 1 high, 5 moderate) | **D lại**: kết quả giống hệt 20/10 |
| F31 | P2 | Contract cancel yêu cầu "publish event hủy matching" nhưng catalog chỉ có 6 event | Docs không đổi |
| F32 | P3 | Docker tag trôi (`rabbitmq:3-management-alpine`, `postgres:17-alpine`, `nginx:1.27-alpine`) | Không đổi; Nginx thực tế đang là 1.27.5 |

Rủi ro chưa chốt (giữ nguyên): **R01** signed zero (0.0 và -0.0) cho ra hash idempotency khác nhau · **R02** count và content của list Trip không cùng snapshot · **R03** login bỏ qua BCrypt khi email không tồn tại (lộ qua thời gian phản hồi) · **R04** trộn đồng hồ JVM và DB `now()` · **R05** candidates tải toàn bộ driver AVAILABLE vào Java.

Khoảng trống kiểm thử (giữ nguyên): **Q01** test "không có secret" chỉ kiểm top-level field · **Q02** chưa test framing log nhiều event và stack trace · **Q03** chưa test URL biến thể, NumericDate phần lẻ, JSON sai kiểu · **Q04** frontend chỉ có test helper, chưa có test component/race/E2E · **Q05** chưa có concurrency/recovery cấp hệ thống · **Q06** chưa có load test, CI, SCA backend/container.

---

## 2. Phát hiện mới (chưa có trong bản 20/10)

### 2.1. Bảo mật và gateway

| ID | Ưu tiên / BC | Phát hiện và ảnh hưởng | Vị trí |
|---|---|---|---|
| N01 | **P2 / D** | **Bỏ qua rate limit đăng nhập.** Giới hạn chỉ gắn với `location = /api/auth/login` (khớp chính xác). `/api/auth/login;x` rơi vào `location /api/auth`, nơi không có `limit_req`, rồi vẫn được proxy. Spring MVC bỏ matrix parameter khi định tuyến (đã chứng minh ở F01), nên request vẫn tới handler login → brute-force mật khẩu không bị giới hạn. | `deploy/nginx/nginx.conf` (2 location `/api/auth`) |
| N02 | P2 / S | **Backend lắng nghe trên mọi network interface.** Không có `server.address`, nên 5 tiến trình Java chạy trên host mở cổng 8081–8085 ra LAN, gồm cả `/internal/**` và `/actuator/**`. Contract §2 ghi "internal port không publish ra host". Gọi thẳng vào backend còn bỏ qua được giới hạn 64 KiB và rate limit của Nginx; kết hợp F01 thì reserve được từ LAN. | `*/application.yml`; contract `api-contracts.md` §2 |
| N03 | P3 / S | Chỉ Nginx giới hạn body 64 KiB; ứng dụng không giới hạn kích thước JSON. Gọi trực tiếp backend (N02) gửi được body rất lớn, Jackson đọc toàn bộ vào bộ nhớ. | `*/application.yml`; spec §8 |
| N04 | P3 / D | Location prefix không có ranh giới: `/api/authx`, `/api/tripsfoo`, `/api/driversX` đều bị proxy sang backend. Đã thấy `/api/authx` → 502 từ upstream. Nên dùng `/api/auth/` kèm location khớp chính xác. | `deploy/nginx/nginx.conf` |
| N05 | P3 / D | `server_tokens` đang bật: header `Server: nginx/1.27.5` lộ phiên bản. | `deploy/nginx/nginx.conf` |
| N06 | P3 / D | Rate limit trả **503** (mặc định của `limit_req`), không phải 429. Bảng mã lỗi contract §1.2 không có mã nào cho rate limit. F22 mới nói về HTML, chưa nói về sai status. | `nginx.conf`; contract §1.2 |
| N07 | P3 / S | Một `INTERNAL_API_TOKEN` dùng chung cho mọi bên gọi, nên không phân quyền được theo caller. Matching chỉ cần candidates nhưng cầm cùng token thì cũng reserve/confirm/release được, trái với spec §9 ("Trip là orchestrator duy nhất") và spec §11 ("credential riêng"). | contract §2 quyết định #8; `ApiSecurityFilter` Driver |
| N08 | P3 / S | Driver khởi động bình thường khi `INTERNAL_API_TOKEN` rỗng (mặc định `${INTERNAL_API_TOKEN:}`), sau đó **mọi** internal call đều bị 401 mà không có cảnh báo lúc khởi động. File `.env` local hiện thiếu `INTERNAL_API_TOKEN`, `FARE_*`, `RESERVATION_EXPIRY_*` so với `.env.example` (đã so tên key, không đọc giá trị). | `driver-service/.../application.yml`; `.env` |
| N09 | P3 / S | Mật khẩu DB được hard-code làm giá trị mặc định trong file tracked (`${IDENTITY_DB_PASSWORD:identity_dev_2026}` …). Thiếu env thì service âm thầm dùng mật khẩu mặc định thay vì dừng lại. Trái spec §11 ("không hard-code secret") và §12 ("không dùng secret mặc định"). | 5 × `application.yml` |
| N10 | P3 / S | Không dùng Spring Security filter chain như spec §4 yêu cầu; bảo vệ bằng filter tự viết so khớp tiền tố chuỗi. Đây là nguyên nhân gốc của F01, N01 và các bypass `/api/trips;x`. Khi sửa F01 nên chuyển sang `SecurityFilterChain` với request matcher có cùng cách hiểu path như MVC. | `*/security/ApiSecurityFilter.java`, `JwtAuthFilter.java` |

**Tái hiện N01** (backend tắt; 503 là dấu hiệu rate limit đã chặn):

```text
GET /api/auth/login    ×16 → 502×10, 503×6   (có giới hạn)
GET /api/auth/login;x  ×16 → 502×16          (không bao giờ bị giới hạn)
GET /api/authx              → 502 text/html  (bị proxy sang upstream, N04)
```

### 2.2. Logging và correlation

| ID | Ưu tiên / BC | Phát hiện và ảnh hưởng | Vị trí |
|---|---|---|---|
| N11 | **P2 / S** | **Không có log nghiệp vụ hay audit nào.** Toàn bộ code chỉ có 3 loại log: một dòng truy cập ở `CorrelationIdFilter` và 2 log lỗi ở `GlobalExceptionHandler`. Không log đăng ký/đăng nhập (kể cả thất bại), reserve/confirm/release/finish, expiry worker giải phóng driver, hay tạo trip. MDC `tripId`/`reservationId` khai báo trong layout nhưng không bao giờ được set; `eventType` luôn là `http_request`. Trái đề §4.1 (log phải có event type và kết quả xử lý) và §4.5 (ghi log hoạt động quan trọng), spec §11; demo bước 8 "tìm log theo correlationId" sẽ không có gì để xem. | Mọi service; `JsonLogLayout.STRUCTURED_MDC_KEYS` |
| N12 | P3 / S | Nginx không tạo hoặc truyền `X-Correlation-ID` như contract §5.2 yêu cầu ("Nginx entrypoint tạo nếu client không gửi"); access log không có correlation. Service không trả `X-Correlation-ID` trong response thành công, dù comment ở `web/src/api/client.ts:29` ghi "server echo lại". | `nginx.conf`; `CorrelationIdFilter` ×5 |
| N13 | P3 / D | `X-Correlation-ID: 1-2-3-4-5` được chấp nhận: JDK 21 parse thành `00000001-0002-0003-0004-000000000005`, nhưng filter vẫn ghi chuỗi gốc `1-2-3-4-5` vào log và error body, còn Trip outbox lưu dạng chuẩn. Tìm log theo correlationId sẽ lệch giữa HTTP và event. Nên chuẩn hóa bằng `UUID.toString()` hoặc yêu cầu đúng 36 ký tự. | `CorrelationIdFilter.resolve` ×5; `TripController.create` |

### 2.3. Triển khai và hạ tầng

| ID | Ưu tiên / BC | Phát hiện và ảnh hưởng | Vị trí |
|---|---|---|---|
| N14 | **P2 / S** | Nginx proxy tới `host.docker.internal` nhưng Compose không khai báo `extra_hosts: ["host.docker.internal:host-gateway"]`. Trên Docker Engine Linux (VM theo spec §12, runner CI) tên này không tồn tại; Nginx resolve upstream lúc load config nên **container dừng với lỗi "host not found in upstream"**. Docker Desktop trên Windows che mất lỗi này. | `deploy/compose.yaml` service nginx |
| N15 | **P2 / S** | Topology RabbitMQ chỉ được khai báo bằng script chạy tay; service không tự declare. Contract chỉ yêu cầu publisher confirms, không yêu cầu `mandatory` + returns callback. Nếu exchange tồn tại nhưng thiếu queue/binding (quên chạy script sau khi reset volume, hoặc thêm routing key mới như event hủy của F31 mà quên bind), broker vẫn **ack confirm và bỏ message** → outbox đánh dấu SENT → mất event âm thầm. Phải chốt trước khi làm T13/T14. | `infra/rabbitmq/*`; `docs/events.md` §1 và §4.6 |
| N16 | **P2 / S** | Frontend gọi `crypto.randomUUID()` cho mọi request. API này chỉ có trong **secure context** (HTTPS hoặc localhost). Mở qua `http://<IP-LAN-hoặc-VM>:8080` (spec §12, demo nhiều máy) thì mọi lời gọi API ném TypeError → không đăng nhập được, UI chỉ báo "Lỗi không xác định". | `web/src/api/client.ts:30` |
| N17 | P3 / S | Không có retry khi khởi động: Flyway migrate chạy trong lúc khởi tạo bean, DB chưa sẵn sàng là service dừng. Liveness/readiness probe của Actuator chưa bật (`management.endpoint.health.probes.enabled`), timeout kiểm tra dependency để mặc định (Hikari 30 s). Trái spec §5 và §12 ("dependency unavailable không bắt buộc kill tiến trình", "startup retry thật"). | `*/config/FlywayConfig.java`; `*/application.yml` |
| N18 | P3 / S | Healthcheck PostgreSQL dùng `pg_isready` qua socket, có thể báo healthy khi server tạm của docker-entrypoint đang chạy init script. Nếu T29 dùng `depends_on: service_healthy`, service có thể kết nối trước khi DB/user được tạo xong. | `deploy/compose.yaml` |
| N19 | P3 / S | Không có `.gitattributes`; line ending lẫn lộn (`pom.xml`, `nginx.conf`, `generate-dev-keys.sh` dùng CRLF; init script và `.env.example` dùng LF). Đây là nguyên nhân gốc của F25. Node chưa được pin (không có `engines` hay `.nvmrc`) dù spec §4 yêu cầu; image Testcontainers `postgres:17-alpine` cũng là tag trôi (mở rộng của F32). | root; `web/package.json`; test classes |
| N20 | P3 / S | Integration test dùng cổng cố định và trùng nhau giữa các module: 18099 (Driver và Trip), 18100 (Driver và Matching), 18101 (Driver và Payment). Build tuần tự thì pass; build song song `mvn -T`, CI matrix, hoặc máy đang có tiến trình chiếm cổng thì fail. | `*IntegrationTest`, `*ProbeTest`, `*SmokeTest` |

### 2.4. Contract và thiết kế chưa chốt (chặn các task tiếp theo)

| ID | Ưu tiên / BC | Phát hiện và ảnh hưởng | Vị trí |
|---|---|---|---|
| N21 | **P2 / S** | **Chưa định nghĩa Trip làm gì khi nhận NoDriverFound, hoặc khi thử hết danh sách candidate mà vẫn còn lượt attempt.** State machine chỉ có nhánh NO_DRIVER khi hết 3 attempt hoặc quá deadline; events.md §3.2 viết "chờ attempt kế tiếp (do Saga điều khiển)" nhưng không nói ai phát `TripRequested` attempt mới, lúc nào và với backoff bao nhiêu. T16/T19 sẽ phải tự đoán: có thể spam TripRequested liên tục, hoặc đứng ở SEARCHING đến hết 120 s. | `state-machines.md` §1–2; `events.md` §3.2–3.3 |
| N22 | P3 / S | Mâu thuẫn về thời điểm ghi `trips.driver_id`. Invariant 2 ghi "set một lần khi ACCEPTED", nhưng ví dụ GET detail ở contract §3.3 có `driverId` khi DRIVER_OFFERED và handoff ghi "T16 ghi driver_id + driver_user_id cùng assignment". Quyết định này ảnh hưởng việc driver được offer có GET được trip không, và có xóa `driver_id` khi reject không. | `state-machines.md` §6; `api-contracts.md` §3.3; `handoff.md` |
| N23 | P3 / S | Contract yêu cầu message lỗi vào DLQ phải "mang header `x-dlq-reason`". Nhưng dead-letter bằng `basic.reject`/`nack` **không thể thêm header** (RabbitMQ chỉ thêm `x-death`). Muốn có reason thì consumer phải tự republish sang DLX kèm header rồi ack message gốc; contract cần ghi rõ cách này. | `events.md` §2 và §6 |
| N24 | P3 / S | Driver chỉ lưu response của command **thành công**; command lỗi (409/400) rollback luôn bản ghi claim. Cùng `commandId` vì thế có thể bị 409 trước rồi 201 sau. An toàn chỉ khi Trip **luôn gửi release (tombstone) cho mọi `reservationId` đã bỏ**, kể cả sau khi nhận 409. Quy tắc này chưa có trong contract §4.1 và saga. | `ReservationService.execute`; contract §4.1 |
| N25 | P3 / S | Chưa có cơ chế kích hoạt retry thanh toán: contract ghi "Trip cấp attempt mới sau FAILED" nhưng không có endpoint hay worker nào làm việc đó, nên FAILED thực chất là trạng thái cuối. Cần ghi rõ quyết định bỏ (spec §15 cho phép) hoặc định nghĩa endpoint. | `state-machines.md` §5; `api-contracts.md` §3 |
| N26 | P3 / S | Tên field event không thống nhất: TripCompleted dùng `paymentAttempt`, PaymentSucceeded/Failed dùng `attempt`; PaymentFailed dùng `failureCode` trong khi spec §9 dùng `reason`. Nên thống nhất trước khi code T22–T24. | `events.md` §3.4–3.6 |
| N27 | P3 / S | Passenger không có cách nào biết thông tin tài xế (tên, biển số, loại xe): trip detail chỉ có `driverId` là UUID profile, còn Trip không được đọc DB Driver. Giao diện theo dõi chuyến ở T27 sẽ thiếu dữ liệu nếu không bổ sung field vào proposal/reserve hoặc thêm API. | `api-contracts.md` §3.3; `events.md` §3.2 |
| N28 | P3 / S | Không giới hạn số chuyến đang hoạt động của một passenger: đổi `Idempotency-Key` là tạo được bao nhiêu chuyến SEARCHING cũng được, mỗi chuyến giữ một tài xế. Điều này làm sai lệch C01 và load test P01; cần chốt (một chuyến active mỗi passenger, hoặc ghi rõ là cho phép). | `TripService.create` |

### 2.5. Tính đúng dữ liệu ở Driver

| ID | Ưu tiên / BC | Phát hiện và ảnh hưởng | Vị trí |
|---|---|---|---|
| N29 | P3 / S | Reserve không giới hạn `expiresAt` phía tương lai (ví dụ năm 2100): driver bị RESERVED tới khi có release thủ công, worker expiry không bao giờ chạm tới. F10 mới nói về `expiresAt` đã qua. Nên giới hạn ≤ now + OFFER_TTL + biên. | `ReservationService.validate` |
| N30 | P3 / S | Release chấp nhận reason `OFFER_EXPIRED`/`REJECTED` cho reservation đã **CONFIRMED** và giải phóng driver BUSY. Driver không kiểm tra reason có hợp với state không; invariant 8 hoàn toàn dựa vào việc Trip gửi đúng. | `ReservationService.release` |
| N31 | P3 / S | Biển số không được chuẩn hóa (trim, hoa/thường, khoảng trắng): `51A-123.45` và `51a-123.45 ` được coi là hai xe khác nhau, lách được ràng buộc unique. `displayName` lưu nguyên chưa trim, trong khi Identity lưu `fullName` đã trim. | `DriverService.createProfile`/`validateProfileInput` |

### 2.6. Schema lệch đặc tả §7

| ID | Ưu tiên / BC | Phát hiện và ảnh hưởng | Vị trí |
|---|---|---|---|
| N32 | P3 / S | Thiếu cột/ràng buộc spec §7 yêu cầu: `drivers.version`; `trips.reservation_id`; `vehicles.updated_at`; `trip_events.id` là BIGSERIAL thay vì UUID và không có unique `(trip_id, type, version)` để chống ghi trùng lịch sử khi retry (T25 yêu cầu); `processed_commands` và `idempotency_keys` không có cơ chế dọn dữ liệu cũ. Những chỗ lệch có chủ ý cần được ghi lý do trong báo cáo. | Driver V2–V4; Trip V2–V3 |
| N33 | P3 / S | Bảng `outbox` thiếu dữ liệu để dựng envelope §2 và để publisher retry: không có `aggregate_version` (phải chụp lúc ghi, không suy lại được về sau), `schema_version`, `producer`, `routing_key`, `attempts`, `next_attempt_at`, `last_error`. T13 sẽ cần migration bổ sung. | `trip-service/.../V2__trips.sql` |

### 2.7. Frontend

| ID | Ưu tiên / BC | Phát hiện và ảnh hưởng | Vị trí |
|---|---|---|---|
| N34 | P3 / S | Bỏ qua `expiresIn`: không tự đăng xuất khi JWT hết hạn, chỉ phát hiện khi gặp 401. Người đã đăng nhập mở `/login` không được chuyển về trang của mình. `fetch` không có timeout hay AbortController, nên khi backend treo UI chờ vô hạn (chỉ được Nginx cắt sau 3 s). | `client.ts`, `session.ts`, `LoginPage.tsx`, `App.tsx` |

### 2.8. Quy trình, cấu trúc repo và bàn giao

| ID | Ưu tiên / BC | Phát hiện và ảnh hưởng | Vị trí |
|---|---|---|---|
| N35 | **P1 / S** | **Workspace không phải git repository** (không có `.git`). 12 task đã DONE mà chưa có commit nào. Đề §10.1 bắt buộc lịch sử commit của từng thành viên và tag cuối; đề §12 ghi rõ "thành viên không chứng minh được đóng góp" là không đạt. Nếu repo GitHub chỉ có ở nơi khác thì cần đồng bộ ngay; nếu chưa có thì phải tạo sớm. Lịch sử dựng lại về sau không phải bằng chứng hợp lệ, và spec §1 cấm tạo lịch sử đóng góp giả. | root |
| N36 | **P2 / S** | **Thiếu `src/` và `tests/`** — hai thư mục đề §10.1 bắt buộc. 5 service và `web/` đang nằm ở root; `nginx.conf` ở `deploy/nginx/` (spec §16: `deploy/nginx.conf`); init PG ở `infra/` (spec: `deploy/postgres/init/`). README còn ghi sai: "`src/` chứa 5 backend modules + `web/`". Càng để muộn thì di chuyển càng tốn công (POM modules, đường dẫn Compose, script, tài liệu). | root; `README.md` mục "Cấu trúc repository" |
| N37 | **P2 / S** | `handoff.md`, `backlog.md`, `progress.md` và README **không nhắc tới bản rà soát 20/10**. Handoff vẫn ghi "không có blocker" và đề xuất làm tiếp T13/T14, trong khi F01 (P1) còn mở. Thành viên khác hoặc model khác đọc handoff sẽ xây tiếp trên nền còn lỗ hổng. | `docs/handoff.md` mục "Task kế tiếp" |
| N38 | P3 / S | Stack thực tế lệch spec §4 mà chưa ghi lý do: README ghi "RabbitMQ + Spring AMQP (từ T04)" nhưng chưa POM nào có Spring AMQP; chưa có OpenAPI (spec §4); chỉ Identity dùng Spring Data JPA, các service còn lại dùng JdbcTemplate; Spring Security chỉ dùng phần crypto (xem N10). | `README.md` mục Stack; các `pom.xml` |
| N39 | P2 / S | Tiến độ: theo spec §20.7, đến 25/10 phải xong T06–T14, hiện T13/T14 vẫn TODO. Còn 24 task + 32 phát hiện cũ + 40 phát hiện mới trong khoảng 32 ngày tới hạn nộp 22/11 (giữa kỳ dự kiến 16/11). Matching và Payment vẫn là skeleton, nên hiện chỉ có 3/4 business service tối thiểu thực sự hoạt động (đề §7.2). | `docs/backlog.md` |
| N40 | P3 / S | Không có file hay script nào tự kiểm tra ranh giới service (ví dụ: không service nào chứa connection string của DB khác, không có secret trong file tracked). Acceptance cuối (spec §13) yêu cầu chứng minh hai điều này; nên có script quét sớm, không đợi tới T32/T36. | `scripts/` |

---

## 3. Phần chưa triển khai

**24 task T13–T36 đều TODO** (bảng chi tiết phần còn thiếu của từng task giữ nguyên như §5 bản 20/10): T13 outbox publisher · T14 inbox/retry/DLQ · T15 matching · T16 proposal + reserve · T17 offer · T18 accept · T19 reject · T20 cancel + tranh chấp · T21 timeout/restart Saga · T22 start/complete · T23 payment · T24 nhận payment result · T25 notifications + events API · T26 seed/smoke · T27 UI passenger · T28 UI driver · T29 demo + container hóa · T30 C01/C02 · T31 R01/R02 · T32 S01/S02 · T33 k6 · T34 CI + clean deploy · T35 report · T36 rà soát cuối.

**20 file/thư mục theo spec §16 và đề §10 còn thiếu** (đã kiểm tra bằng `test -e`):

- Thư mục: `src/`, `tests/`, `.github/workflows/ci.yml`, `deploy/postgres/init/` (đang ở `infra/`), `deploy/nginx.conf` (đang ở `deploy/nginx/`).
- Script: `seed-demo.sh`, `smoke-test.sh`, `demo-concurrency.sh`, `demo-recovery.sh`, `replay-dlq.sh`, `reset-demo.sh`.
- Tài liệu: `architecture.md`, `saga.md`, `test-plan.md`, `test-results.md`, `task-assignment.md`, `demo-script.md`, `report.md`, `report.pdf`, `slides.pdf`.
- Không có Dockerfile nào cho 5 service và frontend (spec §16: "mỗi backend module có Dockerfile").

---

## 4. Kiểm tra đã chạy trong lượt này

| Kiểm tra | Kết quả |
|---|---|
| So mtime file với thời điểm sửa cuối 20/10 23:20 | Chỉ `FULL_PROJECT_REVIEW_2026-10-20.md` mới hơn → code không đổi |
| `mvnw -B clean verify` (JDK 21) | Exit 0; surefire: Identity 19, Driver 67, Trip 32, Matching 6, Payment 6 = **130 tests, 0 fail/error/skip** |
| Build log | 19 chỗ dính `}{"timestamp"` → F20 còn |
| `npm test` (Node 24.18.0) | 13/13 pass |
| `npm audit --json` | 8 entries: critical 2, high 1, moderate 5 — giống 20/10 |
| Probe Nginx :8080 (backend tắt) | N01, N04, N05 tái hiện; F22 (502 HTML) tái hiện; không có header `X-Correlation-ID` (N12) |
| JDK 21 `UUID.fromString` | `1-2-3-4-5` và `0-0-0-0-0` được chấp nhận (N13) |
| So key `.env` với `.env.example` (chỉ tên key) | `.env` thiếu 5 key (N08) |
| Kiểm tra cấu trúc spec §16 | 20 mục thiếu (§3) |

Không chạy: E2E trình duyệt, kill JVM thật, load test, quét SCA cho backend và image. N16 (secure context) và N14 (Linux host) dựa trên hành vi đã được tài liệu hóa của trình duyệt và Docker, chưa chạy trên VM thật.

---

## 5. Thứ tự xử lý đề xuất

1. **Quy trình (trong ngày):** N35 tạo hoặc đồng bộ git repo; N37 ghi bản rà soát này và bản 20/10 vào handoff và backlog thành blocker; N36 chuyển cấu trúc sang `src/` và `tests/` **trước** T13 để tránh di chuyển lại nhiều lần.
2. **Bảo mật nền:** F01 + N10 (chuyển sang `SecurityFilterChain`), N01, N02, N04; sau đó F02–F05, N03, N07–N09.
3. **Chốt contract trước T13–T16:** N15 (mandatory/returns + service tự declare topology), N21, N22, N23, N24, F31, N26, N33.
4. **Logging và correlation** (cần cho demo và báo cáo): N11, F20, F21, N12, N13, F22–F24.
5. **Thời gian và dữ liệu Driver/Trip:** F06–F14, N29–N31, R04.
6. **Hạ tầng và deploy:** N14, N16, N17, N18, N19, F25–F29, F32, F30.
7. **Frontend:** F15–F19, N34.
8. Tiếp tục T13 → T36 theo dependency, kèm test cho từng phát hiện đã sửa (Q01–Q06, N20).
