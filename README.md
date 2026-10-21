# DS03 — Ride-Hailing Microservices System

Prototype hệ thống đặt xe mô phỏng Grab/Uber cho môn Distributed Systems.
5 business microservices chạy độc lập, giao tiếp qua REST + RabbitMQ (message queue), mỗi service có database riêng trên PostgreSQL; triển khai bằng Docker Compose.

> **Trạng thái hiện tại (20/10/2026): T01–T12 DONE; đã sửa đủ R01–R07 của review T09/T10/T12.** Backend `clean verify` BUILD SUCCESS — **130 tests, 0 failures/errors/skipped** (Identity 19, Driver 67, Trip 32, Matching 6, Payment 6). Frontend test **13/13**, build thành công. T11 có confirm/release/finish, tombstone, fencing và expiry RESERVED; test restart ứng dụng giữ CONFIRMED/BUSY và hồi phục RESERVED quá hạn. Chi tiết: [T11](docs/tasks/T11.md), [sửa review](docs/T09-T12_REVIEW_FIXES.md). T13/T14 còn TODO; chưa có luồng matching/saga đầu cuối.

## Stack

| Thành phần | Lựa chọn |
|---|---|
| Backend | Java 21, Spring Boot 4.1.1, Maven Wrapper |
| API | Spring Web MVC (starter `spring-boot-starter-webmvc`) |
| Persistence | PostgreSQL + Flyway (từ task T03) |
| Messaging | RabbitMQ + Spring AMQP (từ task T04) |
| Frontend | React + TypeScript + Vite + Ant Design (từ task T08) |
| Deploy | Docker Compose + Nginx (từ task T29) |

## Modules & ports nội bộ

| Module | Port | Vai trò |
|---|---:|---|
| `identity-service` | 8081 | Users, credentials, login, JWT |
| `driver-service` | 8082 | Drivers, vehicles, location, availability, reservations |
| `trip-service` | 8083 | Trips, fare, Saga orchestration, notifications |
| `matching-service` | 8084 | Matching jobs, ranking, proposal history |
| `payment-service` | 8085 | Simulated payments, deduplication |

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
- Init script (`infra/postgres/init/01-create-databases.sh`) chỉ chạy khi volume mới; mật khẩu đọc từ env, không hardcode.

## Local infrastructure — RabbitMQ (T04)

Chạy sau `docker compose up -d` ở mục trên:

```bash
bash infra/rabbitmq/declare-topology.sh   # idempotent — declare topology theo spec mục 9
```

- Topology: topic exchange durable `ride.events` + DLX `ride.events.dlx`; inbox queues `matching.inbox ← trip.requested`, `trip.inbox ← matching.#` **và** `payment.#` (F02 — PaymentSucceeded/PaymentFailed về Trip), `payment.inbox ← trip.completed`; mỗi queue dead-letter sang `ride.events.dlx` về DLQ riêng (`matching.dlq`, `trip.dlq`, `payment.dlq`).
- Declare qua AMQP (escript dùng `amqp_client` có sẵn trong image rabbitmq, chạy one-off container trên network `ridehailing_net`) — không phụ thuộc HTTP API.
- Management UI: http://localhost:15672 (credentials dev trong `.env`). Message persistent + durable topology đã probe qua restart broker (chi tiết: `docs/tasks/T04.md`).

## Chạy services (local) — runbook đầy đủ (cập nhật 2026-10-19, đợt sửa F07)

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

### 2. Declare RabbitMQ topology (idempotent — chạy lại được, không xóa queue/volume)
```bash
bash infra/rabbitmq/declare-topology.sh
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

Migration mới: Driver V4 giữ replay RESERVE cũ và hỗ trợ tombstone thiếu metadata; Trip V3 scope idempotency theo principal/operation/key, thêm driver_user_id để phân quyền bằng JWT sub. Không sửa migration cũ. Dữ liệu Trip được gán thủ công thiếu identity hoặc replay không truy được trip cần sửa rõ owner trước khi migrate; dữ liệu hợp lệ do T12 tạo được nâng cấp tự động. T16 sẽ lưu cả driver_id/profile và driver_user_id/identity từ proposal; hiện chưa có assignment handler.

### 5. Khởi động identity-service (ví dụ) và kiểm tra health
```bash
java -jar src/identity-service/target/identity-service-0.1.0.jar
curl http://localhost:8081/actuator/health
```

**Quan trọng — `.env` vs environment của tiến trình Java:** Compose nạp `.env` cho **container** (PostgreSQL/RabbitMQ). Tiến trình Java chạy local **không tự đọc `.env`**; nó đọc biến môi trường của chính tiến trình, hoặc dev default trong `application.yml` (khớp giá trị `.env.example`). Nếu chạy với env khác, set tường minh trên dòng lệnh, ví dụ:

```bash
read -r -s -p 'Identity DB password: ' IDENTITY_DB_PASSWORD
export IDENTITY_DB_PASSWORD
JWT_PRIVATE_KEY_PATH=keys/jwt-private.pem java -jar src/identity-service/target/identity-service-0.1.0.jar
```

Từ T07: driver-service và trip-service verify JWT bằng public key — chạy local cần set env tiến trình `JWT_PUBLIC_KEY_PATH=keys/jwt-public.pem` (cả hai); driver-service thêm `INTERNAL_API_TOKEN` cho internal API (`X-Internal-Token`). Dev default trong `application.yml` khớp `.env.example`, nhưng khi chạy jar trực tiếp nên set tường minh.

Không in secret (mật khẩu, key) ra console khi minh họa.

Mỗi service chạy trên port riêng (bảng ở trên). Health endpoint: `/actuator/health`.

### 6. Frontend và Nginx (T08)
```bash
cd src/web && npm install && npm run build   # -> src/web/dist (build TRƯỚC khi up nginx — dist được bind-mount)
cd ..
docker compose --env-file .env -f deploy/compose.yaml up -d nginx
# mở http://localhost:8080 — login/register thật, route theo role
```
- Nginx (:8080) serve build + SPA fallback và proxy `/api/auth|drivers|trips|notifications` tới services local trên host (dev mode qua `host.docker.internal`; T29 đổi sang DNS compose khi container hóa services). Body limit **64 KiB** theo contract; vượt giới hạn trả 413 JSON từ trang lỗi tĩnh `src/web/public/errors/payload-too-large.json` (phải build frontend trước để file này có trong `dist/`).
- **N02 — binding loopback:** mỗi service bind chỉ loopback bằng mặc định (`server.address: ${SERVER_ADDRESS:127.0.0.1}`); cổng 8081–8085 không mở ra LAN (netstat: chỉ `127.0.0.1:<port> LISTENING`). Trên Docker Desktop (Windows/Mac), `host.docker.internal` được route về loopback của host nên gateway :8080 **vẫn chạy** với binding mặc định (đã kiểm chứng 2026-10-21: driver trên `127.0.0.1:8082` reach từ container nginx; `curl http://localhost:8080/api/drivers/status` → 401 JSON qua nginx). Khi chạy services trong container (T29): set `SERVER_ADDRESS=0.0.0.0` và KHÔNG publish port.
- Services log structured JSON (spec §11); env tùy chọn `INSTANCE_ID` đặt instanceId trong log (mặc định = UUID per JVM).
- Dev server: `npm run dev` ở `src/web/` (:5173, có proxy `/api/*` tới :8081/:8082/:8083) — không cần nginx khi phát triển UI.
- Token lưu trong sessionStorage; không log token (source + access log Nginx đều không chứa).

## Cấu trúc repository

Theo đề §10.1 và đặc tả mục 16 (C02):
- `src/` — 5 backend modules (`identity-service`, `driver-service`, `trip-service`, `matching-service`, `payment-service`) + frontend `web/`.
- `tests/` — test hệ thống cấp project sẽ đặt ở đây; unit/integration test hiện nằm trong từng module (xem `tests/README.md`).
- `deploy/` — compose + nginx config; `infra/` — init PostgreSQL + declare RabbitMQ topology.
- `scripts/`, `docs/`; `keys/` và `.env` gitignored.

## Tài liệu dự án

- `docs/backlog.md` — 36 task T01–T36, trạng thái và dependency
- `docs/progress.md` — tiến độ từng lượt, lệnh đã chạy, kết quả thật
- `docs/handoff.md` — trạng thái cho lượt tiếp theo
- `docs/tasks/Txx.md` — nhật ký chi tiết mỗi task
