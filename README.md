# DS03 — Ride-Hailing Microservices System

Prototype hệ thống đặt xe mô phỏng Grab/Uber cho môn Distributed Systems.
5 business microservices chạy độc lập, giao tiếp qua REST + RabbitMQ (message queue), mỗi service có database riêng trên PostgreSQL; triển khai bằng Docker Compose.

> **Trạng thái (31/10/2026):** T01–T36 đã thực hiện. Backend 377 test, frontend 100 test, E2E 13 kiểm tra, không lỗi.

## Stack

| Thành phần | Lựa chọn (khớp các `pom.xml` hiện tại) |
|---|---|
| Backend | Java 21, Spring Boot 4.1.1, Maven Wrapper |
| API | Spring Web MVC (starter `spring-boot-starter-webmvc`) + validation |
| Persistence | PostgreSQL + Flyway; **JdbcTemplate** ở driver/trip/matching/payment, chỉ identity-service dùng JPA — xem mục "Lệch đặc tả có chủ đích" |
| Security | Spring Security (filter chain theo role), BCrypt, JWT RS256 (nimbus-jose-jwt), `X-Internal-Token` cho API nội bộ |
| Messaging | RabbitMQ + Spring AMQP. Trip, Matching, Payment: outbox (persistent, `mandatory`, publisher confirms) + inbox (dedup, retry 3 lần, DLQ) |
| Frontend | React + TypeScript + Vite + Ant Design + Leaflet |
| Bản đồ | OpenStreetMap tiles; Nominatim tìm địa chỉ (chỉ Việt Nam); OSRM tính tuyến đường |
| Deploy | Docker Compose + Nginx (từ task T29) |

### Lệch đặc tả có chủ đích

- **JdbcTemplate thay JPA** (driver/trip/matching/payment): các thao tác quan trọng cần SQL tường minh và kiểm chứng được — CAS fencing trên `drivers.status` + `current_reservation_id`/`current_trip_id`, claim idempotency bằng `INSERT ... ON CONFLICT DO NOTHING` trong transaction, advisory lock theo passenger (`pg_advisory_xact_lock`), isolation `REPEATABLE READ` cho list. JPA sẽ che các bước này và khó audit hơn. identity-service dùng JPA vì chỉ là CRUD user đơn giản.
- **Một image backend chung cho 5 container** thay vì Dockerfile từng module: nhanh hơn, vẫn là 5 tiến trình độc lập. Test tích hợp nằm trong từng module thay vì `tests/integration/`.

## Modules & ports nội bộ

| Module | Port | Vai trò |
|---|---:|---|
| `identity-service` | 8081 | Users, credentials, login, JWT |
| `driver-service` | 8082 | Drivers, vehicles, location, availability, reservations |
| `trip-service` | 8083 | Trips, fare, Saga orchestration, notifications |
| `matching-service` | 8084 | Matching jobs, ranking, proposal history |
| `payment-service` | 8085 | Simulated payments, deduplication |

## Đặt chuyến bằng địa chỉ và bản đồ

Passenger nhập địa chỉ đón/đến rồi bấm **Tìm** hoặc Enter; hệ thống không gọi tìm kiếm theo từng phím. `trip-service` giới hạn Nominatim trong Việt Nam, trả tối đa 5 lựa chọn và phát token địa chỉ có thời hạn. Sau khi chọn hai địa chỉ, backend gọi OSRM, lưu tuyến GeoJSON, tính khoảng cách/thời gian/giá và trả `quoteToken`. Chuyến chỉ được tạo từ báo giá còn hiệu lực; nếu Nominatim hoặc OSRM lỗi thì hệ thống chặn tính giá/đặt chuyến và yêu cầu thử lại, không dùng khoảng cách ước lượng thay thế.

Passenger và driver đều xem được tên hai địa chỉ cùng tuyến đường trước và trong chuyến. Các endpoint chính là `GET /api/locations/search`, `POST /api/trips/quote`, rồi `POST /api/trips`; chi tiết schema nằm trong `docs/openapi.yaml`. Dịch vụ public miễn phí phù hợp local/demo và có thể bị giới hạn lưu lượng, vì vậy tìm địa chỉ có rate limit + cache và không phù hợp tải production lớn.

## Phân chia dự án cho 3 thành viên

| Phần | Thành viên phụ trách | Task gốc | Module/phạm vi sở hữu | Kết quả chính |
|---|---|---|---|---|
| **1. Trip, Saga và Payment** | **Đặng Xuân Anh** | T12–T13, T16, T18–T25 | `trip-service`, `payment-service` | Tạo và điều phối chuyến, outbox, Saga, vòng đời chuyến, thanh toán, notification và lịch sử sự kiện |
| **2. Driver và Matching** | **Nguyễn Văn Hiếu** | T09–T11, T14–T15, T17 | `driver-service`, `matching-service` | Hồ sơ/vị trí tài xế, giữ chỗ nguyên tử, offer, matching, inbox retry và DLQ |
| **3. Nền tảng, Identity, Web và Deploy** | **Nguyễn Minh Hùng** | T01–T08, T26–T29, T32–T34 | `identity-service`, `src/web`, `deploy/`, `infra/`, `.github/`, các script hệ thống | Kiến trúc nền, xác thực, frontend, Docker/Nginx, seed/smoke, security test, load test và CI |

### Phần 1 — Đặng Xuân Anh: Trip, Saga và Payment

- Xây dựng API báo giá/tạo chuyến, idempotency, phân quyền owner/driver và state machine của trip.
- Phát/nhận event bằng transactional outbox/inbox; điều phối Saga khi offer, accept, reject, cancel, timeout hoặc restart.
- Xử lý start/complete, payment success/failure, notification và lịch sử sự kiện.
- Với phần bản đồ mở rộng: phụ trách backend Nominatim/OSRM, location token, `quoteToken`, tính giá theo tuyến đường và lưu snapshot địa chỉ/GeoJSON vào trip.
- Chịu trách nhiệm chính cho migration và test của `trip-service`/`payment-service`.

### Phần 2 — Nguyễn Văn Hiếu: Driver và Matching

- Xây dựng hồ sơ, xe, vị trí, heartbeat và trạng thái `OFFLINE/AVAILABLE/RESERVED/BUSY` của tài xế.
- Thực hiện reserve nguyên tử, confirm/release/finish, fencing version và tự giải phóng reservation hết hạn.
- Xây dựng matching job, xếp hạng ứng viên, loại tài xế đã từ chối, proposal và cơ chế inbox retry/DLQ.
- Bảo đảm driver được reserve/offer đúng một lần và tương thích với dữ liệu pickup/destination do Trip phát ra.
- Chịu trách nhiệm chính cho migration và test của `driver-service`/`matching-service`.

### Phần 3 — Nguyễn Minh Hùng: Nền tảng, Identity, Web và Deploy

- Chốt API/event contract; dựng Maven multi-module, PostgreSQL tách DB/user, RabbitMQ topology, security và structured logging.
- Xây dựng đăng ký/đăng nhập, JWT, giao diện passenger/driver/demo và cơ chế polling/recovery trên frontend.
- Với phần bản đồ mở rộng: phụ trách form tìm địa chỉ, Leaflet/OpenStreetMap, hiển thị tuyến cho passenger/driver, proxy Nginx và smoke test luồng địa chỉ.
- Đóng gói Docker Compose, seed/reset/smoke, kiểm thử security/load/E2E, CI và hướng dẫn chạy/demo.
- Chịu trách nhiệm chính cho `identity-service`, `src/web`, hạ tầng, triển khai và tài liệu tổng hợp.

### Phần phối hợp bắt buộc

| Công việc chung | Người tham gia | Quy ước bàn giao |
|---|---|---|
| T21 — Timeout/restart Saga | A + B | A sở hữu Saga; B bảo đảm reservation/matching có thể replay và bù trừ an toàn |
| T30 — Test concurrency, T31 — failure/recovery | A + B | Cung cấp kịch bản và bằng chứng cho cả hai phía Trip ↔ Driver/Matching |
| T32 — Security, T34 — CI/clean deploy | C chính, A + B hỗ trợ | C chạy bộ kiểm tra tổng; A/B sửa lỗi trong module mình sở hữu |
| T35 — Report/demo guide, T36 — rà soát cuối | A + B + C | Mỗi người viết và trình bày phần mình; C ghép tài liệu/chạy demo tổng |

Ranh giới tích hợp được giữ bằng REST API và RabbitMQ contract: A không đọc trực tiếp Driver DB, B không đọc trực tiếp Trip DB, C không đưa nghiệp vụ backend vào frontend. Mọi thay đổi liên phần phải cập nhật `docs/openapi.yaml`, event payload liên quan và có integration test trước khi bàn giao.

## Local infrastructure — PostgreSQL (T03)

Yêu cầu: Docker Desktop đang chạy.

```bash
cp .env.example .env          # giá trị dev-only; KHÔNG dùng cho production
docker compose --env-file .env -f deploy/compose.yaml up -d
# kiểm tra 5 DB + 5 user riêng:
docker exec ridehailing-postgres psql -U postgres_admin -Atc "\l"
```

- Mỗi service chỉ truy cập được DB của mình (user `*_svc`); `postgres_admin` chỉ dùng cho init/debug.
- Dữ liệu nằm trong named volume; stop/start không xóa dữ liệu.
- Init script (`deploy/postgres/init/01-create-databases.sh`) chỉ chạy khi volume mới; mật khẩu đọc từ env, không hardcode.

## Local infrastructure — RabbitMQ (T04)

Topology được declare **tự động** bởi service one-shot `topo-declare` trong compose (chạy sau khi broker healthy) — mỗi lần `docker compose up -d`, kể cả sau `down -v`, queue/binding tồn tại mà không cần chạy tay. Script thủ công vẫn giữ làm công cụ phụ:

```bash
bash deploy/rabbitmq/declare-topology.sh   # idempotent — declare topology theo spec mục 9
```

Nếu broker đã chạy sẵn và volume bị reset khi container còn sống, chạy lại script (hoặc `docker compose up -d --force-recreate topo-declare`).

- Topology: topic exchange durable `ride.events` + DLX `ride.events.dlx`; inbox queues `matching.inbox ← trip.requested`, `trip.inbox ← matching.#` **và** `payment.#` (F02 — PaymentSucceeded/PaymentFailed về Trip), `payment.inbox ← trip.completed`; mỗi queue dead-letter sang `ride.events.dlx` về DLQ riêng (`matching.dlq`, `trip.dlq`, `payment.dlq`).
- Declare qua AMQP (escript dùng `amqp_client` có sẵn trong image rabbitmq, chạy one-off container trên network `ridehailing_net`) — không phụ thuộc HTTP API.
- Management UI: http://localhost:15672 (credentials dev trong `.env`). Message persistent + durable topology đã được kiểm tra qua thao tác restart broker.

## Chạy toàn hệ thống bằng Docker Compose (T29) — cách chính

Yêu cầu: **Docker Desktop đang chạy** (`docker ps` để kiểm tra), **Git Bash** (Windows). Không cần JDK/Maven/Node trên host — build backend (Maven reactor 5 modules → 1 image dùng chung) và frontend (Vite → nginx image) đều chạy trong Docker.

```bash
cp .env.example .env          # giá trị dev-only; KHÔNG dùng cho production
bash scripts/generate-dev-keys.sh   # keys/jwt-*.pem (gitignored) — cần TRƯỚC khi build backend image
docker compose --env-file .env -f deploy/compose.yaml up --build -d
# kiểm tra: mọi container healthy (readiness probe gồm DB thật, N17/C06):
docker compose --env-file .env -f deploy/compose.yaml ps
```

- 5 services chạy là **container độc lập** từ cùng image `ridehailing-backend:0.1.0` (`deploy/docker/Dockerfile.backend` — một lần build Maven reactor cho cả 5 modules), mỗi container một `command: java -jar /app/<svc>.jar`. Frontend + proxy conf bake vào image `ridehailing-web:0.1.0` (`deploy/docker/Dockerfile.web`) — không còn bind-mount dist hay `host.docker.internal`.
- JWT key **không** nằm trong image: compose bind-mount read-only — private key chỉ vào `identity-service`, public key vào `identity`/`driver`/`trip`; `matching`/`payment` không có key. Container backend chạy non-root. Linux host: đặt `HOST_UID`/`HOST_GID` theo `id -u`/`id -g` (`.env.example`) để `identity-service` đọc được private key mode 600.
- Biến trong `.env` (`FARE_*`, `RESERVATION_EXPIRY_*`, `JWT_EXPIRES_IN_SECONDS`, `TRIP_MATCH_DEADLINE_SECONDS`, `PAYMENT_SIMULATE_FAIL_RATE`) được compose truyền vào container; demo payment thất bại: đặt `PAYMENT_SIMULATE_FAIL_RATE=1` rồi `up -d payment-service`.
- Public port duy nhất của hệ thống: **8080** (Nginx). Các port 8081–8085 publish **CHỈ 127.0.0.1** (dev/debug — links health demo từ host browser); `/actuator/*` và `/internal/*` trả 404 qua :8080 (private routes không lộ).
- `DB_HOST`, `RABBITMQ_HOST`, `DRIVER_SERVICE_URL` được compose đặt sang DNS tên service (`postgres`, `rabbitmq`, `driver-service`) — services nói chuyện nhau qua network `ridehailing_net`.

Sau khi hệ thống healthy:

```bash
bash scripts/seed-demo.sh            # 3 passengers + 3 drivers (idempotent)
bash scripts/smoke-trip.sh           # full trip ~10–15 s, payment SUCCEEDED
# demo overview UI: mở http://localhost:8080/demo sau khi login (passenger/driver)
bash scripts/correlation-filter.sh <correlation-id>   # grep logs 5 services + nginx theo correlationId
```

Reset dữ liệu dev: `bash scripts/reset-demo.sh --confirm` (hủy trip chưa terminal của demo users, đưa drivers về AVAILABLE — không chạm volume).

## Kiểm thử và CI

| Lệnh | Mục đích | Yêu cầu |
|---|---|---|
| `./mvnw -B clean verify` rồi `bash scripts/ci-test-report.sh` | Backend; báo cáo fail nếu có test bị skip hoặc thiếu report | JDK 21, Docker (Testcontainers). Thiếu Docker thì test **lỗi**, không bị bỏ qua |
| `cd src/web && npm ci && npm test && npm run build` | Frontend | Node theo `.nvmrc` |
| `bash scripts/scan-tracked-secrets.sh` | Quét secret trong file được git theo dõi | git |
| `bash scripts/ci-system-check.sh` | Từ thư mục checkout: tạo `.env` + key nếu thiếu, up, seed, smoke, S01, S02, S03. **Xóa stack + volume khi xong** (`--keep` để giữ) | Docker, curl, openssl; chỉ dùng trong bản checkout dành riêng để kiểm tra |
| `bash scripts/clean-deploy-check.sh --confirm [--head]` | Như trên nhưng từ bản sao sạch, build không cache; tạm dừng rồi khởi động lại stack đang chạy, giữ volume | Docker |
| `bash scripts/load-p01.sh` | Đo tải P01, xem `tests/performance/results/summary.md` | Stack đang chạy |
| `bash scripts/concurrency-c01.sh`, `concurrency-c02.sh`, `recovery-r01.sh`, `recovery-r02.sh`, `recovery-dlq.sh` | Test hệ thống dài hơn, chưa đưa vào CI (C01 ~11–14 phút, R01 ~3 phút) | Stack đang chạy, đã seed |
| `bash scripts/replay-dlq.sh [--queue trip.dlq] [--confirm]` | Xem DLQ; `--confirm` để replay, giữ nguyên `eventId` | Stack đang chạy |
| `bash scripts/demo-concurrency.sh`, `demo-recovery.sh matching\|payment` | Kịch bản ngắn cho demo; không đè kết quả trong `tests/` | Stack đang chạy, đã seed |

CI: `.github/workflows/ci.yml` chạy quét secret, backend, frontend, rồi deploy từ checkout sạch + smoke + S01/S02/S03 + E2E trình duyệt.

## Chạy services local trên host (chế độ dev cũ — tùy chọn)

> Sau T29, cách chính là Docker Compose toàn hệ thống ở mục trên. Chế độ này chỉ còn dùng khi phát triển/debug **một service riêng lẻ** hoặc chạy test; Nginx image proxy sang DNS compose nên full stack qua :8080 yêu cầu backend containerized. Khi chạy Java trên host, chỉ bật hạ tầng (`docker compose --env-file .env -f deploy/compose.yaml up -d postgres rabbitmq topo-declare`) — các port 8081–8085 của backend container sẽ trùng với tiến trình local — và dùng Vite (mục 6) thay cho Nginx.

Yêu cầu trên host: **JDK 21**, **Docker Desktop đang chạy** (`docker ps` để kiểm tra), **Git Bash** (Windows), **OpenSSL** (sinh JWT keys). Dùng Maven Wrapper — không cần cài Maven.

JDK 21 trên máy này nằm tại `~/dev-tools/jdk-21.0.12.1+1`, chưa có trên PATH hệ thống. Cấu hình **cả `JAVA_HOME` và `PATH` trong cùng terminal** trước khi build hoặc chạy service. Chỉ đặt `JAVA_HOME` sẽ chưa đổi Java được gọi bởi lệnh `java -jar`.

Git Bash trên máy này:

```bash
export JAVA_HOME="$HOME/dev-tools/jdk-21.0.12.1+1"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

PowerShell tương đương:

```powershell
$env:JAVA_HOME = 'C:\Users\Hung Nguyen\dev-tools\jdk-21.0.12.1+1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

Kết quả `java -version` phải là **21**. Trên máy khác, thay đường dẫn bằng thư mục JDK 21 đã cài; cấu hình này chỉ áp dụng cho terminal hiện tại.

Tất cả lệnh dưới đây chạy **từ root repository** (`D:\Working\Distributed system`).

### 1. Tạo `.env` và khởi động hạ tầng (PostgreSQL + RabbitMQ)
```bash
cp .env.example .env          # giá trị dev-only; KHÔNG dùng cho production
docker compose --env-file .env -f deploy/compose.yaml up -d
# kiểm tra 5 DB + 5 user riêng:
docker exec ridehailing-postgres psql -U postgres_admin -Atc "\l"
```

### 2. Declare RabbitMQ topology (tự động qua service one-shot `topo-declare` trong compose)
Sau `up -d`, kiểm tra queue/binding đã tồn tại:
```bash
docker exec ridehailing-rabbitmq rabbitmqctl list_queues name durable
```
Nếu broker chạy sẵn mà volume bị reset khi container còn sống, chạy lại script thủ công (idempotent — không xóa queue/volume):
```bash
bash deploy/rabbitmq/declare-topology.sh
```

### 3. Sinh JWT dev keys (một lần; `--force` để tạo lại) — cần OpenSSL
```bash
bash scripts/generate-dev-keys.sh   # -> keys/jwt-private.pem + keys/jwt-public.pem (gitignored)
```

### 4. Build và test
```bash
./mvnw -B clean verify              # Windows cmd: mvnw.cmd -B clean verify
```
Integration tests dùng Testcontainers — **Docker daemon phải đang chạy**; nếu thiếu, báo rõ test nào chưa chạy, không coi skipped là pass.

T11 bổ sung `RESERVATION_EXPIRY_ENABLED=true` và `RESERVATION_EXPIRY_FIXED_DELAY_MS=1000` cho driver-service (env của tiến trình Java local). Worker scan tối đa 100 RESERVED quá hạn mỗi lượt, không expire CONFIRMED. Confirm/release/finish là API nội bộ có X-Internal-Token; không proxy qua Nginx.

Migration mới: Driver V4 giữ replay RESERVE cũ và hỗ trợ tombstone thiếu metadata; Trip V3 scope idempotency theo principal/operation/key, thêm driver_user_id để phân quyền bằng JWT sub. Không sửa migration cũ. Dữ liệu Trip được gán thủ công thiếu identity hoặc replay không truy được trip cần sửa rõ owner trước khi migrate; dữ liệu hợp lệ do T12 tạo được nâng cấp tự động. Từ T16, trip-service ghi `trips.driver_id`/`driver_user_id` + `reservation_id` khi `DRIVER_OFFERED` (chốt N22: assignment hiệu lực chỉ từ ACCEPTED — endpoint accept thuộc T18).

### 5. Khởi động identity-service (ví dụ) và kiểm tra health
```bash
java -jar src/identity-service/target/identity-service-0.1.0.jar
curl http://localhost:8081/actuator/health
```

**Quan trọng — `.env` vs environment của tiến trình Java:** Compose nạp `.env` cho **container** (PostgreSQL/RabbitMQ). Tiến trình Java chạy local **không tự đọc `.env`**; nó chỉ đọc biến môi trường của chính tiến trình. Từ đợt sửa N09, mật khẩu DB **không còn dev default** trong `application.yml` — thiếu env thì service dừng ngay với thông báo nêu tên biến thiếu (không in giá trị). Set tường minh trên dòng lệnh, ví dụ:

```bash
read -r -s -p 'Identity DB password: ' IDENTITY_DB_PASSWORD
export IDENTITY_DB_PASSWORD
JWT_PRIVATE_KEY_PATH=keys/jwt-private.pem java -jar src/identity-service/target/identity-service-0.1.0.jar
```

Từ T07: driver-service và trip-service verify JWT bằng public key — chạy local cần set env tiến trình `JWT_PUBLIC_KEY_PATH=keys/jwt-public.pem` (cả hai); driver-service thêm `INTERNAL_API_TOKEN` cho internal API (`X-Internal-Token`). Mỗi service cần đúng biến mật khẩu DB của mình (`IDENTITY_DB_PASSWORD`, `DRIVER_DB_PASSWORD`, `TRIP_DB_PASSWORD`, `MATCHING_DB_PASSWORD`, `PAYMENT_DB_PASSWORD`) — thiếu thì service dừng với thông báo nêu tên biến (N09). Từ T13, trip-service publish event lên RabbitMQ nên cần thêm `RABBITMQ_PASSWORD` (cùng quy tắc N09, không có giá trị mặc định); `RABBITMQ_USER` mặc định `ridehailing_dev`, `RABBITMQ_HOST`/`RABBITMQ_AMQP_PORT` mặc định `localhost:5672`. Broker tắt thì trip-service vẫn chạy, event nằm ở trạng thái PENDING trong bảng `outbox` và được gửi khi broker lên lại. Từ T14, matching-service cũng là consumer RabbitMQ (inbox `matching.inbox`) nên cần thêm `RABBITMQ_PASSWORD`; broker tắt thì matching-service vẫn khởi động — listener tự retry kết nối ở nền và event được xử lý khi broker lên lại. Từ T15, matching-service gọi candidates API driver-service (internal) nên chạy local cần thêm `DRIVER_SERVICE_URL` (mặc định `http://localhost:8082`) và `INTERNAL_API_TOKEN` (**không có giá trị mặc định** — thiếu thì service dừng ngay với thông báo nêu tên biến, cùng quy tắc N09). Từ T16, trip-service consume `DriverProposed`/`NoDriverFound` từ queue `trip.inbox` và gọi reserve API driver-service (internal) nên chạy local cũng cần thêm `DRIVER_SERVICE_URL` + `INTERNAL_API_TOKEN` (cùng quy tắc N09); broker tắt thì trip-service vẫn khởi động — event nằm trong bảng `inbox_events`/outbox PENDING và được xử lý khi broker lên lại.

Không in secret (mật khẩu, key) ra console khi minh họa.

Mỗi service chạy trên port riêng (bảng ở trên). Health endpoint: `/actuator/health`.

### 6. Frontend (T08) và Nginx (T29)
```bash
cd src/web && npm install && npm run dev   # http://localhost:5173 — proxy /api/* tới service local :8081/:8082/:8083
```
Nginx không còn dùng được ở chế độ này: frontend + `deploy/nginx.conf` được bake vào image `ridehailing-web` và proxy sang DNS service compose (mục "Chạy toàn hệ thống").
- Nginx (:8080) serve build + SPA fallback và proxy `/api/auth|drivers|trips|locations|notifications` tới **upstream DNS tên service compose** (`identity-service:8081`, `driver-service:8082`, `trip-service:8083`) — T29; `server ... resolve` nên gateway không crash khi backend chưa chạy và theo IP mới khi container được recreate. Body limit **64 KiB** theo contract; vượt giới hạn trả 413 JSON với correlationId do Nginx tự sinh/giữ (`error_page 413` + biến `$cid` — không cần trang lỗi tĩnh). Lỗi upstream 502/503/504 và rate limit cũng trả JSON `UPSTREAM_UNAVAILABLE` / `RATE_LIMITED` theo contract §1.2; Nginx giữ/sinh correlation ID (`$request_id_uuid`) cho mọi request và ghi vào access log.
- **N02 — binding loopback:** mỗi service bind chỉ loopback bằng mặc định khi chạy local (`server.address: ${SERVER_ADDRESS:127.0.0.1}`); trong container (T29) compose đặt `SERVER_ADDRESS=0.0.0.0` và publish port **CHỈ 127.0.0.1** — không mở ra LAN, không expose qua :8080.
- Services log structured JSON (spec §11); env tùy chọn `INSTANCE_ID` đặt instanceId trong log (mặc định = UUID per JVM).
- Dev server: `npm run dev` ở `src/web/` (:5173, có proxy `/api/*` tới :8081/:8082/:8083) — không cần nginx khi phát triển UI.
- Token lưu trong sessionStorage; không log token (source + access log Nginx đều không chứa).

### 7. Seed & smoke demo (T26)
Sau khi hạ tầng + 5 services chạy, seed 3 passengers/3 drivers và smoke một chuyến hoàn chỉnh — chỉ qua public API của Nginx :8080 (chạy Java trên host thì truyền `http://localhost:5173`, Vite proxy), không ghi DB trực tiếp, không chạm volume:

```bash
bash scripts/seed-demo.sh            # idempotent: chạy lại không tạo trùng (409 EMAIL_TAKEN -> skip)
bash scripts/smoke-trip.sh           # tìm địa chỉ -> route/quote -> create -> offer -> accept -> start -> complete -> payment
bash scripts/reset-demo.sh --confirm  # hủy trip chưa terminal của demo users, đưa drivers về AVAILABLE (bắt buộc --confirm)
```

`SEED_PASSWORD` đọc từ `.env` (dev-only). Smoke tìm hai địa chỉ Hà Nội thật qua Nominatim; 3 driver demo nằm trong radius matching 5000 m của khu vực pickup. Smoke dùng Idempotency-Key mới mỗi lần chạy; trip COMPLETED không chặn create kế tiếp.

## Cấu trúc repository

Theo đề §10.1 và đặc tả mục 16 (C02):
- `src/` — 5 backend modules (`identity-service`, `driver-service`, `trip-service`, `matching-service`, `payment-service`) + frontend `web/`.
- `tests/` — E2E, kịch bản k6 và raw results của test đồng thời, phục hồi, bảo mật, hiệu năng; unit/integration test nằm trong từng module.
- `deploy/` — `compose.yaml`, `nginx.conf`, `postgres/init/` (init 5 DB/user), `rabbitmq/` (declare topology) — đúng cấu trúc spec §16.
- `scripts/`; `docs/openapi.yaml` chứa đặc tả OpenAPI; `keys/` và `.env` được Git bỏ qua.

## Kịch bản demo

### 1. Chuẩn bị dữ liệu demo

Khởi động stack theo mục **Chạy toàn hệ thống bằng Docker Compose**, sau đó kiểm tra các container đều healthy và tạo dữ liệu demo:

```bash
docker compose --env-file .env -f deploy/compose.yaml ps
bash scripts/seed-demo.sh
bash scripts/reset-demo.sh --confirm
```

`seed-demo.sh` tạo ba passenger và ba driver qua public API; chạy lại không tạo bản ghi trùng. `reset-demo.sh --confirm` hủy các chuyến demo chưa kết thúc và đưa tài xế về `AVAILABLE`, không xóa volume hoặc lịch sử chuyến đã hoàn thành.

| Vai trò | Tài khoản demo |
|---|---|
| Passenger | `passenger-1@demo.local`, `passenger-2@demo.local`, `passenger-3@demo.local` |
| Driver | `driver-1@demo.local`, `driver-2@demo.local`, `driver-3@demo.local` |

Tất cả tài khoản dùng mật khẩu trong biến `SEED_PASSWORD` của `.env`; giá trị dev mẫu trong `.env.example` là `SeedPass123!`. Chỉ dùng các credential này cho local/demo.

### 2. Demo chính trên giao diện

Mở `http://localhost:8080` bằng hai cửa sổ hoặc hai browser profile riêng để giữ đồng thời phiên passenger và driver.

1. Đăng nhập passenger bằng `passenger-1@demo.local`.
2. Tại **Đặt chuyến mới**, nhập `Quảng trường Ba Đình, Hà Nội` làm nơi đón, bấm **Tìm** hoặc Enter rồi chọn một kết quả.
3. Nhập `Nhà hát Lớn Hà Nội` làm điểm đến, tìm và chọn kết quả tương ứng.
4. Kiểm tra bản đồ đã có hai marker và tuyến đường; giao diện phải hiện quãng đường, thời gian dự kiến và giá do backend tính.
5. Bấm **Đặt chuyến**. Chuyến chuyển từ `SEARCHING` sang `DRIVER_OFFERED` sau khi matching tìm được tài xế.
6. Ở cửa sổ driver, đăng nhập một tài khoản driver và bảo đảm trạng thái là `AVAILABLE`. Matching chọn tài xế phù hợp trong ba tài khoản; nếu driver đang mở không có offer, kiểm tra `driver-2` và `driver-3`, hoặc đặt hai tài xế đó `OFFLINE` trước khi tạo chuyến để cố định người nhận.
7. Tài xế kiểm tra tên địa chỉ, tuyến trên bản đồ, quãng đường, thời gian và giá, sau đó bấm **Nhận chuyến**.
8. Tài xế lần lượt bấm **Bắt đầu chuyến** và **Hoàn thành chuyến**. Passenger theo dõi các trạng thái `ACCEPTED` → `IN_PROGRESS` → `COMPLETED` trong trang chi tiết.
9. Chờ `paymentStatus` chuyển từ `PENDING` sang `SUCCEEDED` (mặc định). Kiểm tra tab **Thông báo** của passenger và driver có các sự kiện offer, accept, complete và payment.

Tuyến hiển thị cho driver là snapshot đã lưu khi passenger đặt chuyến; polling không gọi lại Nominatim hoặc OSRM. Nếu tìm địa chỉ hoặc định tuyến lỗi, hệ thống khóa báo giá/đặt chuyến và yêu cầu thử lại, không dùng khoảng cách ước lượng.

### 3. Demo tự động luồng hoàn chỉnh

Có thể chứng minh cùng luồng qua public API bằng một lệnh:

```bash
bash scripts/smoke-trip.sh
```

Script tìm thật hai địa chỉ tại Hà Nội, lấy route/quote, tạo chuyến, xác định driver được offer, accept, start, complete, chờ payment và kiểm tra notification/event history. Kết quả đạt khi dòng cuối báo `SMOKE PASS` và payment là `SUCCEEDED`.

### 4. Demo tài xế từ chối và Saga tìm lại

1. Reset dữ liệu demo rồi tạo một chuyến mới như mục 2.
2. Driver đang nhận offer bấm **Từ chối**.
3. Kiểm tra driver đó trở lại `AVAILABLE`, reservation được release và chuyến quay lại `SEARCHING` để tìm tài xế khác.
4. Driver đã từ chối không được đề xuất lại trong cùng chuyến; nếu không còn ứng viên, chuyến kết thúc ở `NO_DRIVER`.

### 5. Demo concurrency

Chạy một lượt C01: hai passenger đặt xe gần như đồng thời khi chỉ có một driver khả dụng.

```bash
bash scripts/demo-concurrency.sh
```

Kết quả mong đợi: chỉ một chuyến giữ được driver, chuyến còn lại về `NO_DRIVER`; `max_db_reserved=1`, không có reservation chồng nhau và không còn pointer mồ côi sau cleanup.

### 6. Demo failure/recovery

```bash
# Dừng Matching, tạo trip, quan sát queue giữ message rồi khởi động lại (~20 giây)
bash scripts/demo-recovery.sh matching

# Kill Payment sau commit nhưng trước ACK; redelivery không tạo payment trùng (~40 giây)
bash scripts/demo-recovery.sh payment
```

Kịch bản Matching đạt khi trip tiếp tục đến `DRIVER_OFFERED` sau khi service chạy lại. Kịch bản Payment đạt khi chỉ có một payment row và inbox ghi nhận duplicate delivery thay vì thu tiền lần hai.

### 7. Theo dấu một request qua các service

Trong DevTools → **Network**, chọn một request API và lấy response header `X-Correlation-ID`, sau đó chạy:

```bash
bash scripts/correlation-filter.sh <correlation-id>
```

Script tìm cùng correlation ID trong log của Nginx và năm service, giúp trình bày đường đi của request/event qua hệ thống phân tán. Có thể mở thêm `http://localhost:8080/demo` sau khi đăng nhập để xem tổng quan chuyến và các health link dành cho local.

### 8. Khôi phục nhanh khi demo bị gián đoạn

Nếu gặp `ACTIVE_TRIP_EXISTS`, driver bị `RESERVED/BUSY`, hoặc đã đóng trình duyệt giữa chuyến, chạy:

```bash
bash scripts/reset-demo.sh --confirm
bash scripts/seed-demo.sh
```

Nếu container chưa healthy, kiểm tra bằng `docker compose --env-file .env -f deploy/compose.yaml ps` và xem log service liên quan trước khi chạy lại kịch bản.

## Đặc tả API

`docs/openapi.yaml` mô tả các endpoint public và internal.
