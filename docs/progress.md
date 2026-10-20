# Progress — DS03 Ride-Hailing Microservices

## Lượt 2026-10-17 — Task T01: Khảo sát và backlog (DONE)

### Môi trường thực tế (kết quả lệnh thật, chạy trên Windows host)

| Công cụ | Lệnh đã chạy | Kết quả | Đánh giá |
|---|---|---|---|
| Java | `java -version` | 1.8.0_491 (JRE); không có `javac`; `JAVA_HOME` rỗng; `C:\Program Files\Java` chỉ chứa `jre1.8.0_491` | ❌ **Thiếu JDK 21** — spec yêu cầu Java 21 cho Spring Boot 3.x |
| Maven | `mvn -version` | command not found | ⚠️ Chưa cài trên host; kế hoạch dùng Maven Wrapper (T02) tự tải Maven, nhưng vẫn cần JDK 21 để build/test local |
| Node / npm | `node --version`, `npm --version` | v24.18.0 / 11.16.0 | ✅ Đủ cho Vite/React/TypeScript (sẽ pin trong package.json) |
| Docker CLI | `docker --version`, `docker compose version` | Docker 29.8.1, Compose v5.5.1 | ⚠️ Cài sẵn nhưng **daemon chưa chạy** (`docker ps` lỗi: không tìm thấy npipe dockerDesktopLinuxEngine) — cần bật Docker Desktop trước T03/T04 và mọi integration test |

### Workspace hiện tại
- Folder `D:\Working\Distributed system` trống (đã dọn sạch ở lượt trước); không có AGENTS.md, không có mã nguồn cũ.
- Kết luận: tạo repository mới từ đầu theo cấu trúc mục 16 của spec; không có code hợp lệ nào cần giữ lại.

### Kiến trúc dự kiến (tóm tắt để các task sau tham chiếu)
- 5 business service độc lập: `identity-service` :8081, `driver-service` :8082, `trip-service` :8083, `matching-service` :8084, `payment-service` :8085.
- PostgreSQL 1 container chứa 5 database + 5 user riêng; mỗi service chỉ truy cập DB của mình (Flyway quản lý schema).
- RabbitMQ: topic exchange `ride.events`, durable queues, DLQ; outbox/inbox pattern; Saga orchestrator nằm ở trip-service.
- Nginx :8080 phục vụ React frontend và proxy `/api/auth|drivers|trips`; matching/payment không có public API nghiệp vụ.
- Frontend: React + TypeScript + Vite + Ant Design, polling 2s.

### Việc còn thiếu trước task kế tiếp (blocker)
1. **JDK 21**: cài trên host (khuyến nghị Eclipse Temurin 21, set `JAVA_HOME`) HOẶC chấp nhận build/test trong Docker image `eclipse-temurin:21`. Chưa quyết định — cần người dùng chọn.
2. **Docker daemon**: bật Docker Desktop và xác minh bằng `docker ps` trước T03/T04.

## Lượt 2026-10-17 — Task T02: Backend skeleton (DONE)

### Toolchain đã cài trên host (giải quyết blocker JDK của lượt trước)
| Công cụ | Vị trí | Ghi chú |
|---|---|---|
| JDK Temurin 21.0.12.1 | `~/dev-tools/jdk-21.0.12.1+1` | Set `JAVA_HOME="/c/Users/Hung Nguyen/dev-tools/jdk-21.0.12.1+1"` mỗi phiên shell (không trên PATH hệ thống) |
| Maven 3.10.0 | `~/dev-tools/apache-maven-3.10.0` | Wrapper pin cùng phiên bản này |
| `~/.m2/settings.xml` | mirror Maven Central → `https://repo1.maven.org/maven2/` | Workaround DNS máy này; **không commit** vào repo |

### Việc đã làm
- Tạo root `pom.xml` (`com.ridehailing:ride-hailing-system:0.1.0`, pin Spring Boot 4.1.1) + 5 module độc lập, mỗi module có Application class và `application.yml` với port riêng (8081–8085); thêm `README.md`, `.gitignore`; chạy goal `wrapper:wrapper`.
- **Build**: `mvn -DskipTests package` → `BUILD SUCCESS`, đủ 5 jar `<module>/target/<module>-0.1.0.jar`.
- **Xác minh wrapper files**: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` tồn tại (ls, 2026-10-17).
- **Chạy thật 5 service** từ jar làm tiến trình nền, poll `/actuator/health`: cả 5 trả về `{"groups":["liveness","readiness"],"status":"UP"}` trên đúng port (8081 identity, 8082 driver, 8083 trip, 8084 matching, 8085 payment); kill toàn bộ tiến trình sau kiểm tra.

### Kết luận / blocker còn lại
- T02 DONE — bằng chứng chi tiết trong `docs/tasks/T02.md`.
- Blocker duy nhất cho task kế tiếp: **Docker daemon chưa chạy** (cần bật Docker Desktop trước T03/T04).

## Lượt 2026-10-17 — Task T03: PostgreSQL riêng quyền (DONE)

### Môi trường
- Docker Desktop khởi động bằng `Start-Process` → daemon UP sau ~10s.
- Phát hiện container + volume `ridehailing-postgres`/`ridehailing_pgdata` từ phiên trước (workspace đã wipe, Docker state còn). Các DB trống (không bảng/flyway history) → thay bằng container do compose quản lý trên volume mới; init script chạy lại từ đầu.

### Việc đã làm
- Kiểm tra cấu hình có sẵn: `deploy/compose.yaml`, `.env`/`.env.example` (dev-only), `infra/postgres/init/01-create-databases.sh` (5 user/DB, REVOKE CONNECT FROM PUBLIC, mật khẩu đọc từ env).
- **Thêm** dependency `flyway-database-postgresql` vào cả 5 module pom (Flyway 10+ bắt buộc cho PostgreSQL — thiếu thì migration không chạy được).
- `docker compose --env-file .env -f deploy/compose.yaml up -d` → healthy; init tạo đủ 5 DB/5 user.
- Build + chạy thật 5 service với env từ `.env`.

### Bằng chứng chính (chi tiết trong docs/tasks/T03.md)
- Flyway log: `Database: jdbc:postgresql://localhost:5432/<db> (PostgreSQL 17.11)` + schema history tạo mới ở đủ 5 DB; health UP :8081–:8085.
- `flyway_schema_history` = `1|baseline`, `schema_meta.note` = 'T03 baseline' ở mỗi DB.
- Cross-access: identity_svc → driver_db bị `permission denied for database "driver_db"`.
- Stop/start postgres → dữ liệu kiểm tra vẫn còn (persistence OK).

### Kết luận / blocker
- T03 DONE. Docker daemon đang chạy — không còn blocker cho T04.

## Lượt 2026-10-17 — Task T04: RabbitMQ và network (DONE)

### Môi trường
- Docker Desktop UP; `ridehailing-postgres` + `ridehailing-rabbitmq` healthy (compose từ lượt trước). Management API tại `127.0.0.1:15672`, AMQP `127.0.0.1:5672`.

### Việc đã làm
- **Sửa** `infra/rabbitmq/declare_topology.es`: (1) `ok_call/1` chấp nhận reply `_ok` trần (amqp_client 3.13.7 trả decoded method, không bọc `{ok,_}`); guard chỉ dùng BIF vì Erlang cấm gọi hàm local trong guard; (2) `arguments` của queue.declare đổi sang list of `{Key, TypeAtom, Value}` — định dạng field table thật của client 3.13.7, kiểm chứng bằng decompile beam `rabbit_binary_generator`.
- Chạy `bash infra/rabbitmq/declare-topology.sh` → declare thành công: 2 exchange topic durable (`ride.events`, `ride.events.dlx`), 3 inbox queue + 3 DLQ (durable, dead-letter args), 6 bindings — idempotent.
- Xác minh qua management API: durable flags, bindings từng queue; DNS `getent hosts rabbitmq postgres` trong one-off container trên `ridehailing_net`; probe publish/consume persistent message (`delivery_mode:2`) envelope chuẩn mục 9; DLQ proof (reject → `matching.dlq`, header `x-death`); persistence qua `docker stop/start` broker.
- Dọn file test tạm `t1–t8.es`; cuối lượt cả 6 queue = 0 message.

### Bằng chứng chính (chi tiết trong docs/tasks/T04.md)
- Declare: `T04: topology declared over AMQP (host=rabbitmq).`
- Publish → `{"routed":true}`; consume từ `matching.inbox`: payload nguyên vẹn, `delivery_mode:2`.
- DNS: `172.20.0.3 rabbitmq`, `172.20.0.2 postgres`.
- Restart broker: message persistent trong `trip.inbox` vẫn còn sau stop/start (count 1 → consume OK).

### Kết luận / blocker
- T04 DONE. Không có blocker cho T05 (dependency T01 đã DONE).

## Lượt 2026-10-18 — Task T05: Contract API và event (DONE)

### Việc đã làm
- Đọc lại đề gốc (COURSE PROJECT SPECIFICATION.pdf §7) + spec DS03 mục 8–11 để đối chiếu trước khi chốt.
- Tạo `docs/api-contracts.md`: quy ước chung, bảng mã lỗi (400/401/403/404/409×6/413/500), JWT RS256 claims + internal credential (`X-Internal-Token`), 18 public endpoint + 6 internal endpoint với DTO/ví dụ JSON, công thức fare, tham số demo env, correlation ID rules, bảng 10 quyết định chốt.
- Tạo `docs/events.md`: envelope schemaVersion=1 + validation consume, 6 event types với payload cụ thể (DriverProposed = danh sách candidate có thứ tự), quy tắc dedup/ACK/retry in-process 1s-3s-10s→DLQ/stale ignore, replay DLQ; topology đối chiếu khớp T04.
- Tạo `docs/state-machines.md`: Trip (+transient CANCELLING), Saga phases/intents + worker claim CAS, Reservation (tombstone, TTL chỉ trên RESERVED), Driver, Payment; invariant 1–8 → cơ chế enforce. Chốt thêm cột schema: `reservations.terminal_reason`, `sagas.context`/`lease_owner`, `trips.payment_attempt`.

### Kiểm tra (task tài liệu — đối chiếu nội dung với spec)
- Coverage checklist api-contracts.md §8: 18/18 public + 6/6 internal endpoint ánh xạ; 6/6 event trong events.md; mỗi endpoint/event có ví dụ JSON hợp lệ; không còn lựa chọn "A hoặc B" (bảng quyết định §7).

### Kết luận / blocker
- T05 DONE — bằng chứng chi tiết trong `docs/tasks/T05.md`. Không có blocker cho T06 (deps T03 + T05 đều DONE).

## Lượt 2026-10-19 — Task T06: Identity đăng ký/đăng nhập (DONE)

### Môi trường
- Docker daemon tắt đầu lượt → bật Docker Desktop (`C:\Program Files\Docker\Docker\Docker Desktop.exe`), daemon UP sau ~10s; `ridehailing-postgres` + `ridehailing-rabbitmq` healthy.
- Phát hiện code T06 một phần từ phiên trước (chưa compile, chưa ghi handoff): migration V2, User/Role/UserRepository, JwtProperties, JwtService dùng API Spring Security 6. Hoàn thành phần còn thiếu trong lượt này.

### Việc đã làm
- `identity-service/pom.xml`: + data-jpa, validation, nimbus-jose-jwt 10.10 (pin root), spring-security-crypto; test: starter-test + Testcontainers postgresql/junit-jupiter 1.21.4 (Boot 4 BOM không quản lý Testcontainers).
- Rewrite `JwtService` sang Nimbus JOSE JWT 10.x trực tiếp — Spring Security 7.1 đã bỏ `Jwt.builder()`/NimbusJwtEncoder API cũ; Nimbus 10 đổi package (`com.nimbusds.jose.JWSObject`, `RSASSAVerifier`) — kiểm chứng bằng javap trên jar thật.
- Mới: `AuthenticatedUser`, DTOs (validation theo contract §3.1), `UserService` (BCrypt, EMAIL_TAKEN/INVALID_CREDENTIALS), `JwtAuthFilter` (/me), `ApiError` + `GlobalExceptionHandler` ({code,message,correlationId,details}), `AuthController`, `SecurityBeans` (BCryptPasswordEncoder).
- `scripts/generate-dev-keys.sh` (openssl RSA-2048 → keys/, idempotent, --force); `.env.example` + JWT paths/expiry + `SEED_PASSWORD`; application.yml + jwt env + ddl-auto=none.
- Integration test 6 cases trên HTTP thật + PostgreSQL Testcontainers (`postgres:17-alpine`): flow register→login→/me; duplicate email 409; wrong credentials 401 (cùng message, không lộ trường); no plaintext in DB ($2a$10$, SQL check); /me missing/tampered/garbage token 401; role ADMIN 400. Ghi chú: Boot 4 bỏ TestRestTemplate/@AutoConfigureMockMvc → `java.net.http.HttpClient` + port cố định.

### Bằng chứng chính (chi tiết trong docs/tasks/T06.md)
- `./mvnw -pl identity-service surefire:test` → **Tests run: 6, Failures: 0, Errors: 0**.
- Live trên compose Postgres (:8081): register PASSENGER/DRIVER 201; login 200 + JWT RS256 claims đúng contract §2 (sub/email/role/iss=ridehailing-identity/aud[5]/iat/exp); /me 200; duplicate 409 EMAIL_TAKEN; wrong password & unknown email cùng 401 INVALID_CREDENTIALS; role ADMIN 400 INVALID_INPUT; /me không token 401 UNAUTHORIZED.
- psql thật: `password_hash` = `$2a$10$...` (len 60); `count(*) WHERE password_hash LIKE '%secret123%'` = 0 → không plaintext.

### Kết luận / blocker
- T06 DONE. Không có blocker cho T07 (security/log tối thiểu) và T08 (frontend skeleton — deps T05+T06 đều DONE).

## Lượt 2026-10-19 — Đợt sửa nền tảng T01–T06 theo brief review (F01–F07) (DONE)

### Nguồn việc
`docs/T01-T06_REVIEW_AND_FIX_BRIEF.md` (review 19/10/2026): 7 mục F01–F07. Phạm vi: chỉ sửa lỗi nền tảng + mâu thuẫn contract; không triển khai T07/T08 hay business handler; không xóa dữ liệu/volume hiện có.

### Việc đã làm (theo thứ tự brief §5)
- **F05** — `AuthFlowIntegrationTest`: test tamper signature đổi sang decode byte chữ ký, lật 1 bit của byte đầu, encode base64url không padding + assert byte thực sự đã đổi trước khi gọi endpoint.
- **F01** — `JwtService.authenticate`: validate `exp` (bắt buộc, chưa hết hạn) và `nbf` (nếu có, đã hiệu lực); clock skew chốt = 0s; claims thiếu/sai kiểu (`aud` không phải mảng, `sub`, `role`, `email`) → 401, không còn ClassCastException → 500. `loadPrivateKey/loadPublicKey` public để T07 tái sử dụng (private key không rời identity-service).
- **F02** — `infra/rabbitmq/declare_topology.es` + `declare-topology.sh`: thêm binding `ride.events` → `trip.inbox` với routing key `payment.#`; cập nhật comment/bảng topology trong `docs/events.md`, README.
- **F03** — `GlobalExceptionHandler`: handler `DataIntegrityViolationException` map vi phạm unique key `users_email_key` → 409 `EMAIL_TAKEN`; constraint khác giữ 500 để phân biệt. Unique constraint DB là cơ chế bảo đảm cuối cùng (không dùng lock RAM).
- **F04** — `RegisterRequest.role` + `@NotNull`; `UserService`: password enforce ≤ 72 byte UTF-8 (register → 400 `INVALID_INPUT`, login → 401 `INVALID_CREDENTIALS` cùng message, không lộ email tồn tại); fullName validate 2–100 ký tự sau trim; mới `InvalidInputException`. Cập nhật contract `api-contracts.md` §3.1.
- **F06** — Chốt mâu thuẫn trong docs: replay reserve trả status gốc 201 (sửa api-contracts §4.2); NO_DRIVER chỉ terminal sau khi release/reconcile xong + reconcile timeout bằng reservationId ổn định, late-reserve nhận TOMBSTONED_RESERVATION; phase matching lại = `WAITING_MATCH` cho đến khi có candidate đúng attempt (state-machines.md).
- **F07** — README runbook đầy đủ (JDK 21/Docker/Bash/OpenSSL, `.env`, sinh keys, build/test, khởi động service; phân biệt `.env` của Compose vs env tiến trình Java); handoff sửa đề xuất T07 chỉ reuse pattern verify đã kiểm chứng; nhật ký chi tiết `docs/tasks/fix-2026-10-19.md`. Trạng thái backlog không đổi (T01–T06 vẫn DONE, đây là đợt sửa nền tảng).

### Lệnh đã chạy và kết quả thật
| Lệnh | Kết quả |
|---|---|
| `docker ps` (sau bật Docker Desktop) | daemon UP; `ridehailing-postgres`, `ridehailing-rabbitmq` healthy |
| `bash infra/rabbitmq/declare-topology.sh` (chạy lại, idempotent) | `T04: topology declared over AMQP (host=rabbitmq).` — không xóa queue/volume |
| Management API `GET /api/queues/%2F/trip.inbox/bindings` | bindings `matching.#` + `payment.#` (+ DLQ rk=`trip.inbox`) xác nhận trên broker thật |
| Publish `payment.succeeded`, `payment.failed`, `matching.proposed` vào `ride.events` (management API, payload base64, delivery_mode 2) | cả 3 `{"routed":true}`; consume từ `trip.inbox` (`ack_requeue_false`) thấy đúng 3 routing key + payload nguyên vẹn; message thử đã dọn — queue về 0 |
| `./mvnw -B clean verify` (root, JAVA_HOME JDK 21) | **BUILD SUCCESS** — identity-service: **Tests run: 12, Failures: 0, Errors: 0, Skipped: 0** (6 test cũ + 6 regression mới F01/F03/F04/F05); 5 module build pass |

### Phần chưa kiểm chứng / giới hạn
- Chưa chạy lại restart/persistence hạ tầng trong đợt này (bằng chứng cũ ở T03/T04 vẫn giữ; brief không yêu cầu làm lại bằng chứng cũ).
- Saga/reservation handler chưa tồn tại — F06 nghiệm thu bằng đối chiếu transition/contract, **không** phải pass test Saga runtime.
- Workspace chưa có `.git` → chưa kiểm tra được lịch sử đóng góp/tracked secrets (ghi đúng giới hạn này; không tạo lịch sử thay người dùng).

### Kết luận / blocker
- F01–F07 DONE theo brief. Không có blocker cho T07/T08.

## Lượt 2026-10-19 — Sửa bổ sung F01/F04/F07 sau review lần hai

- JWT: payload không phải JSON object trả 401; audience lẫn số/null bị từ chối; `exp <= now` hết hạn ngay với clock skew 0s.
- fullName: bỏ kiểm tra độ dài trên DTO thô; tiếp tục kiểm tra 2–100 ký tự sau trim trong service. Tên đúng 100 ký tự sau trim được lưu đúng.
- README: cấu hình cả JAVA_HOME và PATH trên PowerShell/Git Bash, xác nhận Java 21; sửa ví dụ nhập DB password.
- Bổ sung 4 regression tests: chạy trước khi sửa code cho **4 failures đúng hành vi sai**, sau đó `mvnw.cmd -B clean verify` → **BUILD SUCCESS**, Identity **16 tests, 0 failures, 0 errors, 0 skipped**; 5 service build thành công.
- Java theo README trên PowerShell và Git Bash đều là Temurin 21.0.12.1. Chạy jar Identity bằng Java được chọn từ PATH trên port thử 18098 → health **200, UP**; đã dừng tiến trình thử.
- Không thay đổi topology/Compose; chưa chạy lại restart/persistence hay Saga runtime. Không triển khai T07/T08. Workspace vẫn chưa có `.git`.
- Nhật ký và lệnh chi tiết: [fix-followup-2026-10-19.md](tasks/fix-followup-2026-10-19.md). Các kết quả 12 test ở mục trước là lịch sử đợt sửa đầu.

## Lượt 2026-10-19 — Task T07: Security và log tối thiểu (DONE)

### Môi trường
- Docker Desktop bật lại đầu lượt (`Start-Process`), daemon UP sau ~5s; `ridehailing-postgres` + `ridehailing-rabbitmq` healthy. Chỉ cần cho Testcontainers của identity — 4 service còn lại chạy test không Docker (flyway/jdbc autoconfig excluded qua test properties, port cố định 18098–18101).

### Việc đã làm
- **driver-service**: `JwtVerifier` (public key RS256, skew 0s, iss/aud/sub/role theo contract §2), `ApiSecurityFilter` (`/api/drivers/**` → DRIVER; `/internal/**` → `X-Internal-Token` constant-time vs env `INTERNAL_API_TOKEN`), `CorrelationIdFilter` (@Order(1), MDC + log INFO), `GlobalExceptionHandler` (404 JSON cho `NoResourceFoundException` — Spring Framework 7). Class security nhỏ nhân bản theo service, không module chung.
- **trip-service**: cùng pattern; role rules theo subpath: quote/create → PASSENGER, accept/reject/start/complete → DRIVER, path khác đã xác thực bất kỳ. Không internal endpoint (cả 6 thuộc driver-service §4.2).
- **matching/payment-service**: không security filter (contract §3/§4 — không API surface); chỉ `CorrelationIdFilter` + `GlobalExceptionHandler`.
- **identity-service**: thêm `CorrelationIdFilter` mới; `JwtAuthFilter.correlationId()` delegate về nó → khớp contract §5 (UUID hợp lệ hoặc server-generated).
- `.env.example`: + `INTERNAL_API_TOKEN=internal_dev_2026`; yml driver/trip đọc env `JWT_PUBLIC_KEY_PATH` (+ `INTERNAL_API_TOKEN` cho driver).

### Bằng chứng chính (chi tiết trong docs/tasks/T07.md)
| Lệnh | Kết quả |
|---|---|
| `./mvnw -B clean verify` (root, JDK 21) | **BUILD SUCCESS** — surefire XML: identity **16**, driver **11**, trip **8**, matching **3**, payment **3** = **41 tests, 0 failures/errors/skipped** |
| Probe driver/trip | expired/tampered/wrong-aud/missing token → 401 `UNAUTHORIZED`; role sai → 403 `FORBIDDEN`; internal credential thiếu/sai → 401 `INVALID_INTERNAL_CREDENTIAL`, đúng → qua filter (404 JSON, chưa có controller — T10); correlation echo + server-generated UUID; health 200 UP không auth |

### Kết luận / blocker
- T07 DONE. Không có blocker cho T08 (frontend skeleton — deps T05+T06 đều DONE). Chạy local driver/trip cần env tiến trình `JWT_PUBLIC_KEY_PATH` (+ `INTERNAL_API_TOKEN` cho driver) — không nằm trong Compose `.env`.

## Lượt 2026-10-19 — Task T08: Frontend skeleton (DONE)

### Môi trường
- Node v24.18.0 / npm 11.16.0; Docker Desktop chạy, postgres/rabbitmq healthy; port 8080/8081/8082 trống.

### Việc đã làm
- `web/`: Vite + React 18 + TypeScript strict + Ant Design 5 — login/register (form validation khớp contract §3.1), route theo role (`Guarded` + `routeForRole`), API client fetch wrapper (Bearer, X-Correlation-ID UUID, error {code,message,correlationId}), placeholder home passenger/driver; token trong sessionStorage, không console.log.
- `deploy/nginx/nginx.conf`: serve build + SPA fallback; proxy `/api/auth|drivers|trips|notifications` theo contract §3 qua `host.docker.internal`; rate limit login 5r/s, payload 1m, timeout connect 1s/read 3s (spec §11); access log không chứa header.
- `deploy/compose.yaml`: + service nginx (`nginx:1.27-alpine` pin, port 8080).

### Bằng chứng chính (chi tiết trong docs/tasks/T08.md)
| Lệnh | Kết quả |
|---|---|
| `npm install` / `npm run build` / `npm test` | install ~44s; **BUILD SUCCESS** → dist/; vitest **2 tests passed** (routeForRole) |
| `docker compose up -d nginx` + chạy identity/driver nền (Java 21, env từ .env) | nginx Up; health UP :8081/:8082 sau ~4s |
| curl qua Nginx :8080 | register **201**; login **200** + accessToken JWT RS256; /me Bearer **200** {role:DRIVER}; driver endpoint không token → **401** JSON + correlationId echo; GET //passenger → index.html (SPA fallback) |
| `docker logs ridehailing-nginx` | access log chỉ method/path/status — **không có Authorization/token** |

### Kết luận / blocker
- T08 DONE. Gate nền tảng T01–T08 đã đủ DONE theo spec §20.3. Không có blocker cho T09 (driver profile và vị trí — deps T03, T05, T07 đều DONE). Trang http://localhost:8080 mở trong app browser để người dùng xác nhận trực quan login + route role.

## Lượt 2026-10-19 — Đợt sửa T07–T08 theo review R01–R07 (DONE)

### Nguồn việc
`docs/T07-T08_REVIEW.md`: 7 phát hiện R01–R07. Phạm vi: chỉ sửa các lỗi này; không triển khai task nghiệp vụ mới.

### Việc đã làm
- **R01** — `deploy/nginx/nginx.conf`: 4 location API đổi sang dạng không slash cuối ở cả hai vế (`location /api/trips { proxy_pass http://host.docker.internal:8083/api/trips; }`...) → URL chính xác + subpath được proxy nguyên request, hết 301.
- **R02** — `trip-service/.../ApiSecurityFilter.java`: bảo vệ thêm `uri.equals("/api/notifications")`; +3 probe test URL chính xác trong `SecurityProbeTest` (trip: 8 → 14 tests).
- **R03** — `src/web/src/api/client.ts` xóa sessionStorage khi request đã xác nhận nhận 401; PassengerHome/DriverHome chỉ hiện "Đăng nhập thành công" khi `/me` trả user thật, redirect `/login` khi `!loading && !user`; bỏ fallback session cache cũ.
- **R04** — Nguyên nhân gốc: Boot 4 đổi package autoconfiguration → tên exclusion cũ bị lặng lẽ bỏ qua. Sửa 4 test file security (exclude đúng class Boot 4 + `app.flyway.enabled=false`) và 5 `FlywayConfig` thêm `@ConditionalOnProperty(app.flyway.enabled, matchIfMissing=true)` — production không đổi.
- **R05** — `JsonLogLayout` + `logback-spring.xml` mỗi service (không dependency mới); `CorrelationIdFilter` ×5 set MDC eventType/result/durationMs; field JSON theo spec §11 (timestamp UTC, instanceId từ env `INSTANCE_ID` hoặc UUID per JVM...); +`JsonLogLayoutTest` ×5.
- **R06** — Mới `src/web/src/auth/validation.ts` (password ≤ 72 byte UTF-8 bằng pure function; fullName 2–100 sau trim) + validator antd custom trong RegisterPage + `validation.test.ts`. Sửa giá trị test: `ạ` (U+1AB1) = **3 byte** (handoff trước ghi 2B — sai).
- **R07** — nginx `client_max_body_size 64k` + `error_page 413 /errors/payload-too-large.json`; static JSON error page trong `web/public/errors/` (Vite copy vào dist).

### Lệnh đã chạy và kết quả thật
| Lệnh | Kết quả |
|---|---|
| Backend: security tests với **Postgres DỪNG** | driver/matching/payment/trip pass — test không còn phụ thuộc DB dev (R04) |
| `./mvnw.cmd -B clean verify` (postgres healthy, JDK 21) | **BUILD SUCCESS**, đủ 6 module; trip 14 tests (gồm 3 probe R02 mới), JsonLogLayoutTest ×5 pass |
| `cd web && npm test` | **13/13 pass** (routeForRole 2 + validation 11) |
| `cd web && npm run build` | BUILD SUCCESS; `dist/errors/payload-too-large.json` tồn tại |
| Probe live qua nginx :8080 (trip-service chạy tạm :8083, dừng sau probe) | GET/POST `/api/trips`, GET `/api/notifications` không token → **401 application/json** + correlationId; **không 301** |
| POST `/api/auth/login` body 70.067B qua :8080 | **413 application/json** `PAYLOAD_TOO_LARGE` (R07) |
| Log trip-service live | dòng JSON spec §11 đầy đủ, correlationId khớp response 401 (trích dẫn trong báo cáo fix) |

### Giới hạn / chưa kiểm chứng
- Chưa E2E browser cho R03 (source + build + test component; review cũng ghi giới hạn tương tự).
- 413 tầng nginx không có correlationId (giới hạn proxy, đã nêu trong báo cáo).
- Tiến trình demo identity :8081 / driver :8082 từ lượt T08 đã dừng để chạy `clean verify`; chưa khởi động lại — người dùng muốn demo tại http://localhost:8080 thì chạy lại theo runbook README.

### Kết luận
- R01–R07 DONE. Chi tiết + bằng chứng: [T07-T08_REVIEW_FIXES.md](T07-T08_REVIEW_FIXES.md). Trạng thái backlog không đổi (đợt sửa, không phải task mới); T09 vẫn là task kế tiếp đề xuất.

## Lượt 2026-10-20 — Task T09: Driver profile và vị trí (DONE)

### Phạm vi
Spec §20.4 (owner B): driver/vehicle migrations; profile, availability, location, heartbeat; internal candidates API lọc heartbeat + khoảng cách. Chưa reserve/matching orchestration (T10+).

### Việc đã làm
- **Migrations V2** (`src/driver-service/src/main/resources/db/migration/V2__drivers.sql`): bảng `vehicles` (plate unique) + `drivers` (user_id unique, status CHECK 4 state theo state machine §4, lat/lng, last_seen_at, current_reservation_id/current_trip_id khai báo sẵn cho T10/T18) + index `(status, last_seen_at)`.
- **Nghiệp vụ** (`DriverService`): createProfile idempotent theo userId (@Transactional; plate trùng → 409 `PLATE_TAKEN`, rollback), setAvailability CAS (chỉ từ OFFLINE/AVAILABLE; RESERVED/BUSY → 409 `STATE_CONFLICT` details `{state, tripId}` với mọi status yêu cầu), updateLocation (mọi state, cập nhật heartbeat), findCandidates (Haversine R=6.371.008.8 m, lọc AVAILABLE + heartbeat mới ≤15s + radius, sort deterministic distanceM tăng → tie-break driverId lexicographic, limit clamp ≤5).
- **API**: `DriverController` (POST profile, PUT availability, PUT location — userId từ JWT) + `InternalDriverController` (GET `/internal/drivers/candidates?lat&lng&radiusM=5000&limit=5`). DTOs + 4 exception class + handlers trong GlobalExceptionHandler (400/409/404 JSON theo contract §1.2).
- **Contract**: cập nhật `docs/api-contracts.md` §1.2/§3.2/§4.2 — thêm `PLATE_TAKEN`, rule 404 khi chưa có profile, 409 mọi status khi RESERVED/BUSY, limit/radius rules.
- **Tests**: mới `DriverProfileIntegrationTest` (14 tests, Testcontainers postgres:17-alpine, port 18099, HTTP thật + JWT test key); sửa `SecurityProbeTest` sang H2 in-memory vì bean T09 cần JdbcTemplate (probe không chạm DB, không cần Docker).

### Lỗi gặp và đã sửa (chạy thật để xác nhận)
1. javac `incompatible bounds` ở `jdbc.query(..., this::mapX)`: Spring 7 thêm overload `query(String, Object[], ResultSetExtractor<T>)`; method reference `(ResultSet)` chỉ khớp nó → mapper đổi signature thành `(ResultSet, int)` đúng RowMapper (verify bằng repro standalone trên jar spring-jdbc 7.0.9).
2. Test compile: `JWSObject` cần header + `sign(SIGNER)`; `RETURNING id` phải dùng `queryForObject` (+ helper toUuid cho cột uuid).
3. Runtime 500 PUT availability: thứ tự args CAS sai (`updated_at` nhận String) → PG type error; sửa thứ tự `(newStatus, ts, ts, driverId)` (bắt lỗi bằng repro pgjdbc standalone — psql literal không hiện vì binding JDBC khác).
4. SecurityProbeTest context fail do thiếu JdbcTemplate bean → H2 in-memory test dependency.

### Lệnh đã chạy và kết quả thật
| Lệnh | Kết quả |
|---|---|
| `./mvnw.cmd -B -pl driver-service clean verify` | BUILD SUCCESS — **28/28** (14 integration + 11 probe + 3 log) |
| `./mvnw.cmd -B clean verify` (root) | BUILD SUCCESS đủ 6 module — tổng **79 tests, 0 failures** (identity 19, driver 28, trip 14, matching 6, payment 6) |
| Live probe: start identity :8081 + driver :8082 (env `JWT_PUBLIC_KEY_PATH`, `INTERNAL_API_TOKEN`, DB creds `.env`), dừng sau probe | register DRIVER **201**; login **200** + JWT RS256; POST profile **201** {status OFFLINE}; PUT availability AVAILABLE **200** + lastSeenAt ISO-Z; PUT location **200**; GET candidates (X-Internal-Token) **200** `[{driverId, distanceM:0}]`; lat=999 → **400** INVALID_INPUT; profile idempotent qua nginx :8080 **200**; OFFLINE qua nginx **200** |

### Kết luận / blocker
- T09 DONE — bằng chứng chi tiết trong [T09.md](tasks/T09.md). Không có blocker cho T10 (Giữ chỗ nguyên tử — dep T09 DONE). Tiến trình Java probe đã dừng; nginx :8080 vẫn up.

## Lượt 2026-10-20 — Task T10: Giữ chỗ nguyên tử (atomic reserve) (DONE)

### Phạm vi
Spec §20.4 (owner B): Reservation và processed_commands migrations; reserve/get nội bộ; CAS driver AVAILABLE; command ID/hash và terminal tombstone. Chưa confirm/release/finish (T11), chưa matching orchestration.

### Môi trường
- Docker Desktop tắt đầu lượt → bật lại bằng `Start-Process`, daemon UP ~10s; postgres/rabbitmq healthy.
- JAVA_HOME JDK 21 + PATH cấu hình theo README trong shell.

### Việc đã làm
- **Migrations V3** (`src/driver-service/src/main/resources/db/migration/V3__reservations.sql`): `reservations` (id PK do Trip cấp, state CHECK 4 state, terminal_reason, expires_at, last_command_version, pickup/destination/distance_m/fare_vnd — contract decision #4 cho T17 offer) + unique index `(trip_id, attempt)` (invariant 2); `processed_commands` (command_id PK, request_hash, stored_response JSONB).
- **Nghiệp vụ** (`ReservationService`): `reserve()` trong 1 transaction @Transactional — thứ tự: (1) dedup commandId (replay trả stored response / conflict → 409 `COMMAND_CONFLICT`) → (2) tombstone (row terminal → 409 `TOMBSTONED_RESERVATION`) → (3) version check (`commandVersion ≤ last_command_version` → 409 `STALE_VERSION`; `<1` → 400) → (4) CAS driver AVAILABLE + heartbeat cutoff; affected ≠ 1 → rollback toàn bộ. `requestHash()` = SHA-256 payload canonical trừ commandId/commandVersion.
- **API**: `InternalReservationController` — PUT `/internal/reservations/{reservationId}` (RESERVE, 201/replay) + GET (200/404); security filter T07 enforce `X-Internal-Token`. DTO `ReserveRequest`; 4 exception class mới + handlers trong GlobalExceptionHandler.
- **Contract**: cập nhật `docs/api-contracts.md` §4.1 — "chốt T10": định nghĩa request_hash, stored_response JSONB `{status,body}`, thứ tự kiểm tra reserve (replay trước tombstone).
- **Tests**: mới `ReserveIntegrationTest` (13 tests, Testcontainers PG, port 18100) gồm concurrency barrier test (2 PUT đồng thời → đúng 1×201 + 1×409, DB đúng 1 reservation hoạt động) và tombstone simulation (insert row RELEASED trực tiếp — creation thuộc T11); sửa `SecurityProbeTest` probe path sang sub-path không map.

### Lỗi gặp và đã sửa (chạy thật để xác nhận)
1. **500 insert stored_response**: pgjdbc bind String → varchar, PG từ chối implicit cast → SQL explicit `?::jsonb`.
2. **SecurityProbeTest vỡ khi T10 land**: probe "credential đúng" giả định không có controller (mong 404); PUT body rỗng giờ cho 400 → đổi sang `/internal/reservations/{uuid}/no-such-subpath` → 404.
3. **Test context fail** với `jwt.public-key-path` file giả: bean JwtVerifier vẫn instantiate → sinh RSA key tạm trong static block (pattern T09).

### Lệnh đã chạy và kết quả thật
| Lệnh | Kết quả |
|---|---|
| `./mvnw.cmd -B -pl driver-service clean verify` | BUILD SUCCESS — **41/41** (ReserveIntegrationTest 13, DriverProfileIntegrationTest 14, SecurityProbeTest 11, JsonLogLayoutTest 3) |
| `./mvnw.cmd -B clean verify` (root) | BUILD SUCCESS đủ 6 module — tổng **86 tests, 0 failures** (identity 19, driver 41, trip 14, matching 6, payment 6) |
| Live probe (`t10_live_probe.sh`: identity :8081 + driver :8082 với `INTERNAL_API_TOKEN`; unique plate/email per run; dừng sau probe) | register DRIVER **201**; login **200** JWT RS256; profile/AVAILABLE/location OK; PUT reserve → **201** `{reservationId, state:RESERVED, version:1}`; retry cùng command → replay **201** body giống hệt; cùng commandId khác fareVnd → **409 COMMAND_CONFLICT**; GET reservation → **200** đủ trường; reserve tái dùng trip+attempt → **409 DRIVER_NOT_AVAILABLE**; không token → **401** |

### Kết luận / blocker
- T10 DONE — bằng chứng chi tiết trong [T10.md](tasks/T10.md). Không có blocker cho T11 (Confirm/release/finish — dep T10 DONE). Tiến trình Java probe đã dừng; nginx :8080 vẫn up.

## Lượt 2026-10-20 — Task T12: Quote và tạo trip (DONE)

### Phạm vi
Spec §20.4 (owner A): Trip migration, Haversine/fare, create/get/list và idempotency HTTP; ghi ý định yêu cầu matching trong DB/outbox pending. Chưa ghép tài xế (T16+), chưa cancel/accept/reject/start/complete (T18–T22).

### Môi trường
- Docker daemon đã up đầu lượt (nginx/postgres/rabbitmq healthy); không cần bật lại.
- JAVA_HOME JDK 21 + PATH cấu hình trong shell (lần đầu quên → build fail "No compiler... JRE"; tìm JDK tại `~/dev-tools/jdk-21.0.12.1+1`).

### Việc đã làm
- **Migrations V2** (`trip-service/.../db/migration/V2__trips.sql`): `trips` (state CHECK 8 states, matching_attempt, driver_id nullable, payment_status/payment_attempt khai báo sẵn cho T16–T24), `outbox` (id = eventId ổn định; status PENDING/SENT — publisher worker thuộc T13), `trip_events` (enum 13 values §3.5), `idempotency_keys` (key PK, request_hash, stored_response JSONB).
- **Nghiệp vụ**: `Haversine` (nhân bản T09 driver-service), `TripService` — quote (fare server-side: `FARE_BASE_VND + ceil(distanceM/1000)*FARE_PER_KM_VND`, env mới `FARE_BASE_VND=12000`/`FARE_PER_KM_VND=5000` trong application.yml + .env.example); create @Transactional: claim idempotency bằng `ON CONFLICT (key) DO NOTHING` → replay response gốc / 409 `IDEMPOTENCY_MISMATCH`; INSERT trip SEARCHING v1 + outbox PENDING TripRequested attempt 1 (`excludedDriverIds: []`) + trip_event TRIP_CREATED v1; list phân trang §1.3 (clamp size 100, sort created_at DESC); get owner/assigned-driver không thì 404. `TripController` + 3 exception mới (InvalidInput/IdempotencyMismatch/TripNotFound) + handlers.
- **Chốt T12** (ghi trong task log): outbox schema tạo ở T12 (T13 chỉ thêm worker); saga WAITING_MATCH = T16, notifications = T25 — chưa ghi; request_hash = SHA-256 canonical chỉ tọa độ pickup/destination.
- **Tests**: `TripIntegrationTest` 11 tests mới (Testcontainers PG, port **18102**) — quote fare đúng (3309 m / 32000 VND), create 202 + Location + DB verify (trip/outbox/trip_event), replay cùng key/payload, mismatch 409, thiếu/sai key 400, tọa độ sai 400, list envelope + clamp size, detail owner/404/401, driver list rỗng, role 403.
- **Sửa phụ thuộc**: SecurityProbeTest — probe path `GET /api/trips` (mong 404) giờ chạm controller T12 → đổi sang `/api/trips/{id}/events` (T25); DataSource exclude-autoconfig → H2 in-memory + dependency h2 test (pattern driver-service T09). Test JSONB: pgjdbc `getObject()` trả PGobject → query dùng `payload::text`.

### Lệnh đã chạy và kết quả thật
| Lệnh | Kết quả |
|---|---|
| `./mvnw.cmd -B -pl trip-service clean verify` | BUILD SUCCESS — **25/25** (TripIntegrationTest 11, SecurityProbeTest 11, JsonLogLayoutTest 3) |
| `./mvnw.cmd -B clean verify` (root) | BUILD SUCCESS đủ 6 module — tổng **97 tests, 0 failures** (identity 19, driver 41, trip 25, matching 6, payment 6) |
| Live probe (`t12_live_probe.sh`: identity :8081 + trip :8083, mọi request qua nginx :8080; dừng sau probe) | register PASSENGER **201**; login **200** JWT RS256; quote **200** `{distanceM:3309,fareVnd:32000}`; create **202** + `Location` header, body `{tripId,state:SEARCHING,matchingAttempt:1,createdAt}`; replay cùng key/payload → **202** body gốc giống hệt; cùng key khác payload → **409 IDEMPOTENCY_MISMATCH**; thiếu key / sai UUID → **400 INVALID_INPUT**; GET list envelope đúng (totalElements 1); GET detail owner **200** đủ field (driverId null); passenger khác → **404 NOT_FOUND**; không token → **401 UNAUTHORIZED**; psql trip_db: outbox `TripRequested PENDING` payload attempt 1 + excludedDriverIds []; trip_events `TRIP_CREATED v1` |

### Kết luận / blocker
- T12 DONE — bằng chứng chi tiết trong [T12.md](tasks/T12.md). Unblocked mới: **T13** (Outbox publisher Trip — dep T04, T12 DONE) và **T11** vẫn sẵn sàng (dep T10 DONE). Tiến trình Java probe đã dừng; nginx :8080 vẫn up.

## Lượt 2026-10-20 — Sửa review T09/T10/T12 và hoàn tất T11

Theo yêu cầu người dùng, xử lý đủ R01–R07 rồi triển khai T11. Đã đối chiếu đặc tả DS03 §6/§8/§20.4 và PDF đề môn học §7; không triển khai task T13+.

- Trip: replay scope principal/CREATE_TRIP/key; driver_user_id tách driver_id để kiểm tra JWT; offset long; V3 backfill owner và kiểm tra identity đầy đủ. 32 tests pass; review độc lập chấp nhận.
- Driver: profile creation serialize theo user; command claim nguyên tử + operation/resource hash + version INT bounds; V4 giữ replay cũ và cho tombstone đúng nghĩa. Confirm/release/finish cùng transaction với driver fencing; worker chỉ expire RESERVED. 67 tests pass.
- Thêm regression HTTP/PostgreSQL cho concurrency, stale/changed commands, version max, tombstone, deadline khi chờ khóa driver, old release/finish, upgrade và restart application context. Hai phát hiện bổ sung từ review (deadline và defensive finish fencing) đã có test đỏ trước sửa và pass sau sửa.
- Root `mvnw.cmd -B clean verify`: **BUILD SUCCESS, 130 tests, 0 failures/errors/skipped** — Identity 19, Driver 67, Trip 32, Matching 6, Payment 6. Lượt clean verify kết thúc 23:17 ngày 20/10/2026, giờ Việt Nam.
- Web `npm test -- --run`: **13/13**; `npm run build`: thành công, còn cảnh báo chunk Vite >500 kB.
- Không dùng DB nghiệp vụ dev hoặc khởi động port demo; không có Git metadata nên không tạo commit. Backup nguồn trước sửa lưu trong TEMP. Không tuyên bố đã chạy E2E lifecycle Nginx hoặc kill/restart JVM; test restart đóng rồi mở ứng dụng với cùng DB test.

T11 DONE, R01–R07 đã xử lý. Chi tiết: [T11](tasks/T11.md), [nhật ký sửa](T09-T12_REVIEW_FIXES.md). T13/T14 vẫn TODO. Không có blocker còn lại trong phạm vi đã giao.
