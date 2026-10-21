# Danh sách cần sửa — phạm vi T01–T12

Lập ngày 21/10/2026 từ [rà soát 20/10](FULL_PROJECT_REVIEW_2026-10-20.md) và [rà soát 21/10](FULL_PROJECT_REVIEW_2026-10-21.md).

## Phạm vi

File này **chỉ gồm vấn đề nằm trong phần T01–T12 đã làm**: code, cấu hình, script, migration, test và contract (T05) hiện có.

**Không đưa vào:**
- Task T13–T36 chưa làm.
- Deliverable thuộc task sau: script seed/smoke/demo/replay/reset (T26), Dockerfile và container hóa service (T29), CI (T34), report/slides/test-results (T35).
- N39 (tiến độ chung), N40 (script quét ranh giới, thuộc T32/T36), Q05 (test cấp hệ thống, cần T13+), Q06 (load test/CI/SCA).

**Cách dùng:**
- Mỗi mục có ID gốc (F/R/Q/N trong hai bản rà soát), mức ưu tiên, vị trí, cách sửa đề xuất và cách kiểm chứng. Đánh dấu `[x]` khi đã sửa **và** có test hoặc probe chứng minh.
- **P1** là lỗ hổng bảo mật hoặc điều kiện đạt môn. **P2** ảnh hưởng tính đúng, an toàn hoặc khả năng phục hồi. **P3** là trường hợp biên, chẩn đoán hoặc độ nhất quán.
- Mỗi lỗi chỉ ghi một lần, ở task gây ra nó. Lỗi lặp ở nhiều service ghi ở task tạo ra mẫu code đó, kèm danh sách file bị ảnh hưởng.

## Tổng hợp

| Nhóm | Số mục | P1 | P2 | P3 |
|---|---:|---:|---:|---:|
| Chung / quy trình (T01–T02) | 6 | 1 | 2 | 3 |
| T03 PostgreSQL | 4 | 0 | 1 | 3 |
| T04 RabbitMQ | 3 | 0 | 3 | 0 |
| T05 Contract | 12 | 0 | 3 | 9 |
| T06 Identity | 4 | 0 | 3 | 1 |
| T07 Security + log | 15 | 1 | 8 | 6 |
| T08 Frontend + Nginx | 17 | 0 | 7 | 10 |
| T09 Driver profile | 6 | 0 | 2 | 4 |
| T10 Reserve | 5 | 0 | 3 | 2 |
| T11 Confirm/release/finish | 3 | 0 | 2 | 1 |
| T12 Trip (+ cổng test) | 7 | 0 | 1 | 6 |
| **Tổng** | **82** | **2** | **35** | **45** |

## Thứ tự sửa khuyến nghị

1. **C01–C03:** git repo, cấu trúc `src/`/`tests/`, ghi blocker vào handoff. Làm trước tiên để các commit sửa lỗi sau đó có lịch sử và đúng vị trí.
2. **T07 bảo mật:** F01 + N10 (cùng một lần sửa), N02, F02, F03, F04; T08 N01, N04.
3. **T05 contract:** chốt N15, N21–N24, F31 trước khi bắt đầu T13.
4. **T07 log:** F20, F21, N11, N12, N13.
5. **T09–T12 tính đúng dữ liệu và thời gian:** F06–F14, N29–N31, R04.
6. **Hạ tầng và script:** T03, T04, T08 (N14, N16), F25–F29.
7. **Frontend:** F15–F19, N34.
8. **P3 còn lại.**

---

## Chung / quy trình (T01–T02)

- [x] **C01 — N35 (P1): Workspace không phải git repository**
  - Vị trí: root (không có `.git`).
  - Vấn đề: T01–T12 đã DONE nhưng chưa có commit nào. Đề §10.1 bắt buộc lịch sử commit của từng thành viên; đề §12 ghi "không chứng minh được đóng góp" là không đạt.
  - Sửa: `git init` (hoặc clone repo GitHub có sẵn rồi chép mã vào); kiểm tra `.gitignore` đã chặn `.env`, `keys/`, `*.pem`, `src/web/dist`, `target/` **trước** lần `git add` đầu tiên. Từ đây mỗi thành viên commit phần mình sửa bằng tài khoản của chính họ. Không dựng lại lịch sử giả (spec §1).
  - Kiểm chứng: `git status` không liệt kê `.env` hay `keys/*.pem`; `git log` có commit thật.

- [x] **C02 — N36 (P2): Thiếu `src/` và `tests/` mà đề bắt buộc; README ghi sai**
  - Vị trí: root; `README.md` mục "Cấu trúc repository"; root `pom.xml` `<modules>`.
  - Vấn đề: 5 service và `web/` nằm ở root. Đề §10.1 bắt buộc có `src/` và `tests/`. Spec §16 đặt `deploy/nginx.conf` và `deploy/postgres/init/` (hiện ở `deploy/nginx/` và `infra/postgres/init/`). README lại ghi "`src/` chứa 5 backend modules".
  - Sửa: chuyển sang `src/<service>/` và `src/web/`; cập nhật `<module>src/identity-service</module>`…, đường dẫn bind mount trong `deploy/compose.yaml`, script `infra/rabbitmq/*`, README và docs. Tạo `tests/` (ít nhất `tests/README.md` mô tả test nằm trong từng module và test hệ thống sẽ đặt ở đây). Làm trong một commit riêng trước khi sửa lỗi khác.
  - Kiểm chứng: `./mvnw -B clean verify` vẫn 130/0; `npm run build` trong `src/web`; `docker compose config --quiet`; Nginx vẫn phục vụ được build.

- [x] **C03 — N37 (P2): Handoff/backlog không nhắc tới các bản rà soát; ghi "không có blocker"**
  - Vị trí: `docs/handoff.md` mục "Task kế tiếp"; `docs/backlog.md`; `docs/progress.md`.
  - Sửa: thêm mục "Blocker trước T13" trỏ tới file này. Ghi rõ T13 chỉ bắt đầu sau khi xong F01/N10 và các mục contract T05 (N15, N21–N24, F31). Cột Blocker của T13/T14/T16 ghi các ID tương ứng.
  - Kiểm chứng: một người đọc handoff sẽ thấy ngay danh sách blocker.

- [ ] **C04 — N38 (P3): Stack thực tế lệch spec §4 mà không ghi lý do**
  - Vị trí: `README.md` mục Stack; các `pom.xml`.
  - Vấn đề: README ghi "RabbitMQ + Spring AMQP (từ T04)" nhưng chưa POM nào có `spring-boot-starter-amqp`. Chưa có OpenAPI. Chỉ Identity dùng JPA (các service còn lại dùng JdbcTemplate). Spring Security mới chỉ dùng phần crypto.
  - Sửa: sửa README cho đúng hiện trạng; thêm một mục "Lệch đặc tả có chủ đích" (JdbcTemplate thay JPA, lý do). Tạo OpenAPI (springdoc hoặc file `docs/openapi.yaml`) cho các endpoint đã có của T06/T09/T12.
  - Kiểm chứng: README khớp các POM; có OpenAPI cho Identity/Driver/Trip.

- [x] **C05 — N19 (P3): Line ending lẫn lộn, Node chưa pin**
  - Vị trí: root (thiếu `.gitattributes`); `src/web/package.json` (thiếu `engines`); không có `.nvmrc`.
  - Vấn đề: `pom.xml`, `nginx.conf`, `generate-dev-keys.sh` dùng CRLF, các file khác dùng LF. Đây là nguyên nhân gốc của F25. Spec §4 yêu cầu pin Node.
  - Sửa: thêm `.gitattributes` (`* text=auto eol=lf`, `*.cmd text eol=crlf`) rồi normalize; thêm `"engines": {"node": "24.x"}` và `.nvmrc` theo phiên bản đã dùng (đang là 24.18.0).
  - Kiểm chứng: `file scripts/*.sh infra/**/*.sh` không còn CRLF; `npm ci` báo lỗi khi sai phiên bản Node.
  - ✅ Đã làm + kiểm chứng (Phase 6): thêm `.gitattributes` (`* text=auto eol=lf`, `*.cmd`/`*.bat` = crlf); normalize 163 file CRLF → LF (chỉ `mvnw.cmd` giữ CRLF — grep xác nhận không còn CRLF nào khác); `"engines": {"node": "24.x"}` trong `src/web/package.json` + `.nvmrc` = 24.18.0; `generate-dev-keys.sh` chạy end-to-end trên Linux qua `docker run ubuntu:24.04` (openssl cài in-container) → exit 0.

- [ ] **C06 — N17 (P3): Không retry khi khởi động, chưa có liveness/readiness**
  - Vị trí: `*/config/FlywayConfig.java`; `*/application.yml` (5 service).
  - Vấn đề: DB chưa sẵn sàng là service dừng ngay (Flyway migrate chạy trong lúc khởi tạo bean). Probe liveness/readiness chưa bật; timeout kết nối DB để mặc định 30 s. Spec §5 và §12 yêu cầu startup retry thật và kiểm tra dependency có timeout.
  - Sửa: bật `management.endpoint.health.probes.enabled=true`; đặt `spring.datasource.hikari.connection-timeout` (ví dụ 2000 ms) và `initialization-fail-timeout`; thêm retry có giới hạn quanh `flyway.migrate()` (hoặc `restart: on-failure` khi container hóa ở T29, kèm ghi chú).
  - Kiểm chứng: khởi động service khi Postgres đang tắt, bật Postgres trong 30 s → service tự lên; `/actuator/health/readiness` trả DOWN khi DB tắt và UP khi DB lên.

---

## T03 — PostgreSQL

- [x] **T03-1 — F27 (P2): Mật khẩu DB nối thẳng vào SQL literal**
  - Vị trí: `infra/postgres/init/01-create-databases.sh` dòng 8, 13, 18, 23, 28.
  - Vấn đề: mật khẩu có dấu `'` làm script init lỗi, volume đã khởi tạo nhưng thiếu DB/user.
  - Sửa: truyền qua biến psql và dùng quoting của psql: `psql -v pw="$IDENTITY_DB_PASSWORD" ...` với `CREATE USER identity_svc WITH PASSWORD :'pw';`. Không giới hạn ký tự mật khẩu để né lỗi.
  - Kiểm chứng: container disposable với mật khẩu chứa `'`, `"`, `$`, khoảng trắng → 5 user/DB được tạo, đăng nhập được.
  - ✅ Đã làm + kiểm chứng (Phase 6): script viết lại bằng `psql -v VAR=...` + `:'VAR'`; container `postgres:17.11-alpine` disposable với 5 mật khẩu chứa `'`, `"`, `$`, khoảng trắng → "T03: 5 DB + 5 users created", cả 5 service login trả về 1.

- [x] **T03-2 — N09 (P3): Mật khẩu DB hard-code làm giá trị mặc định**
  - Vị trí: `*/src/main/resources/application.yml` (`${IDENTITY_DB_PASSWORD:identity_dev_2026}`…).
  - Vấn đề: thiếu env thì service âm thầm dùng mật khẩu mặc định. Trái spec §11 và §12.
  - Sửa: bỏ giá trị mặc định (`${IDENTITY_DB_PASSWORD}`), để service dừng với thông báo rõ khi thiếu env; cập nhật README cách nạp env khi chạy jar.
  - Kiểm chứng: chạy jar không set env → service dừng, log nêu tên biến thiếu (không in giá trị).
  - ✅ Đã làm + kiểm chứng (Phase 6): bỏ default trong cả 5 `application.yml`; thêm `RequiredDbPasswordValidator` (BeanFactoryPostProcessor @Component x5) fail-fast trước khi tạo DataSource. Chạy `identity-service-0.1.0.jar` không set env → exit 1, log: "Missing required environment variable 'IDENTITY_DB_PASSWORD'" (không in giá trị). Test H2 probe/Testcontainers set password trực tiếp nên không bị chặn; `mvnw clean verify` xanh.

- [x] **T03-3 — N18 (P3): Healthcheck Postgres có thể báo healthy khi init chưa xong**
  - Vị trí: `deploy/compose.yaml` healthcheck postgres.
  - Sửa: kiểm tra qua TCP và chính tài khoản service, ví dụ `pg_isready -h 127.0.0.1 -U $${POSTGRES_USER}` kết hợp `psql -h 127.0.0.1 ... -c 'select 1' -d payment_db` (DB tạo cuối cùng).
  - Kiểm chứng: xóa volume, `up -d`, theo dõi `docker inspect` → chỉ healthy sau khi log init in "5 DB + 5 users created".
  - ✅ Đã làm + kiểm chứng (Phase 6): healthcheck = `pg_isready -U $${POSTGRES_USER}` && `PGPASSWORD=$$PAYMENT_DB_PASSWORD psql -U payment_svc -d payment_db -c 'select 1'`; sau `down -v` → `up -d`, `docker inspect` healthy và log init có dòng "T03: 5 DB + 5 users created".

- [x] **T03-4 — F32 (P3): Docker image tag trôi**
  - Vị trí: `deploy/compose.yaml` (postgres, rabbitmq, nginx); `infra/rabbitmq/declare-topology.sh`; tất cả `new PostgreSQLContainer("postgres:17-alpine")` trong test.
  - Vấn đề: `postgres:17-alpine`, `rabbitmq:3-management-alpine`, `nginx:1.27-alpine` đổi theo bản vá. Escript lại chép record từ RabbitMQ 3.13.7.
  - Sửa: pin phiên bản patch đầy đủ (tốt nhất kèm digest), cùng một tag cho Compose và Testcontainers; RabbitMQ pin đúng bản khớp với record đã chép (hoặc nâng bản và cập nhật record).
  - Kiểm chứng: `docker compose config` hiển thị tag đầy đủ; build/test vẫn pass.
  - ✅ Đã làm + kiểm chứng (Phase 6): pin `postgres:17.11-alpine`, `rabbitmq:3.13.7-management-alpine` (khớp record framing chép trong escript — xác nhận bằng `rabbitmqctl version` = 3.13.7 trên broker đang chạy), `nginx:1.27.5-alpine`; cập nhật compose (kể cả service topo-declare mới) + cả 7 class Testcontainers; `docker compose config` hiển thị tag pin, `mvnw clean verify` xanh.

---

## T04 — RabbitMQ

- [x] **T04-1 — F28 (P2): Credential không percent-encode trong AMQP URI**
  - Vị trí: `infra/rabbitmq/declare_topology.es:31`.
  - Sửa: không dựng URI; tạo record `#amqp_params_network{username=…, password=…, host="rabbitmq"}` trực tiếp từ env (hoặc percent-encode user/pass).
  - Kiểm chứng: mật khẩu có `#`, `@`, `:`, `/` → declare thành công.
  - ✅ Đã làm + kiểm chứng (Phase 6): escript không parse URI — dựng record `#amqp_params_network{}` trực tiếp từ env (+ helper `env_binary`; record definition chép từ amqp_client.hrl). Broker test với user `u#x`, password `p@ss:w/ord#` → "T04: topology declared over AMQP"; 6 queue durable hiện trong rabbitmqctl. (Phase 6 bổ sung: xử lý `RABBITMQ_HOST=false` mặc định của image + retry transient — xem T04-3.)

- [x] **T04-2 — F29 (P2): Script `source .env` khác cách Compose đọc dotenv**
  - Vị trí: `infra/rabbitmq/declare-topology.sh:16`.
  - Sửa: không `source`; đọc giá trị qua `docker compose --env-file .env -f deploy/compose.yaml config` hoặc parser dotenv đơn giản (chỉ `KEY=VALUE`, không thực thi). Hoặc truyền `--env-file .env` cho `docker run`.
  - Kiểm chứng: `.env` có mật khẩu chứa khoảng trắng không quote (Compose chấp nhận) → script vẫn declare được.
  - ✅ Đã làm + kiểm chứng (Phase 6): `load_dotenv()` parser KEY=VALUE thuần bash (không thực thi shell; bỏ CRLF, skip comment/trống, trim key, strip một cặp quote). Kiểm chứng bằng source hàm với `.env` tạm chứa giá trị không quote có khoảng trắng, `#`, quoted value, dòng CRLF → parse đúng.

- [x] **T04-3 — N15 phần topology (P2): Topology chỉ khai báo bằng script chạy tay**
  - Vị trí: `infra/rabbitmq/*`; README bước 2.
  - Vấn đề: reset volume broker mà quên chạy script thì service không có exchange/queue. Nếu exchange có nhưng thiếu binding, publish với confirms vẫn được broker ack và message bị bỏ.
  - Sửa (phần T04): ghi rõ trong README/compose rằng script là bắt buộc sau mỗi lần reset; thêm bước chạy script tự động (service one-shot trong Compose, `depends_on` rabbitmq healthy). Phần contract (`mandatory` + returns) xem T05-2.
  - Kiểm chứng: `docker compose down -v && up -d` → queue và binding tồn tại mà không cần chạy tay.
  - ✅ Đã làm + kiểm chứng (Phase 6): service one-shot `topo-declare` trong compose (`rabbitmq:3.13.7-management-alpine`, `depends_on: rabbitmq condition: service_healthy`, `restart: "no"`). Phát hiện và sửa 2 bug khi verify thật: (1) image rabbitmq mặc định đặt env `RABBITMQ_HOST=false` → escript fallback sai host; (2) healthcheck ping pass trước khi listener AMQP sẵn sàng → thêm `nc -z 127.0.0.1 5672` vào healthcheck + retry transient trong escript. Sau `down -v` → `up -d`: topo-declare exit 0, log "T04: topology declared over AMQP (host=rabbitmq)", cả 6 queue durable (matching/trip/payment inbox + dlq) tồn tại không chạy script tay.

---

## T05 — Contract (api-contracts.md, events.md, state-machines.md)

Tiêu chí DONE của T05 là "không còn lựa chọn mơ hồ". Các mục dưới đây là chỗ contract còn thiếu hoặc mâu thuẫn; chỉ cần sửa tài liệu, chưa cần code.

- [x] **T05-1 — F31 (P2): Cancel yêu cầu "publish event hủy matching" nhưng event không tồn tại**
  - Vị trí: `docs/api-contracts.md:219`; `docs/events.md` §3; CHECK `event_type` trong `trip-service/.../V2__trips.sql`.
  - Sửa: chọn một trong hai hướng và ghi rõ. (a) Bỏ câu "publish event hủy"; Trip bỏ qua `DriverProposed`/`NoDriverFound` tới sau khi trip đã terminal (ACK + log). (b) Thêm event `TripCancelled` (routing key, payload, binding `matching.inbox`, consumer) và migration mở rộng CHECK. Hướng (a) gọn hơn cho MVP.
  - Kiểm chứng: api-contracts, events và state-machines nói cùng một điều.
  - ✅ Đã làm (2026-10-21): chọn hướng (a) — bỏ câu "publish event hủy" ở api-contracts §3.3 cancel; state-machines row `SEARCHING→CANCELLED` ghi rõ không publish, event attempt cũ bị bỏ qua ACK+log theo events.md §4.4; events.md §3.2 thêm note không có event "hủy matching" trong catalog. Ba tài liệu nói cùng một điều.

- [x] **T05-2 — N15 phần contract (P2): Thiếu quy tắc `mandatory` + returns cho publisher**
  - Vị trí: `docs/events.md` §1 và §4.6.
  - Sửa: thêm quy tắc publisher dùng `mandatory=true` + ReturnsCallback; message bị trả về thì outbox giữ PENDING và ghi `last_error`, không đánh dấu SENT. Service tự declare topology khi khởi động (Spring AMQP `Declarables`), script chỉ còn là công cụ phụ.
  - Kiểm chứng: tài liệu nêu rõ hành vi khi gặp message không route được.
  - ✅ Đã làm (2026-10-21): events.md §1 thêm quy tắc publisher `mandatory=true` + ReturnsCallback; message returned → outbox giữ PENDING + ghi `last_error`, không đánh dấu SENT, retry vòng quét kế tiếp; service declare topology khi khởi động (Spring AMQP declarables), script chỉ là công cụ phụ. §4 rule 6 cập nhật tương ứng.

- [x] **T05-3 — N21 (P2): Chưa định nghĩa hành vi khi NoDriverFound hoặc hết candidate mà còn lượt attempt**
  - Vị trí: `docs/state-machines.md` §1–2; `docs/events.md` §3.2–3.3.
  - Sửa: thêm transition `SEARCHING → SEARCHING` với điều kiện cụ thể, ví dụ: nhận NoDriverFound hoặc hết candidate → attempt+1, `next_retry_at = now + MATCHING_RETRY_DELAY` (env, ví dụ 5 s), phát `TripRequested` attempt mới qua outbox, dừng khi attempt > `MAX_MATCHING_ATTEMPTS` hoặc quá deadline → NO_DRIVER. Ghi rõ excluded list có giữ hay không với NoDriverFound.
  - Kiểm chứng: không còn nhánh "do Saga điều khiển" mà không nêu cụ thể.
  - ✅ Đã làm (2026-10-21): state-machines.md §1 thêm row `SEARCHING→SEARCHING`: NoDriverFound đúng attempt hoặc hết candidate chưa giữ chỗ → attempt+1, `next_retry_at = now + MATCHING_RETRY_DELAY_MS` (env default 5000 ms), outbox TripRequested attempt mới, excluded list giữ nguyên với NoDriverFound; dừng khi attempt > MAX_MATCHING_ATTEMPTS hoặc quá deadline → NO_DRIVER. events.md §3.2/§3.3 cập nhật cùng hành vi; api-contracts §6 thêm env `MATCHING_RETRY_DELAY_MS`.

- [x] **T05-4 — N22 (P3): Mâu thuẫn về thời điểm ghi `trips.driver_id`**
  - Vị trí: `docs/state-machines.md` §6 invariant 2 ("set khi ACCEPTED"); `docs/api-contracts.md` §3.3 (ví dụ DRIVER_OFFERED có `driverId`); `docs/handoff.md`.
  - Sửa: chốt một cách. Gợi ý: ghi `driver_id`/`driver_user_id` khi DRIVER_OFFERED để driver được offer xem được trip, xóa về NULL khi reject/expire; invariant 2 diễn đạt lại theo "assignment hiệu lực". Cập nhật cả ba tài liệu.
  - ✅ Đã làm (2026-10-21): chốt ghi `trips.driver_id`/`driver_user_id` khi `DRIVER_OFFERED`, xóa về NULL khi reject/expire; invariant 2 diễn đạt lại theo "assignment hiệu lực" (từ ACCEPTED). Cập nhật: state-machines.md §6 + row DRIVER_OFFERED→SEARCHING, api-contracts.md §3.3 (list + detail), handoff.md dòng T16.

- [x] **T05-5 — N23 (P3): `x-dlq-reason` không thể thêm khi reject message**
  - Vị trí: `docs/events.md` §2 và §6.
  - Sửa: ghi rõ consumer gặp event malformed hoặc unsupported thì **republish** sang `ride.events.dlx` (routing key = tên DLQ) kèm header `x-dlq-reason`, rồi ACK message gốc. Lỗi transient hết retry mới dùng `reject(requeue=false)` (lúc đó chỉ có `x-death`).
  - ✅ Đã làm (2026-10-21): events.md §2 — consumer gặp malformed/unsupported → **republish** sang `ride.events.dlx` với routing key = tên DLQ + header `x-dlq-reason`, rồi ACK message gốc; ghi rõ không dùng reject cho trường hợp này (broker chỉ thêm được x-death). §4 rule 3 và §6 cập nhật hai đường vào DLQ.

- [x] **T05-6 — N24 (P3): Thiếu quy tắc release mọi reservationId bị bỏ**
  - Vị trí: `docs/api-contracts.md` §4.1; `docs/state-machines.md` §2.
  - Vấn đề: Driver chỉ lưu response của command thành công. Cùng `commandId` có thể nhận 409 lần đầu và 201 lần sau, nên retry trễ có thể giữ chỗ cho reservation Trip đã bỏ.
  - Sửa: ghi quy tắc: Trip **phải** gửi release (tạo tombstone) cho mọi `reservationId` đã cấp mà không dùng nữa, kể cả sau khi nhận 409/timeout; không tái dùng `commandId` cho thao tác khác. Ghi rõ command lỗi không được lưu để replay.
  - ✅ Đã làm (2026-10-21): api-contracts.md §4.1 thêm chốt N24 — Trip phải gửi release (tombstone nếu tới trước) cho mọi reservationId đã cấp mà bỏ, kể cả sau 409/timeout; không tái dùng commandId cho thao tác khác; command lỗi không lưu để replay. state-machines.md §2 quy tắc worker cập nhật tương ứng.

- [ ] **T05-7 — N25 (P3): Chưa có cơ chế kích hoạt retry thanh toán**
  - Vị trí: `docs/state-machines.md` §5; `docs/api-contracts.md` §3.
  - Sửa: ghi rõ MVP không có retry nghiệp vụ (FAILED là trạng thái cuối, spec §15 cho phép), hoặc định nghĩa endpoint/worker cấp attempt mới.

- [ ] **T05-8 — N26 (P3): Tên field event không thống nhất**
  - Vị trí: `docs/events.md` §3.4–3.6.
  - Sửa: thống nhất `paymentAttempt` hoặc `attempt` cho cả ba event; `failureCode` hoặc `reason` (spec §9 dùng `reason`).

- [ ] **T05-9 — N27 (P3): Passenger không biết thông tin tài xế**
  - Vị trí: `docs/api-contracts.md` §3.3; `docs/events.md` §3.2; §4.2 candidates.
  - Sửa: thêm `displayName`, `plateNumber`, `vehicleType` vào candidate (Driver → Matching → Trip), Trip lưu snapshot khi offer và trả trong GET detail. Hoặc ghi rõ MVP không hiển thị.

- [ ] **T05-10 — N28 (P3): Chưa chốt giới hạn số chuyến active của một passenger**
  - Vị trí: `docs/api-contracts.md` §3.3 POST /api/trips.
  - Sửa: chốt "mỗi passenger tối đa một chuyến chưa terminal → 409 `ACTIVE_TRIP_EXISTS`" (khuyến nghị; cài đặt ở T12-6), hoặc ghi rõ cho phép nhiều chuyến.

- [ ] **T05-11 — N06 phần contract (P3): Bảng mã lỗi không có mã cho rate limit**
  - Vị trí: `docs/api-contracts.md` §1.2.
  - Sửa: thêm `429 RATE_LIMITED` (cài đặt ở T08-6).

- [ ] **T05-12 — N07 (P3): Một internal token dùng chung, không phân quyền theo caller**
  - Vị trí: `docs/api-contracts.md` §2 và quyết định #8.
  - Sửa: dùng token riêng cho từng caller (`INTERNAL_TOKEN_TRIP`, `INTERNAL_TOKEN_MATCHING`); Driver cho phép Matching chỉ gọi `GET /internal/drivers/candidates`, còn Trip gọi được reservation. Hoặc ghi rõ là rủi ro chấp nhận trong báo cáo.

---

## T06 — Identity

- [ ] **T06-1 — F05 (P2): Ký tự U+0000 qua validation, PostgreSQL lỗi → 500**
  - Vị trí: `identity-service/.../service/UserService.java:56` (fullName); áp dụng tương tự cho Driver `DriverService.validateProfileInput` (displayName, plateNumber).
  - Sửa: từ chối ký tự điều khiển (`\p{Cc}`, đặc biệt `\u0000`) trong mọi field text → 400 `INVALID_INPUT`.
  - Kiểm chứng: test register với `"fullName":"ab\u0000c"` → 400, DB không đổi; tương tự cho driver profile.

- [x] **T06-2 — F25 (P2): `generate-dev-keys.sh` dùng CRLF**
  - Vị trí: `scripts/generate-dev-keys.sh` (toàn file).
  - Sửa: chuyển sang LF (kèm C05 `.gitattributes`).
  - Kiểm chứng: `docker run --rm -v "$PWD:/w" -w /w bash:5 bash scripts/generate-dev-keys.sh` chạy được trên Linux.
  - ✅ Đã làm + kiểm chứng (Phase 6): file chuyển sang LF (kèm C05); chạy end-to-end trên Linux qua container `ubuntu:24.04` → "Keys already exist", exit 0.

- [x] **T06-3 — F26 (P2): Thiếu public key nhưng còn private → script tạo private mới**
  - Vị trí: `scripts/generate-dev-keys.sh:20,25`.
  - Sửa: nếu private có mà public thiếu thì tạo lại public từ private (`openssl pkey -in priv -pubout`); chỉ tạo private mới khi có `--force`. Thêm kiểm tra cặp key khớp lúc khởi động Identity (ký thử rồi verify) để phát hiện cấu hình lệch.
  - Kiểm chứng: xóa public, chạy script → fingerprint private không đổi; token cũ vẫn verify được.
  - ✅ Đã làm + kiểm chứng (Phase 6): script tạo lại public từ private (`openssl pkey -pubout`) khi public thiếu, không ghi đè private trừ `--force`; `JwtService` thêm `verifyKeyPair()` lúc khởi động (ký probe RS256 bằng private, verify bằng public; lệch → IllegalStateException). Kiểm chứng: xóa `keys/jwt-public.pem`, chạy script → public tái tạo byte-identical với backup (cmp + sha256), fingerprint private không đổi. Test `JwtServiceKeyPairCheckTest` 2/2 trong `mvnw clean verify`.

- [ ] **T06-4 — R03 (P3): Login bỏ qua BCrypt khi email không tồn tại**
  - Vị trí: `identity-service/.../service/UserService.java:78–80`.
  - Sửa: khi không tìm thấy user, vẫn gọi `passwordEncoder.matches(password, DUMMY_HASH)` để thời gian xử lý tương đương. Ghi chú trong báo cáo rằng register vốn đã lộ EMAIL_TAKEN.
  - Kiểm chứng: đo 20 lần mỗi loại; chênh lệch trung bình không còn ~70 ms.

---

## T07 — Security, error, correlation, log (cả 5 service)

- [x] **T07-1 — F01 + N10 (P1): Bỏ qua xác thực bằng biến thể URL**
  - Vị trí: `driver-service/.../security/ApiSecurityFilter.java:51–65`; `trip-service/.../security/ApiSecurityFilter.java:47`; `identity-service/.../security/JwtAuthFilter.java:39`.
  - Vấn đề: filter so khớp `getRequestURI()` thô, trong khi MVC bỏ matrix parameter và decode trước khi định tuyến. Đã tái hiện: `PUT /internal;x/reservations/{id}` không có token → 201, driver chuyển RESERVED. Tương tự `/%69nternal/...`, `/api/trips;x` (bỏ qua kiểm tra role PASSENGER), `/api/auth/me;x` (→ 500).
  - Sửa: chuyển sang Spring Security `SecurityFilterChain` (dependency `spring-boot-starter-security`) với `authorizeHttpRequests` dùng `PathPatternRequestMatcher`, giữ `StrictHttpFirewall` mặc định (chặn `;`, `%2F`, `%25`, `..` → 400). JWT dùng `BearerTokenAuthenticationFilter` hoặc filter riêng đặt trong chain; internal token kiểm tra bằng matcher `/internal/**`. Mặc định `anyRequest().denyAll()` hoặc `authenticated()`, mở rõ ràng `/actuator/health/**`, `/api/auth/register`, `/api/auth/login`.
  - Kiểm chứng: test HTTP thật (Testcontainers) cho mỗi service với `;x`, `%69`, `//`, `/./`, chữ hoa, dấu `/` cuối trên mọi đường dẫn bảo vệ → 400/401/403 **và** assert DB không đổi.
  - ✅ Đã làm + kiểm chứng (2026-10-21): Spring Security `SecurityFilterChain` ở cả 5 service (`config/SecurityConfig.java`, `security/JwtAuthenticationFilter.java` đặt attribute `authenticatedUser`; driver thêm `InternalTokenFilter` constant-time cho `/internal/**`); firewall mặc định chặn matrix param. Xóa filter cũ (`JwtAuthFilter`, `ApiSecurityFilter`). Regression: `SecurityProbeTest` (driver + trip) — `;x`→400, `%69nternal`/`%61pi`→401, `//`, `/./`, chữ hoa→404, trailing slash; identity `AuthFlowIntegrationTest` assert DB không đổi. `mvnw clean verify`: 155 tests / 0 failures.

- [x] **T07-2 — N02 (P2): Backend lắng nghe trên mọi network interface**
  - Vị trí: `*/application.yml` (thiếu `server.address`).
  - Vấn đề: cổng 8081–8085 mở ra LAN, gồm `/internal/**` và `/actuator/**`, bỏ qua giới hạn của Nginx. Trái contract §2.
  - Sửa: `server.address: ${SERVER_ADDRESS:127.0.0.1}` khi chạy local trên host; khi container hóa (T29) đặt `0.0.0.0` nhưng không publish port. Cập nhật Nginx nếu cần.
  - Kiểm chứng: từ máy khác trong LAN, `curl http://<host>:8082/actuator/health` bị từ chối kết nối; qua `:8080` vẫn chạy.
  - ✅ Đã làm + kiểm chứng (2026-10-21): `server.address: ${SERVER_ADDRESS:127.0.0.1}` trong cả 5 `application.yml`. Chạy driver-service trên host: netstat chỉ có `127.0.0.1:8082 LISTENING` (không có 0.0.0.0) → máy khác LAN bị từ chối kết nối. Từ container nginx qua `host.docker.internal` (resolve 192.168.65.254) **vẫn reach được** — Docker Desktop (Windows/Mac) route host.docker.internal về loopback của host, nên dev-mode gateway :8080 chạy với binding mặc định; kiểm chứng: `curl http://localhost:8080/api/drivers/status` → 401 JSON ApiError đầy đủ qua nginx. Ghi chú README §6 + comment compose/nginx.

- [x] **T07-3 — F02 (P2): JWT cắt phần lẻ NumericDate; `nbf:null` được bỏ qua**
  - Vị trí: `identity-service/.../service/JwtService.java:133,137,141`; `driver-service/.../security/JwtVerifier.java:91,95,99`; `trip-service/.../security/JwtVerifier.java` (tương ứng).
  - Sửa: so sánh bằng giá trị thập phân (`BigDecimal` hoặc `doubleValue()` so với `Instant.now()` có phần mili giây), **hoặc** chốt và kiểm tra exp/nbf/iat phải là số nguyên. `nbf` có mặt mà là null → 401. Nếu dùng Spring Security (T07-1) thì dùng `JwtTimestampValidator` với skew 0.
  - Kiểm chứng: test token `exp = now + 0.5 s` (hợp lệ), `nbf = now + 0.9 s` (bị từ chối), `"nbf": null` (bị từ chối).
  - ✅ Đã làm + kiểm chứng (2026-10-21): `JwtService.authenticate`/`JwtVerifier.authenticate` so sánh exp/nbf bằng double (`Instant.now().toEpochMilli()/1000.0`); `"nbf": null` có mặt → 401. Test: token fractional-second trong SecurityProbeTest (driver/trip) + AuthFlowIntegrationTest (identity): exp=now+0.5s hợp lệ (đến dispatcher), nbf=now+0.9s→401, `"nbf":null`→401.

- [x] **T07-4 — F03 (P2): Jackson tự ép kiểu dữ liệu sai**
  - Vị trí: Identity `web/dto/RegisterRequest.java:16`; Driver `web/dto/ReserveRequest.java:9` (và Confirm/Release/Finish); Trip `web/dto/QuoteRequest.java:6`.
  - Vấn đề: role nhận 0/1; password/fullName dạng số; `commandVersion: 1.9` thành 1; `distanceM`/`fareVnd` thập phân bị cắt; tọa độ dạng chuỗi `"10.7"` được nhận.
  - Sửa: cấu hình ObjectMapper từng service: tắt coercion scalar (`CoercionConfigs` cho Textual/Integer/Float → `Fail`), bật `FAIL_ON_NUMBERS_FOR_ENUMS`, tắt `ACCEPT_FLOAT_AS_INT`, bật `FAIL_ON_NULL_FOR_PRIMITIVES` (field nguyên thủy bị thiếu không âm thầm thành 0), cân nhắc `FAIL_ON_UNKNOWN_PROPERTIES`.
  - Kiểm chứng: test mỗi trường hợp trên → 400 `INVALID_INPUT`, DB không đổi.
  - ✅ Đã làm + kiểm chứng (2026-10-21): Boot 4 MVC dùng JsonMapper auto-config từ `JsonMapperBuilderCustomizer` (đã verify bằng javap spring-boot-jackson jar) — @Bean ObjectMapper cũ KHÔNG được dùng cho request binding (thử nghiệm: role=0 vẫn 201). Thay bằng bean `strictCoercionCustomizer` ở identity/driver/trip (`config/JacksonConfig.java`): int/long←String+Float→Fail, double←String→Fail, String←Integer+Float→Fail, Role enum←số→Fail (identity), + FAIL_ON_NULL_FOR_PRIMITIVES + FAIL_ON_UNKNOWN_PROPERTIES. Test: `role:0`, password/fullName dạng số, `commandVersion:1.9`, `attempt:"3"`, latitude `"10.7"` → tất cả 400 INVALID_INPUT; identity assert DB count không đổi (=0).

- [x] **T07-5 — F04 (P2): Lỗi phía client bị gom thành 500**
  - Vị trí: `*/exception/GlobalExceptionHandler.java` — Identity/Trip dòng 77, Driver dòng 122, Matching/Payment dòng 43.
  - Vấn đề: `/api/trips?page=oops`, `size=2147483648`, `/api/trips/not-a-uuid`, sai method (405), sai media type (415), thiếu `@RequestAttribute`; Identity trả 500 cho path không tồn tại (thiếu handler `NoResourceFoundException`).
  - Sửa: thêm handler cho `MethodArgumentTypeMismatchException`, `MissingServletRequestParameterException`, `HttpRequestMethodNotSupportedException` (405), `HttpMediaTypeNotSupportedException` (415), `ServletRequestBindingException`, `NoResourceFoundException`; hoặc kế thừa `ResponseEntityExceptionHandler` rồi bọc lại thành ApiError. Các mã mới thêm vào contract §1.2.
  - Kiểm chứng: test từng trường hợp → đúng status và body ApiError có correlationId.
  - ✅ Đã làm + kiểm chứng (2026-10-21): thêm handler `HttpRequestMethodNotSupportedException`→405, `HttpMediaTypeNotSupportedException`→415 ở cả 5 GlobalExceptionHandler; identity thêm `NoResourceFoundException`→404 + `MissingServletRequestParameterException`/`MethodArgumentTypeMismatchException`. Test: DELETE trên endpoint POST-only→405, body text/plain→415 (cả hai có correlationId), `page=oops`, `size=2147483648`, `/api/trips/not-a-uuid`→400. Ghi chú: mã mới METHOD_NOT_ALLOWED/UNSUPPORTED_MEDIA_TYPE sẽ thêm vào contract §1.2 ở phase docs.

- [x] **T07-6 — F20 (P2): Log không xuống dòng giữa các event**
  - Vị trí: `*/logging/JsonLogLayout.java:58–62` (×5).
  - Sửa: trả về `json + CoreConstants.LINE_SEPARATOR` ở cả nhánh chính và nhánh fallback.
  - Kiểm chứng: test qua `OutputStreamAppender` thật, ghi 3 event → đúng 3 dòng, mỗi dòng parse được bằng JSON. Build log không còn `}{"timestamp"` (hiện có 19 chỗ).

- [x] **T07-7 — F21 (P2): Log exception mất stack trace và cause**
  - Vị trí: `*/logging/JsonLogLayout.java:55` (×5).
  - Sửa: dùng `ThrowableProxyUtil.asString(throwable)` cho field `stackTrace` (JSON escape đã được ObjectMapper xử lý), kèm chuỗi cause. Không đưa stack trace vào HTTP response.
  - Kiểm chứng: test exception có cause → output chứa tên class cause và ít nhất một frame `at ...`.
  - ✅ Đã làm + kiểm chứng (2026-10-21): `JsonLogLayout.doLayout` ghi field `stackTrace` = `ThrowableProxyUtil.asString(throwable)` (`ch.qos.logback.classic.spi`) ở cả nhánh chính và fallback; test `layoutIncludesFullStackTraceWithCause` ×5: exception có cause → output chứa tên class cause + `Caused by:` + frame `at ...`. 170 tests / 0 failures.

- [x] **T07-8 — N11 (P2): Không có log nghiệp vụ hay audit**
  - Vị trí: toàn bộ service code; `JsonLogLayout.STRUCTURED_MDC_KEYS`.
  - Vấn đề: chỉ có log truy cập HTTP và log lỗi. Không log register/login (kể cả thất bại), profile/availability, reserve/confirm/release/finish/expire, tạo trip. MDC `tripId`/`reservationId` không bao giờ được set; `eventType` luôn là `http_request`. Trái đề §4.1, §4.5 và spec §11.
  - Sửa: thêm log INFO có `eventType` riêng và `result`: `user_registered`, `login_succeeded`/`login_failed` (không log email đầy đủ hay password), `driver_availability_changed`, `reservation_reserved`/`confirmed`/`released`/`finished`/`expired` (set MDC `reservationId`, `tripId`), `trip_created` (set `tripId`). Expiry worker log từng reservation đã expire.
  - Kiểm chứng: test appender assert đủ event và MDC; không chứa token/password.
  - ✅ Đã làm + kiểm chứng (2026-10-21): audit log nghiệp vụ đã thêm — identity `UserService` (`user_registered`, `login_succeeded`/`login_failed`; không ghi email/password), driver `DriverService.setAvailability` (`driver_availability_changed`) và `ReservationService` (`reservation_reserved` chỉ sau CAS thành công, `reservation_confirmed`, `reservation_released` 3 nhánh tombstone/already-terminal/thực, `reservation_finished`, `reservation_expired`; MDC `reservationId`+`tripId`), trip `TripService.create` (`trip_created`; MDC `tripId`). Test capture qua OutputStreamAppender thật: `AuthFlowIntegrationTest.businessAuditLogsAreStructuredAndSecretFree`, `ReservationLifecycleIntegrationTest.reservationLifecycleAuditLogsAreStructured`, `DriverProfileIntegrationTest.availabilityChangeAuditLogIsStructuredAndSecretFree`, `TripIntegrationTest.tripCreatedAuditLogIsStructuredAndSecretFree` (assert eventType+result+MDC, toàn bộ output không chứa `Bearer `/email). 170 tests / 0 failures.

- [x] **T07-9 — N13 (P3): Correlation ID không chuẩn hóa**
  - Vị trí: `*/security/CorrelationIdFilter.java` `resolve()` (×5).
  - Vấn đề: `X-Correlation-ID: 1-2-3-4-5` được chấp nhận (JDK 21 parse được) nhưng chuỗi gốc được ghi vào log/error, còn Trip outbox lưu dạng chuẩn → tìm log bị lệch.
  - Sửa: chỉ chấp nhận đúng 36 ký tự theo regex UUID, hoặc dùng `UUID.fromString(x).toString()` làm giá trị chuẩn cho mọi nơi.
  - Kiểm chứng: gửi `1-2-3-4-5` → log, error body và outbox dùng cùng một giá trị.
  - ✅ Đã làm + kiểm chứng (2026-10-21): `CorrelationIdFilter.resolve()` chuẩn hóa bằng `UUID.fromString(value.trim()).toString()` ở cả 5 service; test `TripIntegrationTest.correlationIdIsCanonicalizedAndReturnedInEveryResponse`: header `X-Correlation-ID: 1-2-3-4-5` → error body (400), response header và `outbox.correlation_id` đều bằng `00000001-0002-0003-0004-000000000005`; không có header → server sinh UUID hợp lệ.

- [x] **T07-10 — N12 phần service (P3): Không trả `X-Correlation-ID` trong response**
  - Vị trí: `*/security/CorrelationIdFilter.java`.
  - Sửa: `response.setHeader("X-Correlation-ID", id)` trước `chain.doFilter`. Phần Nginx xem T08-8.
  - Kiểm chứng: response 200 có header đúng giá trị đã gửi hoặc vừa sinh.
  - ✅ Đã làm + kiểm chứng (2026-10-21): `doFilterInternal` set `response.setHeader("X-Correlation-ID", id)` trước `chain.doFilter` ở cả 5 service; test assert header có mặt với giá trị canonical trên cả response 400 và 202.

- [ ] **T07-11 — N03 (P3): Ứng dụng không giới hạn kích thước body**
  - Vị trí: `*/application.yml`; filter.
  - Sửa: filter từ chối `Content-Length > 65536` hoặc đọc vượt 64 KiB → 413 `PAYLOAD_TOO_LARGE` (ApiError). Áp dụng cả cho `/internal/**`.
  - Kiểm chứng: gọi thẳng backend với body 70 KiB → 413 JSON.

- [ ] **T07-12 — N08 (P3): Driver khởi động với internal token rỗng; `.env` local thiếu key**
  - Vị trí: `driver-service/.../application.yml` (`${INTERNAL_API_TOKEN:}`); `ApiSecurityFilter` constructor; `.env` (thiếu `INTERNAL_API_TOKEN`, `FARE_BASE_VND`, `FARE_PER_KM_VND`, `RESERVATION_EXPIRY_ENABLED`, `RESERVATION_EXPIRY_FIXED_DELAY_MS`).
  - Sửa: dừng khởi động nếu token rỗng hoặc ngắn hơn 16 ký tự; đồng bộ `.env` theo `.env.example`.
  - Kiểm chứng: chạy Driver không có token → dừng với thông báo rõ.

- [ ] **T07-13 — Q01 (P3): Test "không có secret trong log" quá hẹp**
  - Vị trí: `*/JsonLogLayoutTest.java` (×5).
  - Sửa: assert trên toàn bộ chuỗi output (không chỉ top-level key) không chứa token mẫu, password mẫu hay chuỗi `Bearer `; chạy một luồng register/login thật rồi quét log đã ghi.

- [x] **T07-14 — Q02 (P3): Chưa test framing nhiều event và stack trace**
  - Sửa: gộp chung với T07-6 và T07-7 (test qua appender thật).
  - ✅ Đã làm + kiểm chứng (2026-10-21): `JsonLogLayoutTest.appenderFramesEachEventOnItsOwnLine` ×5 — OutputStreamAppender thật, 3 event → đúng 3 dòng, mỗi dòng parse JSON, không còn `}{`; `layoutIncludesFullStackTraceWithCause` ×5. 170 tests / 0 failures.

- [x] **T07-15 — Q03 (P2): Security test chỉ dùng URL chuẩn**
  - Sửa: gộp chung với T07-1, T07-3, T07-4. Bộ test phải có URL biến thể, NumericDate có phần lẻ, JSON sai kiểu và assert DB không đổi.
  - ✅ Đã làm + kiểm chứng (2026-10-21): bộ `SecurityProbeTest` (driver/trip) + `AuthFlowIntegrationTest` (identity) đã bao gồm URL biến thể (`;x`, `%69nternal`, `//`, `/./`, chữ hoa, trailing slash), NumericDate phần lẻ (exp/nbf fractional, nbf null), JSON sai kiểu (số cho int/enum/double, chuỗi cho double) và assert DB không đổi. 155 tests / 0 failures.

---

## T08 — Frontend + Nginx

- [x] **T08-1 — N01 (P2): Bỏ qua rate limit đăng nhập bằng `;`**
  - Vị trí: `deploy/nginx/nginx.conf` (`location = /api/auth/login` và `location /api/auth`).
  - Vấn đề: `/api/auth/login;x` rơi vào location không có rate limit (đã đo: 16 request đều 502, không lần nào 503).
  - Sửa: đặt `limit_req` cho toàn bộ `location /api/auth/` (riêng login giới hạn chặt hơn); chặn URI chứa `;` hoặc `%3B` ở cấp server (`if ($request_uri ~* "(;|%3b)") { return 400; }`); kết hợp với T07-1 để backend cũng từ chối.
  - Kiểm chứng: 16 lần `/api/auth/login;x` → bị 400 hoặc 429.
  - ✅ Đã làm + kiểm chứng (2026-10-21): `nginx.conf` thêm server-level `if ($request_uri ~* "(;|%3b)") { return 400; }`; `limit_req` cho toàn bộ `/api/auth/` (zone auth_limit 10r/s burst=5) và riêng login chặt hơn (zone login_limit 5r/s burst=5, spec §11). Kiểm chứng thật qua :8080: `/api/auth/login;x` và `/api/auth/login%3Bx` → 400; burst 20 request tới `/api/auth/me` và `/api/auth/login` → mix 502/503 (limit_req hoạt động, trước đó toàn 502 không lần nào 503).

- [x] **T08-2 — N04 (P3): Location prefix không có ranh giới**
  - Vị trí: `deploy/nginx/nginx.conf` (`/api/auth`, `/api/drivers`, `/api/trips`, `/api/notifications`).
  - Vấn đề: `/api/authx`, `/api/tripsfoo` bị proxy sang backend.
  - Sửa: dùng `location = /api/trips` + `location /api/trips/` (tương tự cho các route khác).
  - Kiểm chứng: `/api/authx` → 404 JSON (xem T08-5).
  - ✅ Đã làm + kiểm chứng (2026-10-21): thay prefix location bằng cặp boundary `location = /api/X` + `location /api/X/` (proxy_pass kèm slash cuối) cho auth/drivers/trips/notifications. Kiểm chứng thật với backend chạy: `/api/driversfoo`, `/api/authx` → KHÔNG proxy (rơi SPA fallback HTML — JSON 404 đầy đủ theo T08-5, ghi nhận); exact `/api/drivers` và subpath `/api/drivers/status`, `/api/drivers/profile/{id}` proxy đúng tới driver (401 JSON ApiError).

- [ ] **T08-3 — F22 (P2): Lỗi 502/504/rate-limit trả HTML**
  - Vị trí: `deploy/nginx/nginx.conf:49,84`.
  - Sửa: `error_page 502 503 504 = @json_error;` với `location @json_error { default_type application/json; return 502 '{"code":"UPSTREAM_UNAVAILABLE",...}'; }`; mã tương ứng thêm vào contract §1.2.
  - Kiểm chứng: backend tắt → `application/json` theo đúng định dạng ApiError.

- [ ] **T08-4 — F23 (P3): Lỗi Nginx không có correlationId**
  - Vị trí: `deploy/nginx/nginx.conf:23,84`; `web/public/errors/payload-too-large.json`.
  - Sửa: dùng chung biến correlation của T08-8 trong body lỗi (`return` có biến thay cho file tĩnh) và header `X-Correlation-ID`.
  - Kiểm chứng: body 65 537 byte kèm header UUID → 413 có cùng correlationId.

- [ ] **T08-5 — F24 (P3): API path lạ rơi vào SPA fallback, trả 200 HTML**
  - Vị trí: `deploy/nginx/nginx.conf:87`.
  - Sửa: thêm `location /api/ { return 404 JSON; }` trước `location /`.
  - Kiểm chứng: `/api/does-not-exist` → 404 JSON.

- [ ] **T08-6 — N06 (P3): Rate limit trả 503 thay vì 429**
  - Vị trí: `deploy/nginx/nginx.conf`.
  - Sửa: `limit_req_status 429;` kèm body JSON `RATE_LIMITED` (contract xem T05-11).

- [ ] **T08-7 — N05 (P3): Lộ phiên bản Nginx**
  - Sửa: `server_tokens off;` trong block `http`.
  - Kiểm chứng: header `Server: nginx` không còn số phiên bản.

- [ ] **T08-8 — N12 phần Nginx (P3): Nginx không tạo hoặc truyền correlation ID**
  - Vị trí: `deploy/nginx/nginx.conf`.
  - Sửa: `map $http_x_correlation_id $cid { "~^[0-9a-fA-F-]{36}$" $http_x_correlation_id; default $request_id_uuid; }` (định dạng `$request_id` thành dạng UUID hoặc để service tự sinh và ghi rõ trong contract §5.2); `proxy_set_header X-Correlation-ID $cid;` và thêm `$cid` vào `log_format`.
  - Kiểm chứng: request không gửi header → access log của Nginx và log của service có cùng ID.

- [x] **T08-9 — N14 (P2): `host.docker.internal` không tồn tại trên Docker Engine Linux**
  - Vị trí: `deploy/compose.yaml` service nginx.
  - Sửa: thêm `extra_hosts: ["host.docker.internal:host-gateway"]`.
  - Kiểm chứng: `docker compose up nginx` trên Linux (WSL Docker Engine hoặc VM) → container chạy.
  - ✅ Đã làm + kiểm chứng (Phase 6): thêm `extra_hosts: ["host.docker.internal:host-gateway"]` cho service nginx; sau `up -d`, `curl http://localhost:8080/api/drivers` → 502 và log nginx ghi upstream `http://192.168.65.254:8082/...` (host-gateway) với "Connection refused" — DNS resolve đúng, proxy tới host hoạt động (services Java chưa chạy local nên 502 là kỳ vọng).

- [x] **T08-10 — N16 (P2): `crypto.randomUUID()` chỉ có trong secure context**
  - Vị trí: `src/web/src/api/client.ts:30`.
  - Vấn đề: mở `http://<IP>:8080` từ máy khác → mọi API call ném TypeError → không đăng nhập được.
  - Sửa: hàm `newUuid()` dùng `crypto.randomUUID` nếu có, nếu không thì dựng UUID v4 từ `crypto.getRandomValues` (có cả ở HTTP thường).
  - Kiểm chứng: unit test giả lập không có `randomUUID`; mở bằng IP LAN và đăng nhập được.
  - ✅ Đã làm + kiểm chứng (Phase 6): `uuidV4Fallback()` (UUID v4 từ `crypto.getRandomValues`, set version/variant bits) + `newUuid()` trong `src/web/src/api/client.ts`; test mới `src/api/uuid.test.ts` (3 cases: UUID hợp lệ ở secure context, 1000 UUID fallback unique khớp regex v4, giả lập non-secure context). `npm test`: 16/16 pass.

- [ ] **T08-11 — F15 (P2): 401 muộn của token cũ xóa phiên mới**
  - Vị trí: `src/web/src/api/client.ts:57–58`.
  - Sửa: chỉ `clearSession()` nếu token đang lưu vẫn là token đã gửi đi (so sánh token hoặc số thế hệ phiên).
  - Kiểm chứng: test với fetch hoãn: gửi bằng A, lưu B, A trả 401 → B vẫn còn.

- [ ] **T08-12 — F16 (P2): Login/register cũ hoàn tất muộn ghi đè phiên mới**
  - Vị trí: `src/web/src/pages/LoginPage.tsx:17–19`; `RegisterPage.tsx:23–27`.
  - Sửa: tăng một bộ đếm "auth generation" mỗi lần login/logout; continuation chỉ được `saveSession` và `navigate` khi generation khớp và component còn mounted (AbortController khi unmount).
  - Kiểm chứng: test hai lần login chồng nhau → phiên lưu lại là của lần bắt đầu sau.

- [ ] **T08-13 — F17 (P3): Validation frontend và backend lệch nhau**
  - Vị trí: `src/web/src/auth/validation.ts:24,35–36`; `RegisterPage.tsx:39,65`; `LoginPage.tsx:31`.
  - Vấn đề: JS `trim()` bỏ NBSP còn Java `trim()` thì không (và ngược lại với U+001F); mật khẩu toàn dấu cách qua được frontend; email >254 ký tự qua được AntD.
  - Sửa: chốt định nghĩa trim chung (chỉ ký tự ≤ U+0020 như Java `trim()`, hoặc dùng `strip()` ở backend và `/^\s+|\s+$/` tương đương ở frontend), áp dụng cả hai bên; frontend chặn mật khẩu toàn khoảng trắng và email >254.
  - Kiểm chứng: thêm các ca ở review 20/10 vào `validation.test.ts` và vào test Identity.

- [ ] **T08-14 — F18 + F19 (P3): Xử lý lỗi sau đăng ký và khi `/me` lỗi**
  - Vị trí: `RegisterPage.tsx:23–29`; `src/web/src/hooks/useMe.ts:16`; `PassengerHome.tsx:16`; `DriverHome.tsx:16`.
  - Sửa: register 201 nhưng login lỗi → báo "Đăng ký thành công, vui lòng đăng nhập" rồi chuyển sang `/login`. `useMe` trả về `error` riêng: chỉ 401 mới chuyển về trang login; lỗi mạng hoặc 5xx hiện nút "Thử lại".
  - Kiểm chứng: test hook với 401, 500 và lỗi mạng.

- [ ] **T08-15 — N34 (P3): Bỏ qua `expiresIn`, không redirect khi đã đăng nhập, `fetch` không có timeout**
  - Vị trí: `src/web/src/api/client.ts`, `auth/session.ts`, `pages/LoginPage.tsx`, `App.tsx`.
  - Sửa: lưu `expiresAt` trong session, `loadSession()` trả null khi đã hết hạn; `/login` và `/register` chuyển người đã đăng nhập về trang của role; `fetch` có AbortController timeout (ví dụ 5 s) và báo lỗi rõ ràng. Sửa luôn comment "server echo lại" ở dòng 29 cho đúng sau T07-10.

- [ ] **T08-16 — F30 (P2): `npm audit` có 8 package lỗi (2 critical, 1 high)**
  - Vị trí: `src/web/package.json:15,22,23`; `src/web/package-lock.json`.
  - Sửa: nâng `vite`, `vitest` (kéo theo `tinypool`, `@vitest/mocker`, `vite-node`, `esbuild`) và `react-router-dom` lên bản đã vá. Đọc ghi chú breaking change của Vite và Vitest; chạy lại test và build.
  - Kiểm chứng: `npm audit` không còn critical/high; `npm test` 13/13; `npm run build` thành công.

- [ ] **T08-17 — Q04 (P3): Frontend chỉ có test cho hàm helper**
  - Sửa: thêm Vitest + Testing Library (môi trường jsdom) cho `client.ts`, `useMe`, `LoginPage`, `RegisterPage`, bao gồm các race của T08-11 và T08-12.

---

## T09 — Driver profile, availability, location, candidates

- [x] **T09-1 — F07 (P2): `lastSeenAt` lấy trước UPDATE có thể bị chặn**
  - Vị trí: `driver-service/.../service/DriverService.java:88,107`; `repo/DriverRepository.java:94,102`.
  - Sửa: để DB tự gán thời gian sau khi giành được khóa (`SET last_seen_at = clock_timestamp() ... RETURNING last_seen_at`) và trả giá trị đó; dùng `GREATEST(last_seen_at, clock_timestamp())` để timestamp không bị lùi. Dùng cùng một nguồn đồng hồ với cutoff (T10-1, R04).
  - Kiểm chứng: test giữ khóa row 750 ms với ngưỡng 500 ms → `lastSeenAt` trả về mới và driver vẫn có trong candidates ngay sau đó.
  - ✅ Đã làm + kiểm chứng (2026-10-21): `DriverRepository.updateLocation/updateAvailabilityIfFree` gán `SET last_seen_at = GREATEST(last_seen_at, clock_timestamp()) ... RETURNING last_seen_at`; test `HeartbeatClockIntegrationTest.locationWaitingOnRowLockGetsFreshDbClockTimestamp` (giữ row lock 750 ms, ngưỡng 500 ms → lastSeenAt fresh theo DB clock, driver vẫn trong candidates).

- [x] **T09-2 — F11 (P2): Haversine không clamp → điểm đối cực ra 0 m**
  - Vị trí: `driver-service/.../geo/Haversine.java:20–24`; `trip-service/.../geo/Haversine.java:20–24` (bản sao).
  - Sửa: `a = Math.min(1.0, Math.max(0.0, a))` trước `atan2`; nếu kết quả không hữu hạn thì ném lỗi.
  - Kiểm chứng: test với (89.846…, 14.397…) và (−89.846…, −165.602…) → khoảng 20 015 114 m, ở cả hai service.
  - ✅ Đã làm + kiểm chứng (2026-10-21): clamp `a` về [0,1] trước `atan2`, ném lỗi nếu kết quả không hữu hạn; `HaversineTest` ×2 module (driver/trip) — 4 tests mỗi: đối cực đúng = round(π·6371088.8) = 20_015_114 m, gần đối cực ≈ cùng giá trị, trùng điểm = 0, NaN/Inf → IllegalArgumentException.

- [x] **T09-3 — F13 (P3): Chi tiết lỗi availability dùng snapshot trước CAS**
  - Vị trí: `DriverService.java:86–96`.
  - Sửa: khi CAS thất bại, đọc lại driver (hoặc `UPDATE ... RETURNING`), lấy `tripId` từ `current_trip_id`, hoặc từ reservation của `current_reservation_id` nếu đang RESERVED.
  - Kiểm chứng: driver RESERVED gọi PUT OFFLINE → 409 với `state=RESERVED` và `tripId` khác null.
  - ✅ Đã làm + kiểm chứng (2026-10-21): khi CAS thất bại đọc lại driver, lấy tripId từ `current_trip_id` hoặc từ reservation của `current_reservation_id`; test `DriverProfileIntegrationTest.offlineConflictResolvesTripIdFromReservationWhenCurrentTripIsNull` → 409 với state=RESERVED và tripId khác null.

- [x] **T09-4 — N31 (P3): Biển số chưa chuẩn hóa; displayName lưu chưa trim**
  - Vị trí: `DriverService.createProfile` / `validateProfileInput:141–151`.
  - Sửa: chuẩn hóa plate (trim, đổi sang chữ hoa, gộp khoảng trắng) trước khi kiểm tra và lưu; regex cho phép `[A-Z0-9.\- ]`; lưu `displayName` đã trim (như Identity với fullName). Migration chuẩn hóa dữ liệu cũ và unique trên `upper(plate_number)` nếu cần.
  - Kiểm chứng: `51A-123.45` rồi `51a-123.45 ` → lần hai 409 `PLATE_TAKEN`.
  - ✅ Đã làm + kiểm chứng (2026-10-21): `normalizePlate()` (trim, uppercase, gộp khoảng trắng) trước khi kiểm tra và lưu; migration V5 chuẩn hóa dữ liệu cũ + unique index `uq_vehicles_plate_upper` trên `upper(plate_number)`; tests `duplicatePlateReturns409PlateTaken` và `plateNormalizationMakesCaseAndWhitespaceDuplicatesConflict` → 409 PLATE_TAKEN.

- [x] **T09-5 — R05 (P3): Candidates tải toàn bộ driver AVAILABLE vào Java**
  - Vị trí: `DriverService.java:124–141`; `DriverRepository.java:109`.
  - Sửa: lọc sơ bộ trong SQL bằng bounding box (lat ± radius/111 km, lng ± …/cos(lat)) + `LIMIT`, sau đó mới tính Haversine trong Java.
  - Kiểm chứng: kết quả giống hệt bản cũ trên bộ dữ liệu test; `EXPLAIN` có dùng index.
  - ✅ Đã làm + kiểm chứng (2026-10-21): `DriverRepository.findCandidatesInBox(lat,lng,radiusM,staleMs,limit)` — bounding box trong SQL + LIMIT rồi mới Haversine trong Java; tests `candidatesExcludeOfflineStaleAndFar` / `candidatesDeterministicOrderAndTieBreak` / `candidatesClampsLimitAndValidatesParams` pass với kết quả như bản cũ.

- [x] **T09-6 — N32 phần Driver (P3): Thiếu cột theo spec §7**
  - Vị trí: Driver V2/V3.
  - Vấn đề: `drivers` thiếu `version`; `vehicles` thiếu `updated_at`; `processed_commands` không có cơ chế dọn dữ liệu cũ.
  - Sửa: migration V5 thêm các cột (hoặc ghi lý do không thêm vào "lệch đặc tả có chủ đích" của C04, ví dụ fencing bằng pointer thay cho version); job dọn `processed_commands` cũ hơn N ngày.
  - ✅ Đã làm + kiểm chứng (2026-10-21): migration V5 thêm `drivers.version` (BIGINT NOT NULL DEFAULT 1) và `vehicles.updated_at`; `ProcessedCommandCleanupWorker` mới xóa processed_commands cũ hơn N ngày (gated bởi `app.flyway.enabled`).

---

## T10 — Reserve nguyên tử

- [x] **T10-1 — F06 (P2): Heartbeat cutoff lấy trước khi chờ khóa driver**
  - Vị trí: `driver-service/.../service/ReservationService.java:99`; `repo/ReservationRepository.java:98`.
  - Sửa: tính cutoff trong chính câu SQL CAS (`last_seen_at > clock_timestamp() - make_interval(secs => ?)`), để PostgreSQL đánh giá lại sau khi có khóa row.
  - Kiểm chứng: test giữ khóa driver vượt ngưỡng heartbeat → reserve trả 409 `DRIVER_NOT_AVAILABLE`.
  - ✅ Đã làm + kiểm chứng (2026-10-21): cutoff trong chính SQL CAS (`last_seen_at > clock_timestamp() - make_interval(secs => ?)`) đánh giá sau khi có khóa row; test `HeartbeatClockIntegrationTest.reserveWaitingOnRowLockSeesStaleHeartbeatByDbClock` (giữ lock 1200 ms → 409 DRIVER_NOT_AVAILABLE, không có reservation row).

- [x] **T10-2 — F10 + N29 (P2): `expiresAt` không được kiểm tra phạm vi**
  - Vị trí: `ReservationService.validate:264–275`.
  - Vấn đề: nhận `expiresAt` đã qua (201 rồi chiếm driver) và nhận cả `expiresAt` rất xa (năm 2100 → driver bị RESERVED gần như vĩnh viễn).
  - Sửa: yêu cầu `now < expiresAt ≤ now + OFFER_TTL_MAX` (env, ví dụ 120 s), ngoài khoảng → 400 `INVALID_INPUT`; dùng cùng nguồn đồng hồ với worker.
  - Kiểm chứng: test quá khứ, +1 giờ, +30 s.
  - ✅ Đã làm + kiểm chứng (2026-10-21): kiểm tra khoảng `(now, now + OFFER_TTL_MAX]` bằng `dbNow()` trong `doReserve()`, chỉ cho reservation MỚI (replay command cũ trả response đã lưu theo contract §4.1); test `reserveRejectsExpiresOutsideOfferTtlWindow` — quá khứ / +1h / 9999-12-31 → 400 INVALID_INPUT, không có row.

- [x] **T10-3 — F12 (P3): Lỗi dữ liệu bị báo nhầm là trùng trip/attempt**
  - Vị trí: `ReservationService.java:106`.
  - Sửa: chỉ trả `DRIVER_NOT_AVAILABLE` khi SQLState là `23505` và tên constraint là `uq_reservations_trip_attempt`; lỗi khác (ví dụ `22008` timestamp ngoài miền) → 400 hoặc ném tiếp. Kết hợp T10-2 sẽ chặn trước được timestamp ngoài miền.
  - Kiểm chứng: `expiresAt="+300000-01-01T00:00:00Z"` → 400.
  - ✅ Đã làm + kiểm chứng (2026-10-21): `isTripAttemptUniqueViolation` chỉ chấp nhận SQLState 23505 trong cause chain + constraint `uq_reservations_trip_attempt`; DataIntegrityViolationException khác ném tiếp (handler → 500). Không có probe HTTP riêng: `validate()` chặn mọi input ngoài miền trước INSERT (T10-2); xác nhận qua full verify.

- [x] **T10-4 — R04 (P2): Trộn đồng hồ JVM và DB**
  - Vị trí: `ReservationService.confirm:149` (JVM); `ReservationRepository.expireReserved/findExpiredIds` (DB `now()`); heartbeat (JVM).
  - Sửa: chọn một nguồn duy nhất (khuyến nghị PostgreSQL `clock_timestamp()` cho mọi so sánh hạn) và áp dụng cho T09-1, T10-1, T10-2 và confirm. Lưu ý `now()` là thời điểm **bắt đầu** transaction, nên dùng `clock_timestamp()` khi so sánh sau khi chờ khóa.
  - Kiểm chứng: rà code không còn `Instant.now()` trong các phép so sánh hạn.
  - ✅ Đã làm + kiểm chứng (2026-10-21): một nguồn đồng hồ duy nhất — `dbNow()`/`clock_timestamp()` cho recheck confirm, `expireReserved/findExpiredIds`, cutoff heartbeat trong SQL; không còn `Instant.now()` trong các phép so sánh hạn (rà code). Kiểm chứng qua `HeartbeatClockIntegrationTest` + `ReservationLifecycleIntegrationTest`.

- [x] **T10-5 — R01 phần Driver (P3): Hash command coi `0.0` và `-0.0` là khác nhau**
  - Vị trí: `ReservationService.requestHash:290–301`.
  - Sửa: chuẩn hóa `-0.0` thành `0.0` (hoặc dùng `BigDecimal.stripTrailingZeros().toPlainString()`) trước khi băm; chốt quy tắc canonical trong contract §4.1. Cùng quyết định với T12-4.
  - ✅ Đã làm + kiểm chứng (2026-10-21): `canonicalDouble()` chuẩn hóa -0.0 → "0.0" trước khi băm; quy tắc canonical chốt trong contract §4.1; unit test `ReservationServiceHashTest` ×3 (-0.0 ≡ 0.0, tọa độ khác → hash khác, giá trị khác 0 giữ đúng Double.toString).

---

## T11 — Confirm / release / finish / expiry

- [x] **T11-1 — F08 (P2): Expiry worker chặn đầu hàng đợi, khóa blocking, không cô lập lỗi**
  - Vị trí: `driver-service/.../service/ReservationExpiryWorker.java:22–23`; `ReservationService.expire:204–210`.
  - Vấn đề: một reservation đang bị giữ khóa làm các reservation quá hạn khác (của driver khác) phải chờ; một lỗi ném ra làm dừng cả batch.
  - Sửa: dùng `pg_try_advisory_xact_lock` (bỏ qua item đang bị khóa) hoặc `SELECT … FOR UPDATE SKIP LOCKED`; bọc try/catch cho từng item kèm log (T07-8); `SET LOCAL lock_timeout`.
  - Kiểm chứng: test giữ khóa reservation A → reservation B quá hạn vẫn EXPIRED trong vòng 1–2 chu kỳ; A bị lỗi giả lập → B vẫn được xử lý.
  - ✅ Đã làm + kiểm chứng (2026-10-21): `expireIfUnlocked` dùng `pg_try_advisory_xact_lock` (bỏ qua item đang bị khóa) + `SET LOCAL lock_timeout 2s`; worker try/catch từng item kèm log.error. Tests: `ReservationLifecycleIntegrationTest.expiryWorkerSkipsLockedReservationButExpiresOthers` (giữ advisory lock A → B vẫn EXPIRED trong chu kỳ) và `ReservationExpiryWorkerTest` (cô lập lỗi — a ném, b vẫn được xử lý).

- [x] **T11-2 — F09 (P2): `commandVersion = INT_MAX` làm reservation không xử lý tiếp được**
  - Vị trí: `ReservationService.java:155,214,236`.
  - Vấn đề: confirm với 2147483647 → driver BUSY, sau đó không còn version hợp lệ nào cho release/finish; CONFIRMED lại không tự hết hạn.
  - Sửa: chốt trong contract rằng version chỉ được tăng một bậc mỗi lần, hoặc giới hạn trên `≤ last + 1000`; hoặc không cho command đưa reservation chưa terminal lên `INT_MAX`. Không chỉ đổi kiểu sang BIGINT để dời biên.
  - Kiểm chứng: test confirm với version cực lớn → 400; luồng 1 → 2 → 3 vẫn chạy bình thường.
  - ✅ Đã làm + kiểm chứng (2026-10-21): `checkVersion` chặn `version > stored + MAX_VERSION_STEP(1000)` → 400 INVALID_INPUT (chốt contract §4.1). Tests: `confirmWithHugeVersionIsRejectedButNormalFlowWorks` và `reserveWithHugeCommandVersionIsRejected` (INT_MAX → 400, không row, driver AVAILABLE); luồng 1→2→3 bình thường.

- [x] **T11-3 — N30 (P3): Release không kiểm tra reason có hợp với state**
  - Vị trí: `ReservationService.release:161–182`.
  - Vấn đề: `OFFER_EXPIRED`/`REJECTED` vẫn release được reservation CONFIRMED và giải phóng driver BUSY.
  - Sửa: với state CONFIRMED chỉ chấp nhận reason `CANCELLED` (bù trừ); reason khác → 409 `STATE_CONFLICT`. Cập nhật contract §4.2.
  - Kiểm chứng: test release `OFFER_EXPIRED` trên CONFIRMED → 409, driver vẫn BUSY.
  - ✅ Đã làm + kiểm chứng (2026-10-21): release trên CONFIRMED chỉ chấp nhận reason CANCELLED; reason khác → 409 STATE_CONFLICT (contract §4.2 đã cập nhật). Test: `offerExpiredReleaseOnConfirmedIsStateConflict` — driver vẫn BUSY.

---

## T12 — Quote và tạo trip

- [x] **T12-1 — F14 (P2): `createdAt` trong response create/replay khác với GET**
  - Vị trí: `trip-service/.../service/TripService.java:94`; `repo/TripRepository.java:32`; migration V2 dòng 24.
  - Sửa: dùng một timestamp duy nhất: INSERT với giá trị tường minh (`created_at = ?`) hoặc `RETURNING created_at`, và dùng giá trị đó cho response, bản lưu replay và `occurred_at` của outbox.
  - Kiểm chứng: test create rồi GET → `createdAt` bằng nhau; replay cũng giống hệt.
  - ✅ Đã làm + kiểm chứng (2026-10-21): một timestamp duy nhất `Instant.now().truncatedTo(SECONDS)` dùng cho response, bản lưu replay, `trips.created_at` và `outbox.occurred_at`; test `TripIntegrationTest.createdAtIsStableAcrossGetAndReplay` — POST/GET/DB/replay trả cùng giá trị.

- [x] **T12-2 — N33 (P3): Bảng outbox thiếu field cho envelope và retry**
  - Vị trí: `trip-service/.../V2__trips.sql` bảng `outbox`; `OutboxRepository.insertPending`.
  - Sửa: migration thêm `aggregate_version` (chụp `trips.version` lúc ghi — bắt buộc làm ngay vì không suy lại được), `schema_version`, `producer`, `routing_key`, `attempts`, `next_attempt_at`, `last_error`; `insertPending` ghi `aggregate_version = 1` cho TripRequested.
  - Kiểm chứng: test create → outbox có `aggregate_version = 1`, `routing_key = trip.requested`.
  - ✅ Đã làm + kiểm chứng (2026-10-21): migration V4 thêm `aggregate_version/schema_version/producer/routing_key/attempts/next_attempt_at/last_error` cho outbox; `insertPending` ghi `aggregate_version=1`, `routing_key='trip.requested'`; test `outboxRowCarriesAggregateVersionAndRoutingKey`.

- [x] **T12-3 — N32 phần Trip (P3): Thiếu cột/ràng buộc theo spec §7**
  - Vị trí: Trip V2/V3.
  - Vấn đề: `trips` thiếu `reservation_id`; `trip_events.id` là BIGSERIAL (spec yêu cầu UUID) và thiếu unique `(trip_id, type, version)` để chống ghi trùng lịch sử khi retry; `idempotency_keys` không có cơ chế dọn dữ liệu cũ.
  - Sửa: migration V4 thêm `reservation_id`, unique `(trip_id, type, version)`, đổi hoặc ghi lý do giữ BIGSERIAL; job dọn idempotency key cũ (ví dụ > 24 h).
  - ✅ Đã làm + kiểm chứng (2026-10-21): migration V4 thêm `trips.reservation_id` + unique index `uq_trip_events_trip_type_version` trên `trip_events(trip_id, type, version)`; giữ BIGSERIAL cho `trip_events.id` — lệch đặc tả có chủ đích (id nội bộ history, không phải event identity lộ ra envelope); `IdempotencyKeyCleanupWorker` mới xóa idempotency key cũ > 24 h (gated bởi `app.flyway.enabled`).

- [x] **T12-4 — R01 (P3): Hash idempotency coi `0.0` và `-0.0` là khác nhau**
  - Vị trí: `TripService.requestHash:196`.
  - Sửa: chuẩn hóa như T10-5; ghi rõ trong contract §3.3.
  - Kiểm chứng: cùng key, latitude 0.0 rồi -0.0 → replay, không phải 409.
  - ✅ Đã làm + kiểm chứng (2026-10-21): `canonicalDouble()` trong `TripService.requestHash` (-0.0 → "0.0"); quy tắc chốt trong contract §3.3; test `negativeZeroLatitudeReplaysInsteadOfMismatch` — cùng key, latitude 0.0 rồi -0.0 → replay 202, không phải 409.

- [x] **T12-5 — R02 (P3): Count và content của list không cùng snapshot**
  - Vị trí: `TripService.list:137–155`.
  - Sửa: đặt `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` cho `list`, hoặc gộp thành một query với `COUNT(*) OVER()`.
  - Kiểm chứng: test tạo chuyến đồng thời trong lúc list → `totalElements` khớp với nội dung trang.
  - ✅ Đã làm + kiểm chứng (2026-10-21): `list()` đặt `@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)` — count và content đọc cùng snapshot; test `listUnderRepeatableReadStaysConsistentWithConcurrentCreates`.

- [ ] **T12-6 — N28 (P3): Không giới hạn số chuyến active của một passenger**
  - Vị trí: `TripService.create:81–134`.
  - Sửa (sau khi chốt T05-10): trong transaction, khóa theo passenger (advisory lock) rồi kiểm tra có chuyến state chưa terminal hay không → 409 `ACTIVE_TRIP_EXISTS`; hoặc dùng partial unique index `(passenger_id) WHERE state IN ('SEARCHING','DRIVER_OFFERED','CANCELLING','ACCEPTED','IN_PROGRESS')`.
  - Kiểm chứng: hai create đồng thời với hai key khác nhau → một 202, một 409; replay cùng key vẫn 202.

- [x] **T12-7 — N20 (P3): Cổng test cố định trùng nhau giữa các module**
  - Vị trí: `server.port=18099` (Driver và Trip), `18100` (Driver và Matching), `18101` (Driver và Payment), cùng `18097`, `18098`, `18102`.
  - Sửa: `webEnvironment = RANDOM_PORT` + `@LocalServerPort` để dựng BASE URL; bỏ hằng số `BASE`.
  - Kiểm chứng: `./mvnw -B -T 4 clean verify` pass.
  - ✅ Đã làm + kiểm chứng (2026-10-21): 9 test classes chuyển sang `RANDOM_PORT` + `@LocalServerPort` (`org.springframework.boot.test.web.server.LocalServerPort` trong Boot 4), bỏ `server.port=...`; full `./mvnw -B clean verify` xanh trên cổng ngẫu nhiên — 194 tests / 0 failures.
