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

## Lượt 2026-10-21 — FIX_LIST T01–T12: Phase 1 (C01–C03) + Phase 2 (T07 security F01/N10, N02, F02, F03, F04, Q03; nginx T08-1/T08-2)

### Phase 1 — committed (`a135fc6`, `e8762da`, `1f308ae`)
- C01: khởi tạo git repo + `.gitignore` (chặn `.env`, `keys/*.pem`, `target/`, `dist/`).
- C02: chuyển 5 services + web vào `src/`; sửa pom modules/relativePath, compose bind-mount, README/docs paths. Verify lúc đó: `mvnw clean verify` 130/0, frontend 13/13, nginx serve :8080.
- C03: ghi blocker trước T13 (F01+N10, N15/N21–N24/F31) trong handoff/backlog, trỏ `docs/FIX_LIST_T01-T12.md`.

### Phase 2 — code + kiểm chứng thật (commit sau entry này)
- **T07-1 (F01+N10):** Spring Security `SecurityFilterChain` ở cả 5 service (`config/SecurityConfig.java`, `security/JwtAuthenticationFilter.java` đặt request attribute `authenticatedUser`; JSON 401/403 handlers; driver thêm `InternalTokenFilter` constant-time cho `/internal/**`). Xóa filter cũ. Firewall mặc định chặn matrix param → 400.
- **T07-2 (N02):** `server.address: ${SERVER_ADDRESS:127.0.0.1}` trong cả 5 yml. Kiểm chứng thật: chạy driver trên host → netstat chỉ `127.0.0.1:8082 LISTENING`; từ container nginx qua `host.docker.internal` (resolve 192.168.65.254) vẫn reach được — Docker Desktop route về loopback host, dev gateway :8080 chạy với binding mặc định (`curl http://localhost:8080/api/drivers/status` → 401 JSON qua nginx). Ghi chú README §6 + comment compose/nginx.
- **T07-3 (F02):** exp/nbf so sánh bằng double; `"nbf": null` có mặt → 401. Test fractional-second trong SecurityProbeTest/AuthFlowIntegrationTest.
- **T07-4 (F03):** Boot 4 MVC dùng JsonMapper auto-config từ `JsonMapperBuilderCustomizer` (verify javap) — @Bean ObjectMapper cũ không được dùng cho request binding (thử: role=0 vẫn 201). Thay bằng bean `strictCoercionCustomizer` ở identity/driver/trip; test sai kiểu → 400 INVALID_INPUT, DB không đổi.
- **T07-5 (F04):** handler 405/415 (+ NoResourceFound/type-mismatch/missing-param ở identity) trong cả 5 GlobalExceptionHandler; test có correlationId. Mã mới METHOD_NOT_ALLOWED/UNSUPPORTED_MEDIA_TYPE chờ phase docs vào contract §1.2.
- **T07-15 (Q03):** bộ probe test đủ URL biến thể + NumericDate phần lẻ + JSON sai kiểu + assert DB không đổi.
- **T08-1 (N01) + T08-2 (N04), nginx.conf:** server-level `if ($request_uri ~* "(;|%3b)") return 400`; `limit_req` toàn `/api/auth/` (auth_limit 10r/s burst=5) + login riêng chặt hơn (login_limit 5r/s burst=5); boundary locations `location = /api/X` + `location /api/X/`. Kiểm chứng thật qua :8080: `;x`/`%3Bx` → 400; burst 20 req `/me`+`/login` → mix 502/503 (limit_req hoạt động); `/api/driversfoo`, `/api/authx` không proxy (SPA fallback — JSON 404 theo T08-5); exact/subpath proxy đúng tới driver chạy thật.
- **Verify tổng:** `./mvnw -B clean verify` → BUILD SUCCESS, **155 tests / 0 failures** (identity 22, driver 78, trip 43, matching 6, payment 6) — gồm regression test mới của phase này.

### Ghi chú kỹ thuật
- Boot 4: MVC request binding dùng JsonMapper auto-config (`JacksonAutoConfiguration` + `JsonMapperBuilderCustomizer`) — custom @Bean ObjectMapper không áp dụng cho binding (đã verify javap trên jar local).
- Docker Desktop Windows: `host.docker.internal` reach được process bind loopback (khác Linux) → dev-mode nginx proxy hoạt động với `SERVER_ADDRESS=127.0.0.1`.

## Lượt 2026-10-21 — FIX_LIST T01–T12: Phase 3 (contract T05 + mã lỗi F04)

Docs-only, theo thứ tự khuyến nghị của FIX_LIST ("chốt N15, N21–N24, F31 trước khi bắt đầu T13"):
- **T05-1 (F31):** chọn hướng (a) — bỏ "publish event hủy matching" (event không tồn tại trong catalog); trip terminal → event attempt cũ bị bỏ qua ACK+log. Cập nhật api-contracts §3.3, state-machines row SEARCHING→CANCELLED, events.md §3.2 note.
- **T05-2 (N15 contract):** publisher `mandatory=true` + ReturnsCallback; message returned → outbox giữ PENDING + `last_error`, không SENT; service declare topology khi khởi động, script chỉ phụ trợ (events.md §1/§4).
- **T05-3 (N21):** transition `SEARCHING→SEARCHING` cụ thể: NoDriverFound/hết candidate còn lượt → attempt+1, `next_retry_at = now + MATCHING_RETRY_DELAY_MS` (env mới, default 5000), outbox TripRequested attempt mới; excluded list giữ nguyên với NoDriverFound; hết attempts/deadline → NO_DRIVER.
- **T05-4 (N22):** chốt ghi `trips.driver_id`/`driver_user_id` khi DRIVER_OFFERED, xóa NULL khi reject/expire; invariant 2 = "assignment hiệu lực" từ ACCEPTED (state-machines §6, api-contracts §3.3, handoff dòng T16).
- **T05-5 (N23):** malformed/unsupported → republish sang DLQ kèm `x-dlq-reason` rồi ACK gốc; reject(requeue=false) chỉ sau hết retry transient (chỉ mang x-death) — events.md §2/§4.3/§6.
- **T05-6 (N24):** Trip phải release mọi reservationId đã cấp mà bỏ (kể cả sau 409/timeout); không tái dùng commandId; command lỗi không lưu replay — api-contracts §4.1 + state-machines §2.
- **F04 → contract:** thêm `METHOD_NOT_ALLOWED` (405) và `UNSUPPORTED_MEDIA_TYPE` (415) vào bảng mã lỗi api-contracts §1.2.

Kiểm chứng: 3 tài liệu contract nhất quán (đọc lại sau sửa); không thay đổi code/test — `clean verify` không bị ảnh hưởng (docs-only).

## Lượt 2026-10-21 — FIX_LIST T01–T12: Phase 4 (T07 logging F20/F21/N11/N12/N13)

### Việc đã làm
- **T07-6 (F20):** `JsonLogLayout.doLayout` ×5 trả về `json + CoreConstants.LINE_SEPARATOR` ở cả nhánh chính và fallback — mỗi event một dòng JSON.
- **T07-7 (F21):** field `stackTrace` = `ThrowableProxyUtil.asString(throwable)` (`ch.qos.logback.classic.spi`) kèm chuỗi cause; không đưa stack trace vào HTTP response. (Lỗi package ban đầu `classic.util`/`core.util` → sửa bằng javap trên jar logback 1.5.38.)
- **T07-8 (N11):** audit log nghiệp vụ, không ghi secret:
  - identity `UserService`: `user_registered`, `login_succeeded`/`login_failed` (không email/password trong message).
  - driver `DriverService.setAvailability`: `driver_availability_changed`; `ReservationService`: helper `audit(eventType, reservationId, tripId, detail)` set MDC `reservationId`/`tripId` + eventType + result=SUCCESS — gọi ở doReserve (**sau** CAS thành công), confirm, release (3 nhánh: tombstone / already-terminal / thực), finish, expire.
  - trip `TripService.create`: `trip_created` với MDC `tripId`, sau outbox+event insert.
- **T07-9 (N13):** `CorrelationIdFilter.resolve()` ×5 chuẩn hóa bằng `UUID.fromString(value.trim()).toString()` — attribute, MDC, header, error body và outbox dùng cùng một giá trị canonical.
- **T07-10 (N12 service):** `doFilterInternal` set `response.setHeader("X-Correlation-ID", id)` trước `chain.doFilter`; finally block re-set `eventType=http_request` trước dòng access log để business code không làm hỏng eventType của access line.

### Tests mới (capture qua OutputStreamAppender thật gắn vào logger service cụ thể)
- `JsonLogLayoutTest.appenderFramesEachEventOnItsOwnLine` ×5: 3 event → đúng 3 dòng, mỗi dòng parse JSON, không còn `}{`; `layoutIncludesFullStackTraceWithCause` ×5: cause class + `Caused by:` + frame `at ...` (T07-14/Q02).
- identity `AuthFlowIntegrationTest.businessAuditLogsAreStructuredAndSecretFree`: register/login-ok/login-fail qua HTTP, assert eventType+result, toàn bộ output không chứa PASSWORD/"@example.com"/"Bearer ".
- trip `TripIntegrationTest.correlationIdIsCanonicalizedAndReturnedInEveryResponse` (N12/N13): header `X-Correlation-ID: 1-2-3-4-5` → error body 400, response header và `outbox.correlation_id` đều = `00000001-0002-0003-0004-000000000005`; không header → UUID server-generated hợp lệ.
- trip `TripIntegrationTest.tripCreatedAuditLogIsStructuredAndSecretFree`: assert `trip_created` + MDC tripId + không secret.
- driver `ReservationLifecycleIntegrationTest.reservationLifecycleAuditLogsAreStructured`: reserve→confirm→release, assert 3 dòng audit với MDC reservationId+tripId.
- driver `DriverProfileIntegrationTest.availabilityChangeAuditLogIsStructuredAndSecretFree`: PUT availability → `driver_availability_changed` + không secret.

### Lệnh đã chạy và kết quả thật
| Lệnh | Kết quả |
|---|---|
| `./mvnw -B clean verify` (root, JDK 21) | **BUILD SUCCESS** — identity **25**, driver **82**, trip **47**, matching **8**, payment **8** = **170 tests / 0 failures/errors/skipped** |

### Ghi chú kỹ thuật
- logback 1.5.x: `Logger` không có `getContext()` — dùng `getLoggerContext()`; `ThrowableProxyUtil` nằm trong `ch.qos.logback.classic.spi` (verify javap trên jar local).
- Replay command (`execute()` early-return) không re-log business event — event đã log khi lần đầu execute.

### Trạng thái FIX_LIST
T07-6..T07-10 + T07-14 đánh dấu `[x]` kèm bằng chứng. Còn mở: T07-11 (N03 body limit), T07-12 (N08 internal token rỗng/.env), T07-13 (Q01 secret-scan toàn luồng — một phần đã phủ bởi test audit identity).

## Lượt 2026-10-21 — FIX_LIST T01–T12: Phase 5 (T09–T12 correctness/time)

### Việc đã làm
- **T09-1 (F07):** `DriverRepository.updateLocation/updateAvailabilityIfFree` gán `SET last_seen_at = GREATEST(last_seen_at, clock_timestamp()) ... RETURNING last_seen_at` và trả giá trị đó — DB tự gán thời gian sau khi giành được khóa.
- **T09-2 (F11):** Haversine clamp `a` về [0,1] trước `atan2`, ném lỗi nếu kết quả không hữu hạn (driver + trip).
- **T09-3 (F13):** CAS thất bại → đọc lại driver, lấy tripId từ `current_trip_id` hoặc từ reservation của `current_reservation_id`.
- **T09-4 (N31):** `normalizePlate()` (trim/uppercase/gộp khoảng trắng) trước khi kiểm tra và lưu; migration V5 chuẩn hóa dữ liệu cũ + unique index `uq_vehicles_plate_upper` trên `upper(plate_number)`; `isPlateUniqueViolation` khớp cả tên constraint mới lẫn cũ.
- **T09-5 (R05):** `findCandidatesInBox(lat,lng,radiusM,staleMs,limit)` — bounding box trong SQL + LIMIT rồi mới Haversine trong Java.
- **T09-6 (N32 driver):** V5 thêm `drivers.version` (BIGINT NOT NULL DEFAULT 1) + `vehicles.updated_at`; `ProcessedCommandCleanupWorker` mới xóa processed_commands cũ hơn N ngày (gated `app.flyway.enabled`).
- **T10-1 (F06):** cutoff heartbeat trong chính SQL CAS: `last_seen_at > clock_timestamp() - make_interval(secs => ?)` — PostgreSQL đánh giá lại sau khi có khóa row.
- **T10-2 (F10/N29):** kiểm tra khoảng `(now, now + OFFER_TTL_MAX]` bằng `dbNow()` trong `doReserve()`, chỉ cho reservation MỚI (replay command cũ trả response đã lưu theo contract §4.1); env mới `RESERVATION_OFFER_TTL_MAX_MS=120000`.
- **T10-3 (F12):** `isTripAttemptUniqueViolation` — SQLState 23505 trong cause chain + constraint `uq_reservations_trip_attempt`; DataIntegrityViolationException khác ném tiếp (handler → 500).
- **T10-4 (R04):** một nguồn đồng hồ duy nhất: `dbNow()`/`clock_timestamp()` cho recheck confirm, `expireReserved/findExpiredIds`, cutoff heartbeat; không còn `Instant.now()` trong các phép so sánh hạn.
- **T10-5 (R01 driver):** `canonicalDouble()` — -0.0 → "0.0" trước khi băm; quy tắc canonical chốt contract §4.1.
- **T11-1 (F08):** `expireIfUnlocked` dùng `pg_try_advisory_xact_lock` (bỏ qua item đang bị khóa) + `SET LOCAL lock_timeout 2s`; worker try/catch từng item kèm log.error.
- **T11-2 (F09):** `checkVersion` chặn `version > stored + MAX_VERSION_STEP(1000)` → 400 INVALID_INPUT; chốt contract §4.1.
- **T11-3 (N30):** release trên CONFIRMED chỉ chấp nhận reason CANCELLED, khác → 409 STATE_CONFLICT (contract §4.2 cập nhật).
- **T12-1 (F14):** một timestamp duy nhất `Instant.now().truncatedTo(SECONDS)` dùng cho response, bản lưu replay, `trips.created_at` và `outbox.occurred_at`.
- **T12-2 (N33):** migration V4 thêm cột envelope outbox (`aggregate_version/schema_version/producer/routing_key/attempts/next_attempt_at/last_error`); `insertPending(..., aggregateVersion=1, routingKey="trip.requested")`.
- **T12-3 (N32 trip):** V4 thêm `trips.reservation_id` + unique index `uq_trip_events_trip_type_version`; giữ BIGSERIAL cho `trip_events.id` — lệch đặc tả có chủ đích (id nội bộ history); `IdempotencyKeyCleanupWorker` mới xóa key cũ > 24 h.
- **T12-4 (R01 trip):** `canonicalDouble()` trong `TripService.requestHash`; chốt contract §3.3.
- **T12-5 (R02):** `list()` đặt `@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)` — count và content cùng snapshot.
- **T12-7 (N20):** 9 test classes chuyển sang `RANDOM_PORT` + `@LocalServerPort`, bỏ `server.port=...` cố định.

### Tests mới / sửa
- Mới: `HeartbeatClockIntegrationTest` ×2 (T09-1 giữ row lock 750 ms → lastSeenAt fresh theo DB clock + vẫn trong candidates; T10-1 giữ 1200 ms → reserve 409 DRIVER_NOT_AVAILABLE, không row), `HaversineTest` ×2 module ×4 tests (đối cực = round(π·6371088.8) = 20_015_114 m; NaN/Inf → IllegalArgumentException), `DriverProfileIntegrationTest.offlineConflictResolvesTripIdFromReservationWhenCurrentTripIsNull` + `plateNormalizationMakesCaseAndWhitespaceDuplicatesConflict`, `reserveRejectsExpiresOutsideOfferTtlWindow` (quá khứ/+1h/9999-12-31 → 400), `confirmWithHugeVersionIsRejectedButNormalFlowWorks`, `offerExpiredReleaseOnConfirmedIsStateConflict`, `expiryWorkerSkipsLockedReservationButExpiresOthers` + `ReservationExpiryWorkerTest` (cô lập lỗi Mockito), `TripIntegrationTest`: `createdAtIsStableAcrossGetAndReplay`, `outboxRowCarriesAggregateVersionAndRoutingKey`, `negativeZeroLatitudeReplaysInsteadOfMismatch`, `listUnderRepeatableReadStaysConsistentWithConcurrentCreates`; unit `ReservationServiceHashTest` ×3 (T10-5).
- Sửa: `maxIntCommandVersionIsStoredAndReadWithoutTruncation` → viết lại thành `reserveWithHugeCommandVersionIsRejected` (INT_MAX → 400, không row); test nâng cấp v3/v2 assert version hiện tại "5"/"4" (driver V1–V5, trip V1–V4).

### Lệnh đã chạy và kết quả thật
| Lệnh | Kết quả |
|---|---|
| `./mvnw -B clean verify` (root, JDK 21) | **BUILD SUCCESS** — identity **25**, driver **98**, trip **55**, matching **8**, payment **8** = **194 tests / 0 failures/errors/skipped** |

### Ghi chú kỹ thuật
- Spring Framework 7: `@Transactional(isolation=...)` nhận enum `org.springframework.transaction.annotation.Isolation` (không phải String, không nằm trong `org.springframework.transaction`) — verify trên jar spring-tx 7.0.9.
- Boot 4: `@LocalServerPort` ở package `org.springframework.boot.test.web.server` (verify trên jar spring-boot-test 4.1.1).
- `Instant.parse("300000-01-01T00:00:00Z")` fail trên JDK này → test far-future dùng `9999-12-31T23:59:59Z`.
- Driver migrations V1–V5, trip V1–V4; test upgrade chạy full-migrate trên schema legacy (V4/V5 an toàn với dữ liệu cũ — cột mới có default/nullable).

### Trạng thái FIX_LIST
T09-1..6, T10-1..5, T11-1..3, T12-1..5, T12-7 đánh dấu `[x]` kèm bằng chứng. **T12-6 (N28) vẫn mở** — trì hoãn sang phase 8 sau khi chốt T05-10.

## Lượt 2026-10-22 — FIX_LIST T01–T12: Phase 6 (infra/scripts)

### Việc đã làm
- **T03-1 (F27):** `infra/postgres/init/01-create-databases.sh` viết lại — password truyền qua `psql -v VAR=...` + `:'VAR'`, không còn nội suy chuỗi mật khẩu vào SQL literal. Kiểm chứng: container `postgres:17.11-alpine` dùng 1 lần, 5 password chứa `'`, `"`, `$`, khoảng trắng → "T03: 5 DB + 5 users created", cả 5 login service trả về 1.
- **T03-2 (N09):** bỏ dev default `spring.datasource.password` khỏi cả 5 `application.yml`; thêm `RequiredDbPasswordValidator.java` (`@Component` implements `BeanFactoryPostProcessor`) vào package config của từng service — fail-fast trước khi tạo DataSource: kiểm tra env `<VAR>_DB_PASSWORD`, nếu không có thì giá trị resolved `spring.datasource.password` (test đặt trực tiếp nên pass), thiếu → `IllegalStateException("Missing required environment variable 'X_DB_PASSWORD'...")`. Kiểm chứng: `env -u IDENTITY_DB_PASSWORD java -jar identity-service-0.1.0.jar` → exit 1, log nêu tên biến, không in giá trị; full `mvnw clean verify` xanh.
- **T03-3 (N18):** healthcheck postgres = `pg_isready -q -h 127.0.0.1 -U $${POSTGRES_USER} && PGPASSWORD=$$PAYMENT_DB_PASSWORD psql ... -d payment_db -c 'select 1'`. Kiểm chứng: fresh `down -v` → `up -d` healthy, init log "T03: 5 DB + 5 users created".
- **T03-4 (F32):** pin tag mọi nơi: `postgres:17.11-alpine`, `rabbitmq:3.13.7-management-alpine` (khớp framing records sao chép từ 3.13.7; xác minh bằng `docker exec ridehailing-rabbitmq rabbitmqctl version` = 3.13.7), `nginx:1.27.5-alpine`. Cập nhật compose.yaml (kể cả service topo-declare mới) + cả 7 class Testcontainers.
- **T04-1 (F28):** `infra/rabbitmq/declare_topology.es` — không parse AMQP URI; build record `#amqp_params_network{username, password, host}` trực tiếp từ env (+ định nghĩa `-record(amqp_params_network,...)` sao chép nguyên văn từ `amqp_client-3.13.7/include/amqp_client.hrl`; helper `env_binary(Name)` = `list_to_binary`). Kiểm chứng: broker với user `u#x`, password `p@ss:w/ord#` → "T04: topology declared over AMQP", 6 durable queues được liệt kê.
- **T04-2 (F29):** `infra/rabbitmq/declare-topology.sh` — parser `load_dotenv()` an toàn (chỉ KEY=VALUE, không execute shell; bỏ CRLF, skip comment/trống, trim khoảng trắng key, strip 1 cặp quote bao quanh). Kiểm chứng với `.env` tạm: giá trị có khoảng trắng không quote, `#`, giá trị trong quote, dòng CRLF.
- **T04-3 (N15 topology):** service compose one-shot mới `topo-declare` (`rabbitmq:3.13.7-management-alpine`, `command: escript /topo/declare_topology.es`, `depends_on: rabbitmq condition: service_healthy`, `restart: "no"`, env RABBITMQ_USER/PASS + **RABBITMQ_HOST: rabbitmq**, volume `../infra/rabbitmq:/topo:ro`). README cập nhật (auto-declare mỗi lần `up -d`; script thủ công = fallback; `--force-recreate topo-declare` nếu broker data bị xóa khi đang chạy). Kiểm chứng end-to-end sau khi sửa 2 bug thật tìm thấy lúc verify (xem ghi chú kỹ thuật).
- **F25/C05:** `.gitattributes` (`* text=auto eol=lf`, `*.cmd`/`*.bat` crlf); chuẩn hóa 163 file CRLF → LF (chỉ `mvnw.cmd` giữ CRLF — grep verify); `"engines": {"node": "24.x"}` trong `src/web/package.json`; `.nvmrc` = `24.18.0`. Kiểm chứng: không còn CRLF ngoài mvnw.cmd; `generate-dev-keys.sh` chạy end-to-end trên Linux qua `docker run ubuntu:24.04` (openssl cài trong container) → "Keys already exist", exit 0.
- **F26:** `scripts/generate-dev-keys.sh` — nếu private tồn tại nhưng public thiếu → regenerate public từ cùng private (`openssl pkey -pubout`), không bao giờ ghi đè private có sẵn trừ khi `--force`. Thêm `JwtService.verifyKeyPair(privateKey, publicKey)` static trong identity-service — sign payload probe RS256 bằng private, verify qua `probe.verify(new RSASSAVerifier((RSAPublicKey) publicKey))`; mismatch → `IllegalStateException("JWT key pair mismatch...")` khi khởi động. Test mới `JwtServiceKeyPairCheckTest`. Kiểm chứng: phần script (xóa public thật, regenerate byte-identical theo cmp+sha256, fingerprint private không đổi); kiểm tra JwtService chạy qua mvnw verify — 2/2 pass.
- **N14:** service nginx có `extra_hosts: ["host.docker.internal:host-gateway"]`. Kiểm chứng: `curl http://localhost:8080/api/drivers` → 502; log nginx cho thấy upstream `http://192.168.65.254:8082/...` (IP host-gateway) với "Connection refused" — DNS resolve, proxy path hoạt động (service Java không chạy local nên 502 là đúng).
- **N16:** `src/web/src/api/client.ts` — thêm exported `uuidV4Fallback()` + `newUuid()`; header dùng `newUuid()`. Test mới `src/web/src/api/uuid.test.ts` (3 cases). Kiểm chứng: `npm test` trong src/web → 16/16 pass (node v24.18.0).

### Tests mới
- `JwtServiceKeyPairCheckTest` ×2 (F26): keypair khớp → verify OK; public sai → IllegalStateException khi khởi động.
- `uuid.test.ts` ×3 (N16): fallback UUID hợp lệ, không trùng trong 100 lần, header dùng cùng hàm.

### Lệnh đã chạy và kết quả thật
| Lệnh | Kết quả |
|---|---|
| `./mvnw -B clean verify` (root, JDK 21) | **BUILD SUCCESS** — identity **27**, driver **98**, trip **55**, matching **8**, payment **8** = **196 tests / 0 failures/errors/skipped** |
| `npm test` (src/web, node v24.18.0) | **16/16 pass** |
| `docker compose down -v && docker compose up -d` | postgres healthy, rabbitmq healthy, nginx up; topo-declare exit 0 — auto-declare đủ 6 durable queues (3 inbox + 3 DLQ); init log "T03: 5 DB + 5 users created" |
| `env -u IDENTITY_DB_PASSWORD java -jar identity-service-0.1.0.jar` | exit 1, log nêu tên `IDENTITY_DB_PASSWORD`, không in giá trị (N09 fail-fast) |

### Ghi chú kỹ thuật
- Spring Boot 4 **không** nạp `BeanFactoryPostProcessor` từ `META-INF/spring.factories` (verify javap trên spring-boot-4.1.1.jar — chỉ EnvironmentPostProcessor được nạp theo cách đó); bean BFP được instantiate bằng reflection **không DI** → dùng `@Component` BFP gọi `beanFactory.getBean(Environment.class)` trong `postProcessBeanFactory`. EnvironmentPostProcessor bị loại vì chạy trước test property sources (@SpringBootTest properties / @DynamicPropertySource) → làm hỏng 20 integration tests.
- RabbitMQ image đặt env mặc định `RABBITMQ_HOST=false`; escript (erl_eval) trả atom `false` cho biến chưa set (không phải undefined — verify bằng probe script). Sửa: escript coi `false`/`undefined`/`""`/`"false"` = "dùng default rabbitmq", compose đặt `RABBITMQ_HOST: rabbitmq` explicit.
- Race: healthcheck `rabbitmq-diagnostics ping` có thể pass trước khi AMQP listener 5672 nhận kết nối → topo-declare `{error,econnrefused}` sau fresh `down -v`. Sửa 2 chiều: healthcheck thêm `nc -z 127.0.0.1 5672` (busybox nc có sẵn trong image) + escript `start_with_retry/2` (30 × 2s) retry chỉ lỗi transient (`econnrefused`, `timeout`, `nxdomain`, `unknown_host`), fail fast với auth error.
- Erlang trong image rabbitmq bị strip: không có `inet.app`/`inet_drv.so`; nhưng `gen_tcp:connect` hoạt động (NIF load on demand) và `inet:getaddr("rabbitmq", inet)` resolve bình thường — amqp_client 3.13.7 dùng `gethostaddr(Host)` → `inet:getaddr`.
- Nimbus API: không có `RSASSAVerifier.verify(JWSObject)`; gọi đúng là `jwsObject.verify(new RSASSAVerifier(pubKey))`.
- Tên env phải hoa (`IDENTITY_DB_PASSWORD`...) — bản heredoc đầu sinh prefix service thường, đã sửa bằng sed và verify lại.
- Smoke tests (matching/payment SecuritySmokeTest) exclude DataSourceAutoConfiguration → thêm `spring.datasource.password=smoke_no_db` để thỏa validator mà không cần DB thật.

### Trạng thái FIX_LIST
T03-1..4, T04-1..3, C05/F25, F26, N14, N16 đánh dấu `[x]` kèm bằng chứng. Còn mở: **Phase 7** (frontend/nginx: F15–F19, N34, T08-3..8/16/17) và **Phase 8** (P3 còn lại: C04/C06, T06-1..4, T07-11..13, T05-7..12 gồm T05-10 — mở khóa T12-6/N28, Q01).

## Lượt 2026-10-22 — FIX_LIST T01–T12: Phase 7 (frontend + nginx)

### Việc đã làm
- **T08-3 (F22):** `deploy/nginx/nginx.conf` — `error_page 502/503/504 = @json_5xx`: named locations riêng cho từng status (`@json_502/@json_503/@json_504`) trả JSON `UPSTREAM_UNAVAILABLE` kèm `$cid` + header `X-Correlation-ID`; thêm dòng `502/503/504 UPSTREAM_UNAVAILABLE` vào contract §1.2.
- **T08-4 (F23):** `error_page 413 = @json_payload_too_large` — JSON động với `$cid` trong body + header; xóa file tĩnh `src/web/public/errors/payload-too-large.json`; README cập nhật mô tả 413.
- **T08-5 (F24):** `location = /api` + `location /api/` → 404 JSON `NOT_FOUND` trước SPA fallback.
- **T08-6 (N06):** `limit_req_status 429;` + `error_page 429 = @json_rate_limited` → JSON `RATE_LIMITED`.
- **T08-7 (N05):** `server_tokens off;`.
- **T08-8 (N12 phần nginx):** `map $http_x_correlation_id $cid { "~^[0-9a-fA-F]{8}-...{12}$" $http_x_correlation_id; default $gen_cid; }` + `proxy_set_header X-Correlation-ID $cid;` ở cả 8 proxy locations + `$cid` trong `log_format main`. **Deviation ghi nhận:** image pin `nginx:1.27.5-alpine` KHÔNG có module request_id (`$request_id_uuid` không tồn tại — `nginx -t` báo unknown variable) nên `$gen_cid` sinh bằng chuỗi 5 `map` thuần config (msec+connection+request_length-remote_port → nhân đôi ≥32 chữ số → cắt 32 → format 8-4-4-4-12; chữ số thập phân hợp lệ hex → backend `CorrelationIdFilter` parse được).
- **T05-11:** thêm dòng `429 RATE_LIMITED` vào contract §1.2 (cài đặt nginx ở T08-6).
- **F15 (T08-11):** `api/client.ts` giữ `sentToken`; chỉ `clearSession()` khi token đang lưu === token đã gửi.
- **F16 (T08-12):** mới `auth/generation.ts` — bump tại submit login/register + logout; continuation chỉ khi generation khớp và mounted; AbortController abort khi unmount.
- **F17 (T08-13):** `javaTrim()` (codePoints ≤ U+0020, đúng Java `String.trim()`) trong `validation.ts`; password chặn toàn khoảng trắng; login password chỉ blank-check (backend không có min length); email raw ≤ 254 (= `@Size(max=254)`); fullName dùng javaTrim.
- **F18+F19 (T08-14):** register 201 + login lỗi → `navigate('/login', { state: { notice } })`, LoginPage hiện Alert từ `location.state.notice`; `useMe` trả `{user, loading, error:'AUTH'|'NETWORK', retry}` — chỉ AUTH redirect /login, NETWORK/5xx nút "Thử lại".
- **N34 (T08-15):** session lưu `expiresAt`, `loadSession()` null + xóa item khi hết hạn; fetch timeout 5 s (AbortController) → REQUEST_TIMEOUT/NETWORK_ERROR/REQUEST_ABORTED message tiếng Việt; `signal` tùy chọn cho register/login (`AbortSignal.any`); `RedirectIfAuthed` bọc /login và /register.
- **F30 (T08-16):** nâng vite/vitest/react-router-dom/plugin-react (+ jsdom, @testing-library/react) — `npm audit` 0 vulnerabilities.
- **Q04 (T08-17):** test jsdom cho client/useMe/LoginPage/RegisterPage + mở rộng session/validation tests; `test/setup.ts` polyfill matchMedia.

### Kiểm chứng thật qua :8080 (backend Java không chạy)
| Probe | Kết quả |
|---|---|
| `/api/drivers` không header | 502 JSON UPSTREAM_UNAVAILABLE, cid sinh trong body + header `X-Correlation-ID`; access log (`docker logs`) ghi `cid=` trùng |
| `/api/drivers` kèm UUID | body/header/log giữ đúng UUID đã gửi |
| `/api/does-not-exist`, `/api/driversfoo` | 404 JSON NOT_FOUND với cid |
| POST login body 70 043 byte + UUID | 413 JSON PAYLOAD_TOO_LARGE, correlationId = UUID đã gửi (header trùng) |
| Burst 8 req `/api/auth/login` (5r/s burst=5) | 7×502 + 1×429; body 429 = RATE_LIMITED JSON cùng cid |
| Header `Server:` | `nginx` không kèm phiên bản |
| Echo server Node :8082 (tạm, kill sau) | nhận đúng X-Correlation-ID đã gửi và cả ID nginx sinh — chứng minh proxy_set_header tới upstream |

Ghi chú: access.log trong image là symlink `/dev/stdout` (ghi sẵn ở docs/tasks/T08.md) → đọc bằng `docker logs ridehailing-nginx`, không `docker exec tail`.

### Tests và build
| Lệnh | Kết quả |
|---|---|
| `npm test` (src/web, node v24.18.0) | **43/43 pass** (7 files: client, useMe, LoginPage, RegisterPage, session, validation, uuid) |
| `tsc --noEmit && npm run build` | OK (chỉ warning chunk >500 kB) |
| `npm audit` | 0 vulnerabilities |
| `./mvnw -B clean verify` (root, JDK 21) | **BUILD SUCCESS** — identity **31**, driver **98**, trip **55**, matching **8**, payment **8** = **200 tests / 0 failures/errors/skipped** |

### Ghi chú kỹ thuật
- Nginx: `return` không hỗ trợ nối chuỗi `'a' . $var . 'b'` — phải một chuỗi quote duy nhất với `$cid` nội suy; `add_header` mặc định chỉ áp dụng 2xx/30x → mọi dòng `add_header X-Correlation-ID $cid` cần `always`.
- Vitest/jsdom: `sessionStorage` undefined ở node env → `// @vitest-environment jsdom`; antd cần polyfill `window.matchMedia`; RTL auto-cleanup không chạy giữa các test trong vitest → gọi `cleanup()` explicit trong afterEach + query scoped theo container.
- Race test F16 dùng **hai MemoryRouter độc lập** — router chung sẽ unmount instance A khi B navigate, abort fetch của A và làm assertion generation-guard không đạt được.
- F18 dùng `location.state.notice` thay vì setError+navigate (alert không hiển thị sau unmount).

### Trạng thái FIX_LIST
T08-3..8, T08-11..17, T05-11 đánh dấu `[x]` kèm bằng chứng. Còn mở: **Phase 8** (P3 còn lại: C04/C06, T06-1..4, T07-11..13, T05-7..12 gồm T05-10 — mở khóa T12-6/N28, Q01).

## Lượt 2026-10-22 — FIX_LIST T01–T12: Phase 8 (P3 còn lại) — XONG

### Việc đã làm
- **C04 (N38):** README Stack table viết lại khớp POM thật (không có spring-boot-starter-amqp; chỉ identity dùng JPA; security crypto-only); thêm mục "Lệch đặc tả có chủ đích" (JdbcTemplate vs JPA: CAS fencing, ON CONFLICT DO NOTHING, advisory lock, REPEATABLE READ; RabbitMQ topology sẵn nhưng AMQP client thuộc T13/T14). Tạo mới `docs/openapi.yaml` (OpenAPI 3.0.3) phủ đủ endpoint hiện có: identity register/login/me (T06), driver public profile/availability/location + internal candidates/reserve/get/confirm/release/finish (T09/T11), trip quote/create/list/get (T12); schema đối chiếu với record thật trong code.
- **C06 (N17):** 5 `FlywayConfig.java` viết lại — bean trả `FlywayMigrationRunner` (`initMethod="migrateWithRetry"`) retry hữu hạn (`app.flyway.retry-attempts:10`, `retry-delay-ms:2000`), warn mỗi lần thử, IllegalStateException sau lần cuối. 5 `application.yml`: `spring.datasource.hikari.connection-timeout: 2000` + `initialization-fail-timeout: 2000`, `connection-test-query: SELECT 1`, `management.endpoint.health.probes.enabled: true` + `management.endpoint.health.group.readiness.include: db`. **Verify live** (identity jar + docker postgres): PG down lúc khởi động → attempt 1–4 fail có warn → PG up sau ~8 s → "Flyway migration succeeded on attempt 4" → Tomcat up <30 s. Readiness probe: `{"status":"UP"}` 200 khi PG up; dừng PG → `{"status":"DOWN"}` **503**; khởi động lại PG → UP 200. Fail-fast: PG down >40 s → app exit sau attempt 10 với lỗi rõ.
- **T06-1 (F05):** `UserService.containsControlChar()` (`Character::isISOControl`) — register từ chối control char trong fullName (sau trim) → 400 INVALID_INPUT; test `controlCharInFullNameRejectedWithoutPersisting` (AuthFlowIntegrationTest). Driver: check tương tự trong `DriverService.validateProfileInput` cho displayName + test `controlCharInDisplayNameRejectedWithoutPersisting` (DriverProfileIntegrationTest).
- **T06-4 (R03):** `UserService` — `DUMMY_HASH = BCryptPasswordEncoder().encode("dummy-password-for-timing-equalization")`; login email không tồn tại chạy `passwordEncoder.matches(password, DUMMY_HASH)` trước. Test `loginWithMissingEmailTakesComparableTimeToWrongPassword`: 20 lần mỗi path, assert avgMissing ≥ 0.4 × avgExisting.
- **T07-11 (N03):** mới cho cả 5 service: `exception/PayloadTooLargeException.java` + `security/BodySizeLimitFilter.java` (@Order(2); áp `/api/**` và `/internal/**`; Content-Length > 65536 → 413 JSON với correlationId ngay; body chunked/không CL → counting wrapper, đọc vượt 64 KiB → PayloadTooLargeException) + `@ExceptionHandler(PayloadTooLargeException.class)` → 413 PAYLOAD_TOO_LARGE trong mỗi GlobalExceptionHandler. Tests: identity `oversizedBodyReturns413PayloadTooLarge` (AuthFlowIntegrationTest, raw request kèm correlation header), driver cùng tên (DriverProfileIntegrationTest, JWT hợp lệ + displayName 70k char), trip cùng tên (SecurityProbeTest, token passenger + padding field), matching/payment cùng tên (SecuritySmokeTest, helper `post(path, byte[], correlation)` POST `/api/does-not-exist` body 70 KiB).
- **T07-12 (N08):** `InternalTokenFilter` constructor ném IllegalStateException nếu token null/blank/<16 ký tự. Unit test mới `src/driver-service/src/test/java/com/ridehailing/driver/security/InternalTokenFailFastTest.java` (4 tests: empty/null/15-char → throw; 23-char OK). `.env` đồng bộ `.env.example`: thêm `INTERNAL_API_TOKEN=internal_dev_2026`, `FARE_BASE_VND=12000`, `FARE_PER_KM_VND=5000`, `RESERVATION_EXPIRY_ENABLED=true`, `RESERVATION_EXPIRY_FIXED_DELAY_MS=1000`. Các test driver cũ dùng token ≥16 ký tự nên fail-fast không phá chúng.
- **T07-13 (Q01):** 5 `JsonLogLayoutTest.java` mở rộng — `layoutDoesNotCreateTopLevelSecretFields` dùng secrets mẫu "secret-token-123"/"sample-password-456" + helper mới `assertNoSecretsOutsideMessage(m)` (quét mọi field parse được trừ `message`); test mới `normalEventOutputContainsNoSecrets` assert toàn bộ chuỗi output không chứa token/password mẫu/`Bearer `. Identity: `AuthFlowIntegrationTest.registerLoginFlowLogsContainNoSecrets` — gắn OutputStreamAppender+JsonLogLayout programmatic vào root logger, chạy register+login THẬT qua HTTP, quét log bắt được: phải có `/api/auth/register` + `/api/auth/login`, KHÔNG chứa password ("secret123"), JWT accessToken thật, hay "Bearer ".
- **T12-6 (N28):** `TripService.create` — sau khi claim idempotency thành công, trong cùng transaction: `tripRepo.acquirePassengerAdvisoryLock(passengerId)` (`SELECT pg_advisory_xact_lock(hashtext(?))` qua `jdbc.query(sql, rs -> null, arg)`) rồi `countNonTerminalForPassenger` (state NOT IN ('COMPLETED','CANCELLED','NO_DRIVER')) > 0 → ném `ActiveTripExistsException` mới → GlobalExceptionHandler trả **409 ACTIVE_TRIP_EXISTS** details `{passengerId}`. Replay key cũ (claimed == 0) bỏ qua nhánh này — vẫn trả stored 202; claim thất bại rollback trong cùng transaction nên key KHÔNG bị tiêu. Tests mới (TripIntegrationTest): `secondCreateForSamePassengerReturns409ActiveTripExists` + `concurrentCreatesForSamePassengerYieldOne202AndOne409`. Test RR cũ T12-5 viết lại dùng 6 passenger khác nhau (assert totalElements == content.size(), size ∈ [0,1], DB count cuối = 6).
- **Contract docs (T05-7..10, T05-12):** `state-machines.md` §5 — FAILED terminal ⊘, không proactive retry theo spec §15; ghi chú payment_attempt §1 cập nhật. `events.md` §3.4 `paymentAttempt`→`attempt`, §3.6 `failureCode`→`reason`, §3.2 note candidates (MVP không có tên/số xe driver). `api-contracts.md`: dòng 409 ACTIVE_TRIP_EXISTS mới trong §1.2, bullet T12-6 trong §3.3 POST /api/trips, ghi chú "MVP không hiển thị" trong §3.3 GET detail + §4.2 candidates, accepted-risk bullets §2 + decision #8; decision #9 viết lại (FAILED terminal; event field `attempt`).
- **Smoke test fix (hệ quả C06):** Boot 4 validate nghiêm ngặt rằng mọi member của health group phải tồn tại — matching/payment SecuritySmokeTest exclude DataSourceAutoConfiguration → không có contributor 'db' → context load fail. Sửa: thêm property test `management.endpoint.health.group.readiness.include=` (rỗng) trong cả 2 file; `/actuator/health` tổng thể vẫn hoạt động.

### Kiểm chứng thật (JDK 21, lệnh thực)
| Lệnh | Kết quả |
|---|---|
| `./mvnw -B -pl src/matching-service,src/payment-service test` | matching **10/0**, payment **10/0** — BUILD SUCCESS |
| `./mvnw -B clean verify` (root) | identity **36**, driver **105**, trip **59**, matching **10**, payment **10** = **220 tests / 0 failures/errors/skipped** — cả 5 module SUCCESS |

### Ghi chú kỹ thuật
- PgJDBC mới: `jdbc.update("SELECT ...")` fail ("A result was returned when none was expected" — `PgPreparedStatement.executeUpdate` từ chối SELECT trả row) → phải dùng `jdbc.query(sql, rs -> null, args)`.
- T12-6 chọn advisory xact lock per passenger (`pg_advisory_xact_lock(hashtext(uuid))`) + check non-terminal trong @Transactional create sẵn có — không cần migration schema; claim rollback giữ key chưa tiêu. Terminal states = COMPLETED/CANCELLED/NO_DRIVER theo state-machines.md §1.
- Timing test T06-4 dùng assertion tỷ lệ (≥0.4×) — scale-independent nhưng có thể flaky trên máy rất chậm; nếu fail thì chạy lại, không "sửa".

### Trạng thái FIX_LIST
**82/82 mục đánh dấu `[x]`** kèm bằng chứng (`grep -c "^- \[x\]" docs/FIX_LIST_T01-T12.md` = 82; còn 0 `- [ ]`). **T13/T14 mở khóa.**

## Lượt 2026-10-22 — Đánh giá FIX_LIST và sửa L01, L03–L13

Đánh giá kết quả 82 mục: [FIX_REVIEW_T01-T12_2026-10-22.md](FIX_REVIEW_T01-T12_2026-10-22.md) — 74 đạt, 6 đạt một phần, 2 không đạt (T09-6/T12-3: `DELETE ... LIMIT` lỗi cú pháp PostgreSQL). Sau đó sửa L01, L03–L13; **L02 (bằng chứng đóng góp git) còn mở**.

### Việc đã làm
- L01: cleanup `processed_commands`/`idempotency_keys` dùng subquery theo khóa chính.
- L03/L04/L10: `CorrelationIdFilter` + `BodySizeLimitFilter` chạy trước Spring Security; `JsonRequestRejectedHandler` ×5; tắt đăng ký servlet trùng của filter security; Nginx 400 JSON.
- L05: `logging/AuditLog` (MDC tạm thời, ghi sau commit) ở identity/driver/trip.
- L06: Trip V5 — `outbox.schema_version` INT, backfill row cũ, CHECK routing key/aggregate version.
- L07: candidates `ORDER BY …, id`, xử lý kinh tuyến ±180°.
- L08: `deploy/nginx.conf`, `deploy/postgres/init/`, `deploy/rabbitmq/` (container nginx đã tạo lại).
- L09, L11, L12, L13: devDependencies; sửa events.md §6; Nginx `details` + correlation ID từ `$request_id`; handoff ghi ràng buộc một trip active.
- Sửa test frontend flaky `LoginPage.test.tsx` (chờ theo điều kiện thay vì sleep).

### Kiểm chứng thật
| Lệnh | Kết quả |
|---|---|
| `./mvnw -B clean verify` | BUILD SUCCESS — **233 tests / 0 failures** (identity 38, driver 110, trip 63, matching 11, payment 11); log: 0 `BadSqlGrammarException`, 0 lỗi scheduled task, 0 `}{` |
| `npm test` ×15 (song song với Maven) | 15/15 lần 43/43 |
| `npm run build` / `npm audit` | OK / 0 vulnerabilities |
| identity jar + PostgreSQL tạm | `;x` → 400 ApiError có correlationId; request 401/400 có dòng log truy cập |
| Probe Nginx :8080 | 400/404/502 JSON có `details`; correlation ID ngẫu nhiên; UUID client giữ nguyên |

Chưa commit — chờ người dùng quyết định (xem L02 về tài khoản tác giả).

## Lượt 2026-10-23 — T13 Outbox publisher Trip (review + sửa) — DONE

Người dùng đã viết T13; lượt này review và sửa. Chi tiết: [T13](tasks/T13.md).

### Sửa trong review
- Mật khẩu RabbitMQ có giá trị mặc định trong `application.yml` (vi phạm N09) → bỏ default, `RequiredDbPasswordValidator` kiểm tra thêm `RABBITMQ_PASSWORD`.
- Backoff tính bằng `Instant.now()` JVM nhưng so với `now()` DB → mọi mốc thời gian outbox dùng `clock_timestamp()` (R04).
- SELECT không khóa → hai worker có thể publish trùng → claim `FOR UPDATE SKIP LOCKED` + lease.
- ReturnListener cộng dồn trên channel được cache; chờ cố định 200 ms sau mỗi message (~5 msg/s) → gỡ listener sau mỗi lần, kiểm tra return ngay sau confirm (return luôn đến trước ack), khớp theo `messageId`.
- Payload hỏng ném exception → row đó chặn đầu hàng đợi mãi → trả lỗi, backoff, row sau vẫn chạy; worker bắt lỗi từng row và lỗi DB khi claim.
- Test broker down/up fail vì Docker cấp cổng host mới sau restart → cố định cổng host trong test.
- Thêm test: poison row, 2 worker song song (20 row, mỗi event đúng 1 lần), rollback không phát event; log có `correlationId`/`tripId`; README cập nhật env RabbitMQ.

### Kiểm chứng thật
| Lệnh | Kết quả |
|---|---|
| `./mvnw -B -pl src/trip-service -am clean verify` | 69/0 (`OutboxPublishIntegrationTest` 6/6, broker down/up ~40 s); log 0 lỗi scheduled task |
| `OutboxPublishIntegrationTest` lặp 2 lần | 6/6 cả hai lần |
| `./mvnw -B clean verify` toàn repo | **239 tests / 0 failures** (identity 38, driver 110, trip 69, matching 11, payment 11) |

Không chạy trip-service với DB/broker dev (tránh migrate `trip_db` dev và để lại message trong `matching.inbox`). Chưa commit.

## Lượt 2026-10-25 — T14 Consumer inbox và retry (matching-service) — DONE

Chi tiết: [T14](tasks/T14.md).

### Việc đã làm
- Inbox consumer matching-service theo events.md §2/§4/§6: validate envelope; dedup unique `(consumer_name, event_id)` insert + handler trong 1 transaction (ACK chỉ sau commit); transient failure retry in-process hữu hạn (attempt đầu + tối đa 3, backoff `app.inbox.retry-backoff-ms` mặc định `1000,3000,10000`) rồi `reject(requeue=false)` → DLQ; malformed/unsupported republish chủ động vào `ride.events.dlx` rk `matching.dlq` kèm header `x-dlq-reason` + ACK gốc.
- Migration V2: bảng `inbox_events` (dedup) + `matching_probe_events` (side effect probe handler, T14-only).
- Topology declare bởi service khi khởi động (RabbitAdmin declarables): exchange `ride.events`/`ride.events.dlx`, queue `matching.inbox` (+binding rk `trip.requested`) và `matching.dlq`; broker down không chặn startup.
- Fault injection test-only (`FaultInjectionConfig` @Primary wrapper probe handler) — không có knob production.

### Kiểm chứng thật
| Lệnh | Kết quả |
|---|---|
| `./mvnw -B clean verify` toàn repo | BUILD SUCCESS — **244 tests / 0 failures** (identity 38, driver 110, trip 69, matching 16, payment 11) |
| matching module | `InboxConsumerIntegrationTest` 5/5 trên PostgreSQL + RabbitMQ thật (Testcontainers): duplicate once, malformed→DLQ `x-dlq-reason`, unsupported_event_type, retry bounded, exhausted→x-death only; `JsonLogLayoutTest` 6/6; `SecuritySmokeTest` 5/5 |

Không chạy service với DB/broker dev (tránh migrate `matching_db` dev và để lại message trong `matching.inbox`). Chưa commit.

## Lượt 2026-10-25 — T15 Matching job thật (matching-service) — DONE

Chi tiết: [T15](tasks/T15.md).

### Việc đã làm
- matching-service consume `TripRequested` → lưu job/attempt vào `matching_jobs` (unique `(trip_id, attempt)`), gọi candidates API driver-service (internal, `X-Internal-Token`), lọc excludedDriverIds; Matching outbox phát `DriverProposed` (candidates có thứ tự distanceM tăng) hoặc `NoDriverFound`. **Không tự reserve driver** — trip-service nhận proposal và gọi reserve ở T16.
- Migration V3: `matching_jobs` + outbox matching riêng (event_type chỉ DriverProposed/NoDriverFound, causation_id = eventId TripRequested theo events.md §5).
- Outbox publisher/worker mirror trip T13 (claim lease `FOR UPDATE SKIP LOCKED`, persistent + mandatory + confirms, backoff tối đa 30 s); envelope bỏ `aggregateVersion` khi = 0 (§2 optional — matching không đọc Trip DB).
- Stale-event guard theo events.md §4.4 qua chính bảng `matching_jobs` (`MAX(attempt) > attempt` → skip + ACK, không DLQ).
- Fail-fast startup nếu thiếu `INTERNAL_API_TOKEN` (pattern N08/N09); env mới `DRIVER_SERVICE_URL`/`MATCHING_RADIUS_M`/`MATCHING_MAX_CANDIDATES`/`MATCHING_HTTP_TIMEOUT_MS`.

### Kiểm chứng thật
| Lệnh | Kết quả |
|---|---|
| `./mvnw -B -pl src/matching-service clean verify` | **23 tests / 0 failures** — `MatchingJobIntegrationTest` 7/7 trên PostgreSQL + RabbitMQ thật (Testcontainers) + stub JDK HttpServer cho candidates API; `InboxConsumerIntegrationTest` 5/5; `JsonLogLayoutTest` 6/6; `SecuritySmokeTest` 5/5 |
| `./mvnw -B clean verify` toàn repo | BUILD SUCCESS — **251 tests / 0 failures** (identity 38, driver 110, trip 69, matching 23, payment 11) |

Không chạy service với DB/broker dev (tránh migrate `matching_db` dev và để lại message trong `matching.inbox`). Chưa commit.

## Lượt 2026-10-25 — T16 Trip nhận proposal và reserve (trip-service) — DONE

Chi tiết: [T16](tasks/T16.md).

### Việc đã làm
- trip-service consume `DriverProposed`/`NoDriverFound` từ queue `trip.inbox` (bind `matching.#` + `payment.#`, DLQ `trip.dlq`): validate envelope, dedup unique `(consumer_name, event_id)` insert + handler trong 1 transaction (ACK sau commit), retry in-process hữu hạn (3 lần, backoff `1000,3000,10000`) rồi reject → DLQ; malformed/unsupported republish dlx kèm `x-dlq-reason`.
- Migration V6: `sagas` (một row per trip, phase/desired_action theo state-machines.md §2, CAS version+phase, deadline_at) + `inbox_events` (dedup).
- Handler: stale guard (§4.4 → log `stale_event_skipped` + ACK) → saga WAITING_MATCH check → thử reserve candidate theo thứ tự qua internal API driver-service (`PUT /internal/reservations/{id}`, body contract §4.2, reservationId/commandId deterministic UUID v3); 201 → success; 409 conflict → candidate kế tiếp; `COMMAND_CONFLICT`/timeout → GET reconcile CÙNG reservationId (RESERVED khớp = thành công), timeout mù → retry PUT một lần rồi RuntimeException bounded.
- Success: saga CAS → WAITING_DRIVER + trips CAS DRIVER_OFFERED (driver_id/driver_user_id/reservation_id) + trip_event DRIVER_OFFERED — mọi đọc trước, HTTP giữa, ghi sau trong 1 transaction (không giữ DB lock khi gọi HTTP).
- Hết candidate/NoDriverFound: attempt < max và chưa quá deadline → SEARCHING→SEARCHING attempt+1 + saga next_retry_at = now + MATCHING_RETRY_DELAY_MS + outbox PENDING TripRequested (causationId = eventId event này, §5); ngược lại NO_DRIVER terminal + trip_event + saga ABORTED.
- `TripService.create()` insert saga WAITING_MATCH cùng transaction (deadline = createdAt + TRIP_MATCH_DEADLINE_SECONDS) — hoàn tất chốt T12. Fail-fast startup khi thiếu `INTERNAL_API_TOKEN` (N08/N09); env mới `DRIVER_SERVICE_URL`/`OFFER_TTL_SECONDS`/`MAX_MATCHING_ATTEMPTS`/`MATCHING_RETRY_DELAY_MS`/`TRIP_MATCH_DEADLINE_SECONDS`.

### Kiểm chứng thật
| Lệnh | Kết quả |
|---|---|
| `./mvnw -B -pl src/trip-service clean verify` | **77 tests / 0 failures** — `TripProposalIntegrationTest` 8/8 trên PostgreSQL + RabbitMQ thật (Testcontainers) + stub JDK HttpServer cho internal API driver-service; `TripIntegrationTest` 28/28; `OutboxPublishIntegrationTest` 6/6; `SecurityProbeTest` 25/25; `JsonLogLayoutTest` 6/6; `HaversineTest` 4/4 |
| `./mvnw -B clean verify` toàn repo | BUILD SUCCESS — **259 tests / 0 failures** (identity 38, driver 110, trip 77, matching 23, payment 11) |

Sửa trong lượt: jsonb cast PGobject→String ở `SagaRepository.findByTripId`; Spring 7 RestClient ném `RestClientException` khi read-timeout (catch broaden); assertion Flyway version "5"→"6" sau V6. Không chạy service với DB/broker dev (tránh migrate `trip_db` dev). Chưa commit.

## Lượt 2026-10-25 — T17 Driver đọc offer — DONE

Chi tiết: [T17](tasks/T17.md). Dependency T11, T16 DONE (theo backlog; T14–T16 do người dùng làm, chưa commit, test của chúng pass trong full build bên dưới).

### Việc đã làm
- `GET /api/drivers/me/offer`: `ReservationRepository.findCurrentOffer` (fencing qua `current_reservation_id`, RESERVED, `expires_at > clock_timestamp()`), `DriverService.currentOffer`, controller trả literal JSON `null` khi không có offer, 404 khi chưa có profile.
- Contract §3.2 chốt thêm; OpenAPI thêm path + schema `Offer` và sửa lỗi cú pháp YAML có sẵn (header `Location`) — `swagger-cli validate` OK.
- `DriverOfferIntegrationTest` 8 test.

### Kiểm chứng thật
| Lệnh | Kết quả |
|---|---|
| `./mvnw -B -pl src/driver-service -am clean verify` | driver **118/0** (`DriverOfferIntegrationTest` 8/8) |
| `./mvnw -B clean verify` toàn repo | **267 tests / 0 failures** (identity 38, driver 118, trip 77, matching 23, payment 11); 0 lỗi scheduled task |
| identity + driver jar, PostgreSQL tạm, request qua Nginx :8080 | chưa profile 404 → AVAILABLE `null` (200, `application/json`) → sau reserve: offer khớp `tripId`/`expiresAt` của lệnh reserve |

Docker Desktop tắt lúc bắt đầu chạy test (Testcontainers báo không có Docker) — đã bật lại rồi chạy. Không dùng DB dev. Chưa commit.

## Lượt 2026-10-25 — T18, T19, T20 (accept / reject / cancel) — DONE

Người dùng chỉ định ba task; làm tuần tự, mỗi task chạy test xong mới sang task sau. Chi tiết: [T18](tasks/T18.md), [T19](tasks/T19.md), [T20](tasks/T20.md).

### Việc đã làm
- `TripCommandService` (mới): accept / reject / cancel theo mẫu Tx1 khóa row saga ghi intent → HTTP tới driver-service ngoài transaction (commandId tất định; confirm v2, release v3; timeout/5xx → GET reconcile) → Tx2 ghi kết quả.
- `DriverReservationClient`: `confirm`, `release`, `getState`. `SagaRepository`: `lockByTripId`, `updateLocked`, `recordPendingError`, `isPastDeadline`. `TripRepository`: các chuyển trạng thái CAS trả version.
- Sửa T16 (tối thiểu, cần cho T19/T20): giữ `excludedDriverIds` khi offer; giới hạn candidate theo `app.trip.max-candidates`.
- `POST /api/trips/*/cancel` chỉ PASSENGER. Exception `STATE_CONFLICT`, `NOT_OFFERED_DRIVER`.
- Test: `StatefulDriverStub` (driver-service giả lập có trạng thái theo contract §4.2), `TripCommandTestSupport`, `TripAcceptIntegrationTest` 7, `TripRejectIntegrationTest` 9, `TripCancelIntegrationTest` 8.
- Docs: contract §3.3/§4.1 chốt T18–T20, state-machines, OpenAPI (validate OK).
- Chuẩn hóa line ending LF cho các file Java của trip-service có CRLF.

### Kiểm chứng thật
| Lệnh | Kết quả |
|---|---|
| `./mvnw -B -pl src/trip-service -am clean verify` sau T18 / T19 | 84/0 / 93/0 |
| C02 `concurrentAcceptAndCancelHaveExactlyOneWinner` (20 vòng, jitter) × 4 lần | accept thắng 11/6/10/9, cancel thắng 9/14/10/11; mọi vòng đúng bất biến |
| `./mvnw -B clean verify` toàn repo | **291 tests / 0 failures** (identity 38, driver 118, trip 101, matching 23, payment 11); 0 lỗi scheduled task |
| 4 service jar thật + PostgreSQL/RabbitMQ tạm, request qua Nginx :8080 | accept (D1 BUSY), sai driver → NOT_OFFERED_DRIVER, hủy sau ACCEPTED → 409; reject → D2 AVAILABLE ngay, không bị đề nghị lại, NO_DRIVER ở attempt 3; hủy khi offered → 202 CANCELLED, D2 AVAILABLE; hủy khi SEARCHING → 200 + 5 tombstone; cuối cùng 0 reservation RESERVED, 0 log ERROR |

Không dùng DB/broker dev. Chưa commit.

## Lượt 2026-10-26 — Review và sửa T15 (matching) + T16 (trip nhận proposal)

Chi tiết từng lỗi, cách sửa và test: [T15](tasks/T15.md) và [T16](tasks/T16.md), mục "Sửa sau review".

- **T15:** M1 candidates API nhận `excludeDriverIds`, lọc trong SQL trước LIMIT (driver-service + matching client; contract §4.2, OpenAPI); M2 khóa advisory theo trip cho stale guard; M3 cùng attempt khác eventId → bỏ qua thay vì DLQ; M4 listener (matching + trip) dùng correlationId của envelope.
- **T16:** T1 `MATCHING_RETRY_DELAY_MS` áp dụng thật qua outbox `next_attempt_at`; T2 deadline theo đồng hồ DB, kiểm tra trước khi reserve; T4 trip lọc lại excluded; T5 log qua `AuditLog`.
- Chuẩn hóa LF cho file matching-service có CRLF.

| Lệnh | Kết quả |
|---|---|
| `./mvnw -B -pl src/driver-service,src/matching-service,src/trip-service -am clean verify` | driver **120/0**, matching **26/0**, trip **103/0** |
| `./mvnw -B -pl src/identity-service clean verify` | 38/0 |
| `swagger-cli validate docs/openapi.yaml` | valid |

**Phát hiện ngoài phạm vi (chưa sửa):** báo cáo test identity lúc 00:07 ngày 26/10 (một lần chạy với log DEBUG bật từ bên ngoài) cho thấy `RequestResponseBodyMethodProcessor` ở mức DEBUG ghi nguyên body request, **gồm cả mật khẩu** (`RegisterRequest[... password=...]`); test `registerLoginFlowLogsContainNoSecrets` đã bắt được. Cấu hình mặc định (INFO) không bị. Nên tách task: che `password` trong `toString()` của `RegisterRequest`/`LoginRequest` và/hoặc chặn DEBUG cho logger đó.

Chưa commit.

## Lượt 2026-10-26 — T21: Timeout và restart Saga (trip-service) — DONE

Chi tiết: [T21](tasks/T21.md).

### Việc đã làm
- `SagaRecoveryWorker` (mới): @Scheduled quét saga chưa terminal có việc pending (`findActionableDue`), claim bằng transaction ngắn (`FOR UPDATE` + CAS version + `lease_owner` + re-arm `next_retry_at = now + lease`), thực thi intent ngoài khóa DB; lỗi một row không dừng cả lô.
- 4 đường xử lý: offer TTL hết hạn → release `OFFER_EXPIRED` → tìm lại/NO_DRIVER; deadline tổng WAITING_MATCH → tombstone mọi reservationId tất định của attempt → NO_DRIVER + ABORTED (late reserve nhận 409, không hồi sinh); intent CONFIRMING/COMPENSATING treo → resume idempotent (commandId tất định, driver dedup §4.1).
- `V7__saga_worker_lease.sql`: cột `sagas.lease_owner`. `TripInboxHandler` lưu một `offerExpiresAt` duy nhất mỗi lượt vào saga context; `deterministicReservationId` public static.
- Test mới: `TripSagaRecoveryIntegrationTest` 7 (offer expiry, deadline tombstone + late reserve, CONFIRMED không bị TTL release, resume confirm/reject/cancel) và `TripSagaRestartIntegrationTest` 1 (hai application context thật trên một PG, broker cố tình vắng mặt: intent CONFIRMING treo sống qua restart → ACCEPTED; COMPENSATING/REJECT treo → SEARCHING attempt 2 + outbox PENDING).
- Tắt worker schedule trong test fault-injection (`app.saga.worker-interval-ms=3600000` ở `TripCommandTestSupport`) — tick 1 s có thể claim intent treo giữa commit intent và HTTP call, tiêu thụ fault flag trước request.

| Lệnh | Kết quả |
|---|---|
| `./mvnw -B -pl src/trip-service clean verify` | **111/0** (recovery 7/0, restart 1/0; test T18–T20 xanh trở lại) |
| `./mvnw -B clean verify` toàn repo | **306 tests / 0 failures** (identity 38, driver 120, trip 111, matching 26, payment 11) |

Không dùng DB/broker dev. Chưa commit. T22 mở khóa (dep T18 + T21).

## Lượt 2026-10-26 — T22: Start/complete và finish driver (trip-service) — DONE

Chi tiết: [T22](tasks/T22.md).

### Việc đã làm
- `TripCommandService`: `start` (guard ACCEPTED + assigned driver, CAS IN_PROGRESS + trip_event TRIP_STARTED); `complete` = Tx1 {COMPLETED + payment PENDING/attempt 1 + TRIP_COMPLETED + outbox TripCompleted `{tripId, attempt:1, amountVnd=fare_vnd, currency:"VND"}` causation NULL + intent bền AWAITING_FINISH/FINISH_ASSIGNMENT} → ngoài tx gọi finish driver (commandVersion 4, commandId tất định theo reservation) — thành công = saga COMPLETED terminal, chưa rõ = pending cho worker; `resumePendingFinish` cho worker.
- `TripRepository.markInProgress/markCompleted`; `DriverReservationClient.finish`; `SagaRepository.findActionableDue` + `SagaRecoveryWorker` xử lý phase AWAITING_FINISH; exception mới 409 NOT_ASSIGNED_DRIVER; controller POST start/complete (role DRIVER đã enforce ở T07).
- Test: `TripStartCompleteIntegrationTest` 6 test (happy path, guard sai driver/sai state, lặp không phát intent mới, finish fault → worker resume với đúng 2 lần POST finish); `StatefulDriverStub` thêm action `finish` theo contract §4.2 + fault injection.
- Docs: api-contracts.md §3.3/§4.2 "Chốt T22" (commandVersion finish = 4, hành vi lặp, intent bền).

| Lệnh | Kết quả |
|---|---|
| `./mvnw -B -pl src/trip-service clean verify` | **117/0** (TripStartCompleteIntegrationTest 6/0) |
| `./mvnw -B clean verify` toàn repo | **BUILD SUCCESS — 312 tests / 0 failures** (identity 38, driver 120, trip 117, matching 26, payment 11) |

Không dùng DB/broker dev. Chưa commit. T23 mở khóa (dep T13 + T14 + T22 — cả ba DONE).

## Lượt 2026-10-27 — T23: Payment consumer (payment-service) — DONE

Chi tiết: [T23](tasks/T23.md).

### Việc đã làm
- **Migrations:** V2 `inbox_events` (dedup unique `(consumer_name, event_id)`); V3 `payments` (`unique(trip_id, attempt)`, status `SUCCEEDED`/`FAILED`, `failure_code`, cột traceability `event_id`/`correlation_id`) + `outbox` producer payment-service (`event_type CHECK IN ('PaymentSucceeded','PaymentFailed')`).
- **Inbox:** `RabbitTopologyConfig` declare `payment.inbox` (DLX rk `payment.dlq`) bind `trip.completed`; `InboxMessageValidator` (TripCompleted only; amount/currency/aggregateId validation); listener manual ACK sau commit, retry bounded 3 + backoff env, hết lượt → DLQ (`x-death` only); malformed/unsupported → republish chủ động kèm `x-dlq-reason`.
- **Nghiệp vụ:** `TripCompletedHandler` — duplicate business-intent guard (SELECT payments trip+attempt → skip+ACK), thu tiền giả lập `PaymentSimulator` (`PAYMENT_SIMULATE_FAIL_RATE`, default 0) → payments row status cuối + outbox PENDING trong cùng transaction; causationId = eventId TripCompleted, aggregateVersion bỏ khỏi envelope.
- **Outbox:** worker publish persistent + mandatory + confirms (mẫu T13), producer `'payment-service'`.
- Test: `PaymentConsumerIntegrationTest` 7 test trên PG+RabbitMQ thật (testcontainers pinned) + `FaultInjectionConfig` bọc handler thật; `SecuritySmokeTest` cập nhật sang mẫu matching T14 (H2 in-memory).
- Docs: state-machines.md §5 thêm mục "Chốt T23" (payments không có row PENDING trung gian).

| Lệnh | Kết quả |
|---|---|
| `./mvnw -B -pl src/payment-service clean verify` | **18/0** (PaymentConsumerIntegrationTest 7/0; SecuritySmokeTest 5/0; JsonLogLayoutTest 6/0) |
| `./mvnw -B clean verify` toàn repo | **BUILD SUCCESS — 319 tests / 0 failures** (identity 38, driver 120, trip 117, matching 26, payment 18) |

Không dùng DB/broker dev. Chưa commit. T24 mở khóa (dep T23 DONE).

## Lượt 2026-10-27 — T24: Trip nhận payment result (trip-service) — DONE

Chi tiết: [T24](tasks/T24.md).

### Việc đã làm
- **Inbox:** `InboxMessageValidator` mở rộng `HANDLED_EVENT_TYPES += PaymentSucceeded, PaymentFailed` + 2 records payload mới theo events.md §3.5/§3.6 (`PaymentSucceededPayload {tripId, paymentId, attempt, amountVnd}`, `PaymentFailedPayload {tripId, paymentId, attempt, reason}`); validation: `tripId == aggregateId`, `paymentId` UUID, `attempt ≥ 1`, `amountVnd` nguyên >0 vừa long (guard double overflow), `reason` non-blank free string. Listener T16 không đổi.
- **Nghiệp vụ:** `TripInboxHandler.handlePaymentResult` — guard state COMPLETED + `payment_attempt == attempt` + `payment_status PENDING` (§4.4); CAS `markPaymentResult` (PENDING → SUCCEEDED/FAILED, `trips.version+1`) + trip_event `PAYMENT_SUCCEEDED/PAYMENT_FAILED` tại version mới trong cùng transaction listener; stale/duplicate → audit log + skip ACK (không DLQ); CAS fail → rollback + retry bounded.
- Test: `TripPaymentResultIntegrationTest` 6 test trên PG+RabbitMQ thật — publish PaymentSucceeded/PaymentFailed qua exchange `ride.events` rk `payment.succeeded`/`payment.failed` (đường đi thật của outbox payment-service); fault injection nested `FaultInjectionConfig` (`@Primary InboxEventHandler` bọc handler thật, backoff 100/200/400 ms).
- Docs: state-machines.md §5 thêm mục "Chốt T24".

| Lệnh | Kết quả |
|---|---|
| `./mvnw -B -pl src/trip-service clean verify` | **123/0** (TripPaymentResultIntegrationTest 6/0 trên PG+RabbitMQ thật ~51 s) |
| `./mvnw -B clean verify` toàn repo | **BUILD SUCCESS — 325 tests / 0 failures** (identity 38, driver 120, trip 123, matching 26, payment 18) |

Không dùng DB/broker dev. Chưa commit. T25 mở khóa (dep T19 + T20 + T22 + T24 — cả bốn DONE).

## Lượt 2026-10-27 — T25: Notifications và trip event history (trip-service) — DONE

Chi tiết: [T25](tasks/T25.md).

### Việc đã làm
- **Schema:** `V8__notifications.sql` — bảng notifications theo spec + cột `version`, unique `(user_id, trip_id, version, type)` để dedup theo transition (một transition có thể notify 2 user nên key gồm `user_id`).
- **API:** `GET /api/notifications` (mọi role xác thực, chỉ thông báo của caller, phân trang §1.3, sắp created_at DESC) + `GET /api/trips/{id}/events` (owner hoặc assigned driver, không thì 404 — §1.2; sắp version DESC).
- **Ghi notification tại các transition đã có**, trong cùng transaction với trip_event: offer → passenger TRIP_OFFERED + driver OFFER_RECEIVED; accept/complete → cả hai; cancel → passenger luôn + driver nếu đã offer lúc hủy (row đọc trước NULL-out); NO_DRIVER (exhausted reject, deadline) và PAYMENT_* → chỉ passenger.
- Test mới `TripNotificationIntegrationTest` 7 test trên PG+RabbitMQ thật; sửa cleanup 5 tệp test support (`DELETE FROM notifications` trước trips vì FK mới); `SecurityProbeTest` đổi probe sang path không handler + tạo bảng H2; assertion Flyway version `"8"` trong `TripIntegrationTest`.

| Lệnh | Kết quả |
|---|---|
| `./mvnw -B -pl src/trip-service clean verify` | **154/0** (TripNotificationIntegrationTest 7/0 trên PG+RabbitMQ thật) |
| `./mvnw -B clean verify` toàn repo | **BUILD SUCCESS — 371 tests / 0 failures** (identity 38, driver 120, trip 154, matching 26, payment 33) |

Không dùng DB/broker dev. Chưa commit phần T25. T26 mở khóa (dep T06 + T09 + T22 + T24 + T25 — cả năm DONE).

## Lượt 2026-10-27 — T26: Seed và smoke script — DONE

Chi tiết: [T26](tasks/T26.md).

### Việc đã làm
- `scripts/seed-demo.sh` (mới): seed 3 passengers + 3 drivers chỉ qua public API Nginx :8080; idempotent (register trùng → 409 EMAIL_TAKEN skip; profile POST 201/200); driver AVAILABLE + tọa độ demo quanh Hồ Gươm (trong radius matching 5000 m).
- `scripts/smoke-trip.sh` (mới): quote → create (Idempotency-Key mới) → chờ DRIVER_OFFERED (refresh heartbeat drivers mỗi ~10 s — điều kiện matching `last_seen_at < 15 s`) → accept qua offer (§3.2) → start → complete → payment result; verify notifications + event history theo tripId.
- `scripts/reset-demo.sh` (mới): bắt buộc `--confirm`; hủy trip SEARCHING/DRIVER_OFFERED của demo users, đưa drivers về AVAILABLE + tọa độ seed; không xóa volume, không ghi chéo DB.
- README: mục "7. Seed & smoke demo (T26)" trong runbook local.

| Lệnh | Kết quả |
|---|---|
| `bash scripts/seed-demo.sh` ×2 | run 1: 6 registered; run 2: toàn skip (idempotent) — exit 0 |
| `bash scripts/smoke-trip.sh` ×2 | **SMOKE PASS** ~12 s/lần: SEARCHING→DRIVER_OFFERED(~6s)→ACCEPTED→IN_PROGRESS→COMPLETED, payment SUCCEEDED; notifications + events verify OK (5 services thật qua Nginx :8080) |
| `bash scripts/reset-demo.sh [--confirm]` | không --confirm → exit 2 kế hoạch; --confirm → canceled trip DRIVER_OFFERED f1214ec2… + drivers AVAILABLE |

Chưa commit phần T26. Services local để chạy (pid trong scratchpad t26-logs/). T27 mở khóa (dep T08 + T12 + T20 + T24 + T25 — cả năm DONE).

## Lượt 2026-10-29 — T27: UI passenger — DONE

Chi tiết: [T27](tasks/T27.md).

### Việc đã làm
- `src/web/src/trips/types.ts`, `api/trips.ts`, `hooks/usePolling.ts`, `ui/errorHint.ts` (mới): types/helpers trip, API client trips với Idempotency-Key, hook polling chung có cleanup + stop conditions, hint lỗi tiếng Việt.
- `pages/passenger/{CreateTripForm,TripsListPanel,NotificationsPanel}.tsx`, `pages/TripDetailPage.tsx` (mới): form quote/create (key giữ chỉ sau timeout/mạng → replay không trùng), list 5 s polling + nút Hủy theo state, notifications 10 s, detail 3 s polling dừng khi terminal/NOT_FOUND.
- `PassengerHome.tsx` viết lại (Tabs "Chuyến đi"/"Thông báo"), `App.tsx` thêm route `/passenger/trips/:tripId`, `api/client.ts` export `http()` generic + `extraHeaders` + `ApiError.details`.
- Tests mới: trips/types, usePolling, api/trips, errorHint.

| Lệnh | Kết quả |
|---|---|
| `cd src/web && npm test` | **74 passed (74)** (trước T27/T28 là 43) |
| `cd src/web && npm run build` | tsc + vite pass (warning chunk >500 kB không fatal) |
| Live script qua Nginx :8080 (scratchpad `t27t28-logs/t27-live.sh`) | passenger-1: quote → create 202 (trip `f30ff85c…`) → replay cùng key+payload → **202 cùng tripId** (không trùng) → create key khác khi active → **409 ACTIVE_TRIP_EXISTS** → heartbeat driver-1..3 → poll tới DRIVER_OFFERED (~6 s) → cancel → **202 CANCELLED** → notifications TRIP_OFFERED + TRIP_CANCELLED |

Chưa commit phần T27. Nginx serve dist đã build: GET /passenger, /driver → index.html 200.

## Lượt 2026-10-29 — T28: UI driver — DONE

Chi tiết: [T28](tasks/T28.md).

### Việc đã làm
- `src/web/src/drivers/types.ts`, `api/drivers.ts` (mới): types/helpers driver (`parseBusyDetails` cho details 409 `{state,tripId}`, đếm ngược offer), API client drivers.
- `pages/driver/BusyTripPanel.tsx` (mới) + `DriverHome.tsx` viết lại: boot probe PUT availability với status cache (409 → khôi phục busy state + link chuyến sau reload); profile qua re-POST idempotent; heartbeat 10 s chỉ khi AVAILABLE; poll offer 3 s khi AVAILABLE/RESERVED; accept `{attempt}` reconcile tới ACCEPTED; start/complete disable theo state; recovery loop probe availability sau trip terminal.
- Tests mới: drivers/types, api/drivers.

| Lệnh | Kết quả |
|---|---|
| `cd src/web && npm test` | **74 passed (74)** — gồm tests mới drivers/types, api/drivers |
| `cd src/web && npm run build` | tsc + vite pass |
| Live script qua Nginx :8080 (scratchpad `t27t28-logs/t28-live.sh`) | driver-1: boot probe 200 AVAILABLE → passenger create 202 (trip `2252e5cd…`) → nhận offer (`distanceM=1920`, `fareVnd=22000`) → PUT availability khi RESERVED → **409 details {state:"RESERVED", tripId}** → accept attempt 1 → **202 ACCEPTED** → PUT availability khi BUSY → **409 details {state:"BUSY", tripId}** (cơ chế reload recovery) → start 200 IN_PROGRESS → complete 200 COMPLETED/PENDING → poll paymentStatus → **SUCCEEDED** (~10 s) → notifications driver OFFER_RECEIVED/TRIP_ACCEPTED/TRIP_COMPLETED |

Chưa commit phần T28. Services local để chạy (pid trong scratchpad t27t28-logs/). T29 mở khóa (dep T26 + T27 + T28 — cả ba DONE).

## Lượt 2026-10-30 — T29: Màn hình demo và triển khai đầy đủ — DONE

Chi tiết: [T29](tasks/T29.md).

### Việc đã làm
- `deploy/docker/Dockerfile.backend` (mới): multi-stage Maven build cả 5 module trong một reactor run → image runtime chứa 5 jar + curl + JWT dev keys; `Dockerfile.web` (mới): node alpine build → nginx alpine với dist + nginx.conf baked-in.
- `deploy/compose.yaml`: thêm 5 service backend từ một image chung (command riêng), env DB_HOST/RABBITMQ_HOST/DRIVER_SERVICE_URL, ports publish CHỈ 127.0.0.1, healthcheck readiness, depends_on service_healthy; nginx build từ Dockerfile.web.
- `deploy/nginx.conf`: proxy_pass → DNS compose; `/actuator/*` + `/internal/*` trả 404 tường minh trước SPA fallback.
- 5× `application.yml`: datasource qua `${DB_HOST:localhost}`.
- Frontend: `DemoOverview.tsx` (mới) + route `/demo` (Authed, mọi role); `scripts/correlation-filter.sh` (mới).
- README: mục chính "Chạy toàn hệ thống bằng Docker Compose (T29)".

| Lệnh | Kết quả |
|---|---|
| `docker compose down -v && docker compose --env-file .env -f deploy/compose.yaml up --build -d` | Toàn stack healthy ~15 s, DB mới — Flyway migrate sạch; build backend ~74 s (layer cache ấm) |
| `bash scripts/seed-demo.sh` | PASS |
| `bash scripts/smoke-trip.sh` | **PASS** — SEARCHING→DRIVER_OFFERED(~6 s)→ACCEPTED→IN_PROGRESS→COMPLETED + payment SUCCEEDED (~11 s) |
| `curl http://localhost:8080/actuator/health`, `/actuator/health/readiness`, `/internal/drivers/candidates` | **404** cả ba (route private không lộ qua nginx); health dev links `localhost:808x/actuator/health/readiness` → 200 ×5 |
| `bash scripts/correlation-filter.sh <cid>` | Tìm đúng cid trong log trip-service + nginx access log |
| `cd src/web && npm run build` / `npm test` | pass (warning chunk-size không fatal) / **74/74** |

Lỗi sửa trong lúc kiểm chứng: thiếu `RABBITMQ_HOST=rabbitmq` trong env compose của trip/matching/payment (yml mặc định `localhost` — sai trong container) → smoke run đầu kẹt SEARCHING; thêm biến, recreate 3 container, chạy lại PASS. Trip kẹt cũ tự hồi NO_DRIVER sau broker reconnect (outbox replay hoạt động).

## Lượt 2026-10-30 — T30: Test concurrency hệ thống (C01/C02) — DONE

Chi tiết: [T30](tasks/T30.md).

### Việc đã làm
- `scripts/concurrency-c01.sh` (mới): 2× POST /api/trips đồng thời qua barrier, poll cả 2 trip + đếm DB `drivers.status='RESERVED'` mỗi ~2 s, hội tụ {DRIVER_OFFERED, NO_DRIVER}, cleanup verify driver AVAILABLE.
- `scripts/concurrency-c02.sh` (mới): create → chờ offer → accept `{attempt}` + cancel đồng thời qua barrier với jitter 0–190 ms/piah; assert đúng một 2xx + bên kia 409 STATE_CONFLICT; cleanup theo winner (accept: start+complete; cancel: poll CANCELLED), verify driver AVAILABLE.
- Raw results: `tests/concurrency/c01-results.jsonl`, `c01-snapshots.jsonl`, `c01-summary.txt`, `c02-results.jsonl`, `c02-summary.txt`.

| Lệnh | Kết quả |
|---|---|
| `bash scripts/concurrency-c01.sh` (20 lượt) | **PASS** — ok=20 fail=0, offers_seen 20/20; max_concurrent_offered=1, max_db_reserved=1 trên 120 snapshot; final `(DRIVER_OFFERED, NO_DRIVER)` mọi lượt (~517 s) |
| `bash scripts/concurrency-c02.sh` (8 lượt) | **PASS** — ok=8 fail=0, accept_wins=3/cancel_wins=5; mỗi race một 2xx + loser 409 STATE_CONFLICT; driver AVAILABLE cuối mọi lượt (~61 s) |

Lỗi sửa trong lúc kiểm chứng: `cancel_nonterminal` (script test) gọi wait cho cả trip đã terminal → false fail ở reset lượt 2 run đầu C02; sửa chỉ wait sau cancel/CANCELLING thật. Không có lỗi product trong phạm vi T30.

## Lượt 2026-10-30 — T31: Test failure/recovery (R01/R02) — DONE

Chi tiết: [T31](tasks/T31.md).

### Việc đã làm
- `scripts/recovery-r01.sh` (mới): Phase A `docker stop` matching → create trip → verify `matching.inbox depth ≥ 1` → start lại trong deadline 120 s → DRIVER_OFFERED; Phase B quá deadline → NO_DRIVER + audit log `stale_event_skipped`, driver AVAILABLE.
- `scripts/recovery-r02.sh` (mới): full flow với failpoint test-only `APP_INBOX_FAILPOINT_DELAY_MS=8000` (hook giữa DB commit và ACK trong `PaymentInboxListener.sleepFailpoint()`) bật qua compose override file tạm; kill payment-service đúng cửa sổ lỗi → verify redelivery pending trong queue → restart → dedup `inbox_duplicate`, payments rows=1, SUCCEEDED.
- Raw results: `tests/recovery/r01-results.jsonl`, `r01-summary.txt`, `r02-results.jsonl`, `r02-summary.txt`.

| Lệnh | Kết quả |
|---|---|
| `bash scripts/recovery-r01.sh` | **PASS** — Phase A ~16 s (depth=1 → DRIVER_OFFERED, cleanup CANCELLED); Phase B ~132 s (NO_DRIVER + log stale_event_skipped đầy đủ) |
| `bash scripts/recovery-r02.sh` | **PASS** — ~38 s; payments rows=1, inbox_events=1, SUCCEEDED, redelivery pending trước restart, dedup log inbox_duplicate deliveryAttempt=1 |

Lỗi sửa trong lúc kiểm chứng (lỗi script test): heartbeat stale đúng lúc reserve → fresh heartbeat + re-affirm AVAILABLE ngay trước create_trip; accept yêu cầu body `{"attempt":N}` và start/complete trả 200 (không 202); block accept/start/complete cũ sót sau thay thế awk trong r02 (xóa dòng 282–291); cancel chấp nhận cả 200|202 theo contract §3.3. Không có lỗi product trong phạm vi T31.

## Lượt 2026-10-30 — T32: Security và input tests (S01/S02) — DONE

Chi tiết: [T32](tasks/T32.md).

### Việc đã làm
- `scripts/security-s01.sh` (mới): 9 probe authn/authz (no/garbage token, role sai ×4, owner lạ ×2, login sai mật khẩu) + snapshot trips/drivers trước/sau.
- `scripts/security-s02.sh` (mới): 7 probe input/payload/transition (tọa độ sai ×3, body ~200 KiB, transition sai ×3) + luồng positive COMPLETED + assertions DB bad_rows=0, total trips chỉ +1, completed +1; B1 qua `curl -d @file` (argv limit Git Bash).
- Raw results: `tests/security/s01-results.jsonl`, `s01-summary.txt`, `s02-results.jsonl`, `s02-summary.txt`.

| Lệnh | Kết quả |
|---|---|
| `bash scripts/security-s01.sh` | **PASS** — 9/9 probe (P1/P2 401 UNAUTHORIZED, P3–P6 403 FORBIDDEN, P7/P8 404 NOT_FOUND, P9 401 INVALID_CREDENTIALS); snapshot dữ liệu trước=sau (~11 s) |
| `bash scripts/security-s02.sh` | **PASS** — 7/7 probe (Q1/C1/C2 400 INVALID_INPUT, B1 413 PAYLOAD_TOO_LARGE, T1/T3 409 STATE_CONFLICT, T2 409 NOT_OFFERED_DRIVER); positive trip COMPLETED; bad_rows=0, total trips chỉ +1 (70→71), completed +1 (17→18) (~15 s) |

Lỗi sửa trong lúc kiểm chứng (lỗi script test): B1 argv limit → `post_create_file()` với `-d @file`; block accept/start/complete cũ sót sau thay thế awk trong s02 — call accept không body trả 400 "Malformed request body" (xóa dòng 309–319); kỳ vọng P9 sửa thành INVALID_CREDENTIALS theo contract §3.1. Không có lỗi product trong phạm vi T32.

## Lượt 2026-10-30 — Rà soát và sửa T27–T32

Chi tiết: [T27-T32_REVIEW_FIXES](T27-T32_REVIEW_FIXES.md).

| Lệnh | Kết quả |
|---|---|
| `./mvnw -B clean verify` | **BUILD SUCCESS, 375 tests / 0 failures** (identity 38, driver 123, trip 155, matching 26, payment 33) |
| `npm test` + `npm run build` | **98/98**, build pass |
| `node tests/e2e/ui-flow.cjs` | **13/13** trên headless Chrome qua Nginx :8080 |
| `bash scripts/concurrency-c01.sh` | **20/20**; 0 reservation chồng nhau, 0 orphan; A/B thắng 17/3 |
| `bash scripts/concurrency-c02.sh` | **20/20**; accept/cancel thắng 14/6; 9 lượt chồng nhau |
| `bash scripts/recovery-r01.sh` | **PASS** (lần đầu fail do heartbeat quá hạn trong script, đã sửa) |
| `bash scripts/recovery-r02.sh` | **PASS** |
| `bash scripts/security-s01.sh` / `security-s02.sh` | **17/17** / **7/7** |
| Bản sao sạch (`build --no-cache`, key + `.env` mới, volume riêng) | **PASS** sau khi sửa lỗi `COPY src/web/public/` |

## Lượt 2026-10-30 — T33: Load experiment (k6 P01) — DONE

Chi tiết: [T33](tasks/T33.md). `bash scripts/load-p01.sh` chạy đủ 6 cấu hình trên commit `0c24f96`; raw + `summary.md` tại `tests/performance/results/`.

| Dataset / hành khách | NO_DRIVER | POST 202 p50/p95 (ms) | create→offered p50/p95 (ms) | Lỗi kỹ thuật | Gán trùng |
|---|---|---|---|---|---|
| enough 10 / 30 / 50 | 0% | 6/13, 6/18, 5/51 | 2022/8055, 3024/9103, 5536/10651 | 0 | 0 |
| scarce 10 / 30 / 50 (giữ tài xế 10 s) | 76% / 69,3% / 64% | 7/11, 8/40, 6/66 | 1519/13623, 8570/13624, 10580/15237 | 0 | 0 |

Sửa trong lúc kiểm chứng (lỗi kịch bản đo, không phải sản phẩm): throughput từng tính cả thời gian setup; dataset thiếu tài xế từng không giữ tài xế nên không có contention; k6 ghi nối vào file raw làm lẫn lượt trước.

## Lượt 2026-10-31 — T34: CI và clean deploy — DONE

Chi tiết: [T34](tasks/T34.md). Chưa chạy trên GitHub vì repo chưa có remote.

| Lệnh | Kết quả |
|---|---|
| `./mvnw -B clean verify` + `ci-test-report.sh` (Linux) | 375 test, 0 failure/error/skip |
| `npm ci && npm test && npm run build` (Linux) | 98/98, build pass |
| `ci-system-check.sh --keep` (Linux, uid 1001) | PASS: healthy 124 s, seed, smoke, S01, S02, route private 404 |
| `tests/e2e/ui-flow.cjs` (Linux, Chromium) | 13/13 |
| `clean-deploy-check.sh --confirm` (Windows) | PASS; 2885 chuyến trước = sau |
| Test khi không có Docker | Error, mvn exit 1, report fail (không skip) |
| actionlint, `scan-tracked-secrets.sh` | exit 0; secret giả bị phát hiện |

Lỗi sửa khi kiểm chứng: build song song cùng tag fail trên Linux; `mvnw`/script thiếu bit thực thi; máy quét secret luôn PASS do nuốt lỗi `git grep`; bản đầu `clean-deploy-check.sh` khôi phục sai project (volume gốc không mất).

