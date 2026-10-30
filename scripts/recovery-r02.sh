#!/usr/bin/env bash
# T31 — R02 (spec §13): Crash Payment sau DB commit nhưng trước ACK, restart -> redelivery không tạo payment trùng.
# Failpoint XÁC ĐỊNH: env APP_INBOX_FAILPOINT_DELAY_MS bật hook test-only trong PaymentInboxListener
# (sleep giữa DB commit và basicAck). Bật qua compose override TẠM sinh lúc chạy — KHÔNG sửa
# deploy/compose.yaml tracked (spec §13: fault injection chỉ bật bằng profile/env test, không đưa
# endpoint kill/crash ra public; ${VAR:-} trong compose tracked sẽ cho chuỗi rỗng làm Spring crash startup).
# Chuỗi: complete trip -> payment consumer commit payments row -> sleep trong failpoint window ->
# docker kill (SIGKILL) -> message unacked bị broker requeue -> restart KHÔNG failpoint -> redelivery
# hit dedup ("duplicate event skipped") -> verify MỘT payment record + trip payment_status SUCCEEDED.
# Raw results: tests/recovery/r02-results.jsonl + r02-summary.txt.
# Yêu cầu: stack Docker Compose (T29) đang chạy + seed demo đã chạy. Script KHÔNG build lại: image dùng chung
# cho 5 service, build riêng ở đây sẽ làm payment-service chạy khác code với 4 service còn lại.
set -eu

fail() { echo "R02 FAIL: $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$REPO_ROOT" || fail "không vào được repo root"
BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
FAILPOINT_MS="${2:-${FAILPOINT_MS:-8000}}"

SEED_PASSWORD="${SEED_PASSWORD:-}"
if [ -z "$SEED_PASSWORD" ] && [ -f ".env" ]; then
  SEED_PASSWORD="$(sed -n 's/^SEED_PASSWORD=//p' .env | tr -d '\r' | tail -n 1)"
fi
: "${SEED_PASSWORD:=SeedPass123!}"

DRIVER_DB_PASSWORD="${DRIVER_DB_PASSWORD:-}"
if [ -z "$DRIVER_DB_PASSWORD" ] && [ -f ".env" ]; then
  DRIVER_DB_PASSWORD="$(sed -n 's/^DRIVER_DB_PASSWORD=//p' .env | tr -d '\r' | tail -n 1)"
fi
[ -n "$DRIVER_DB_PASSWORD" ] || fail "thiếu DRIVER_DB_PASSWORD trong .env"

PAYMENT_DB_PASSWORD="${PAYMENT_DB_PASSWORD:-}"
if [ -z "$PAYMENT_DB_PASSWORD" ] && [ -f ".env" ]; then
  PAYMENT_DB_PASSWORD="$(sed -n 's/^PAYMENT_DB_PASSWORD=//p' .env | tr -d '\r' | tail -n 1)"
fi
[ -n "$PAYMENT_DB_PASSWORD" ] || fail "thiếu PAYMENT_DB_PASSWORD trong .env"

TRIP_DB_PASSWORD="${TRIP_DB_PASSWORD:-}"
if [ -z "$TRIP_DB_PASSWORD" ] && [ -f ".env" ]; then
  TRIP_DB_PASSWORD="$(sed -n 's/^TRIP_DB_PASSWORD=//p' .env | tr -d '\r' | tail -n 1)"
fi
[ -n "$TRIP_DB_PASSWORD" ] || fail "thiếu TRIP_DB_PASSWORD trong .env"

PAYMENT_CONTAINER="ridehailing-payment-service"
RABBITMQ_CONTAINER="ridehailing-rabbitmq"
READINESS_URL="http://127.0.0.1:8085/actuator/health/readiness"

PASS1="passenger-1@demo.local"
D1="driver-1@demo.local"; D2="driver-2@demo.local"; D3="driver-3@demo.local"
PICKUP_LAT=21.0345; PICKUP_LNG=105.8409
DEST_LAT=21.0260; DEST_LNG=105.8570

http() { # METHOD PATH [BEARER] [JSON_BODY] -> "STATUS<TAB>BODY"
  local method="$1" path="$2" bearer="${3:-}" data="${4:-}"
  local tmp status
  tmp="$(mktemp)"
  local args=(-s --max-time 10 -X "$method" "$BASE_URL$path")
  [ -n "$bearer" ] && args+=(-H "Authorization: Bearer $bearer")
  [ -n "$data" ] && args+=(-H 'Content-Type: application/json' -d "$data")
  status="$(curl "${args[@]}" -o "$tmp" -w '%{http_code}')" || fail "HTTP lỗi (network) $method $path"
  printf '%s\t%s' "$status" "$(cat "$tmp")"
  rm -f "$tmp"
}

json_field() { sed -n 's/.*"'"$2"'":"\([^"]*\)".*/\1/p' <<<"$1" | head -1; }
json_number() { sed -n 's/.*"'"$2"'":\([0-9][0-9]*\).*/\1/p' <<<"$1" | head -1; }

gen_uuid() {
  local h
  h="$(openssl rand -hex 16)" || fail "openssl không khả dụng (Idempotency-Key)"
  echo "${h:0:8}-${h:8:4}-${h:12:4}-${h:16:4}-${h:20:12}"
}

login() { # email -> token
  local line status body tok
  line="$(http POST /api/auth/login "" "{\"email\":\"$1\",\"password\":\"$SEED_PASSWORD\"}")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "200" ] || fail "login $1 -> HTTP $status: $body (chạy scripts/seed-demo.sh trước)"
  tok="$(json_field "$body" accessToken)"
  [ -n "$tok" ] || fail "login $1: không có accessToken"
  echo "$tok"
}

set_driver_status() { # tok status(AVAILABLE|OFFLINE) label — echo driverId (contract §3.2)
  local line status body did
  line="$(http PUT /api/drivers/me/availability "$1" "{\"status\":\"$2\"}")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "200" ] || fail "set $3 -> $2: HTTP $status: $body (trip kẹt? chạy reset-demo.sh --confirm)"
  did="$(json_field "$body" driverId)"
  [ -n "$did" ] || fail "set $3: không có driverId trong response: $body"
  echo "$did"
}

heartbeat_d1() {
  local line status body
  line="$(http PUT /api/drivers/me/location "$D1_TOK" "{\"latitude\":$PICKUP_LAT,\"longitude\":$PICKUP_LNG}")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "200" ] || fail "heartbeat driver-1 -> HTTP $status: $body"
}

wait_state() { # tok trip_id target max_seconds -> echo final state; return 1 nếu timeout hoặc về terminal khác
  local deadline=$((SECONDS + $4)) st="" line status body
  while [ "$SECONDS" -lt "$deadline" ]; do
    line="$(http GET "/api/trips/$2" "$1")"
    status="${line%%$'\t'*}"; body="${line#*$'\t'}"
    [ "$status" = "200" ] || fail "GET trip $2 -> HTTP $status: $body"
    st="$(json_field "$body" state)"
    if [ "$st" = "$3" ]; then echo "$st"; return 0; fi
    case "$st" in CANCELLED|COMPLETED|NO_DRIVER) echo "$st"; return 1 ;; esac
    sleep 2
  done
  echo "${st:-?}"
  return 1
}

cancel_nonterminal() { # tok label — hủy mọi trip chưa terminal của user (T12-6: tối đa 1)
  local line status body pairs pair tid st final cancelled
  line="$(http GET /api/trips "$1")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "200" ] || fail "GET trips $2 -> HTTP $status: $body"
  pairs="$(grep -o '"tripId":"[0-9a-f-]*","state":"[A-Z_]*"' <<<"$body" || true)"
  while IFS= read -r pair; do
    [ -z "$pair" ] && continue
    tid="$(sed 's/.*"tripId":"\([^"]*\)".*/\1/' <<<"$pair")"
    st="$(sed 's/.*"state":"\([A-Z_]*\)".*/\1/' <<<"$pair")"
    cancelled=0
    case "$st" in
      SEARCHING|DRIVER_OFFERED)
        line="$(http POST "/api/trips/$tid/cancel" "$1")"
        status="${line%%$'\t'*}"; body="${line#*$'\t'}"
        # contract §3.3: 200 = CANCELLED ngay (SEARCHING, không reservation); 202 = CANCELLING
        # (DRIVER_OFFERED, release reservation async) — cả hai đều hợp lệ.
        case "$status" in 200|202) ;; *) fail "cancel $tid ($2) -> HTTP $status: $body" ;; esac
        cancelled=1
        ;;
      CANCELLING) cancelled=1 ;; # transient — chờ terminal (phải về CANCELLED)
    esac
    if [ "$cancelled" = "1" ]; then
      if ! final="$(wait_state "$1" "$tid" CANCELLED 30)"; then
        fail "trip $tid ($2) không về CANCELLED sau cancel (state cuối: ${final:-?})"
      fi
    fi
  done <<<"$pairs"
}

create_trip() { # tok label -> echo tripId (202 + Idempotency-Key mới)
  local idem tmp code body tid
  idem="$(gen_uuid)"
  tmp="$(mktemp)"
  code="$(curl -s --max-time 10 -X POST "$BASE_URL/api/trips" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $idem" -d "$TRIP_BODY" -o "$tmp" -w '%{http_code}')" || fail "create trip ($2) lỗi (network)"
  body="$(cat "$tmp")"; rm -f "$tmp"
  [ "$code" = "202" ] || fail "create trip ($2) -> HTTP $code: $body"
  tid="$(json_field "$body" tripId)"
  [ -n "$tid" ] || fail "create trip ($2): không có tripId trong response: $body"
  echo "$tid"
}

queue_depth() { # queue_name -> int (0 nếu queue chưa tồn tại)
  docker exec "$RABBITMQ_CONTAINER" rabbitmqctl list_queues name messages \
    | awk -v q="$1" '$1==q {print $2; found=1} END {if (!found) print 0}'
}

wait_payment_ready() {
  local i code
  for i in $(seq 1 40); do
    code="$(curl -s -o /dev/null -w '%{http_code}' "$READINESS_URL" || true)"
    [ "$code" = "200" ] && return 0
    sleep 3
  done
  fail "payment-service không ready sau 120 s (docker logs $PAYMENT_CONTAINER | tail -50 để debug)"
}

payments_count() { # trip_id -> int (read-only, tài khoản của chính payment-service)
  docker exec -e PGPASSWORD="$PAYMENT_DB_PASSWORD" ridehailing-postgres \
    psql -U payment_svc -d payment_db -Atc "SELECT count(*) FROM payments WHERE trip_id = '$1'"
}

inbox_events_count() { # trip_id -> int (consumer payment-service)
  docker exec -e PGPASSWORD="$PAYMENT_DB_PASSWORD" ridehailing-postgres \
    psql -U payment_svc -d payment_db -Atc "SELECT count(*) FROM inbox_events WHERE consumer_name = 'payment-service' AND aggregate_id = '$1'"
}

trip_payment_status() { # trip_id -> payment_status (read-only, tài khoản của chính trip-service)
  docker exec -e PGPASSWORD="$TRIP_DB_PASSWORD" ridehailing-postgres \
    psql -U trip_svc -d trip_db -Atc "SELECT payment_status FROM trips WHERE id = '$1'"
}

driver_status_db() { # driver_id -> status (read-only, tài khoản của chính driver-service)
  docker exec -e PGPASSWORD="$DRIVER_DB_PASSWORD" ridehailing-postgres \
    psql -U driver_svc -d driver_db -Atc "SELECT status FROM drivers WHERE id = '$1'"
}

echo "== R02: BASE_URL=$BASE_URL failpoint=${FAILPOINT_MS}ms (test-only, compose override tạm)"

# ---------- logins MỘT lần, reuse token (tránh rate limit /api/auth) ----------
P1_TOK="$(login "$PASS1")"; sleep 0.2
D1_TOK="$(login "$D1")";    sleep 0.2
D2_TOK="$(login "$D2")";    sleep 0.2
D3_TOK="$(login "$D3")"

RESULTS_DIR="$REPO_ROOT/tests/recovery"
mkdir -p "$RESULTS_DIR"
RESULTS_FILE="$RESULTS_DIR/r02-results.jsonl"
SUMMARY_FILE="$RESULTS_DIR/r02-summary.txt"
: > "$RESULTS_FILE"

TRIP_BODY="{\"pickup\":{\"latitude\":$PICKUP_LAT,\"longitude\":$PICKUP_LNG},\"destination\":{\"latitude\":$DEST_LAT,\"longitude\":$DEST_LNG}}"

# ---------- reset state ----------
D2_ID="$(set_driver_status "$D2_TOK" OFFLINE "driver-2")"
D3_ID="$(set_driver_status "$D3_TOK" OFFLINE "driver-3")"
cancel_nonterminal "$P1_TOK" "passenger-1"
D1_ID="$(set_driver_status "$D1_TOK" AVAILABLE "driver-1")"
heartbeat_d1

# ---------- baseline: payment.inbox phải rỗng ----------
depth0="$(queue_depth payment.inbox)" || fail "rabbitmqctl list_queues lỗi"
[ "$depth0" -eq 0 ] || fail "payment.inbox có $depth0 message trước khi chạy (stack chưa ổn định?)"

T_START=$SECONDS

# ---------- bật failpoint qua compose override TẠM (không sửa file tracked) ----------
OVERRIDE_DIR="$(mktemp -d)" || fail "mktemp lỗi"
# Fail giữa chừng không được để payment-service mang failpoint hoặc nằm chết sau kill.
FAILPOINT_ACTIVE=0
restore_payment() {
  rm -rf "$OVERRIDE_DIR"
  if [ "$FAILPOINT_ACTIVE" = "1" ]; then
    docker compose --env-file .env -f deploy/compose.yaml up -d --no-build --force-recreate payment-service >/dev/null 2>&1 || true
  fi
}
trap restore_payment EXIT
cat > "$OVERRIDE_DIR/r02-override.yaml" <<EOF
services:
  payment-service:
    environment:
      APP_INBOX_FAILPOINT_DELAY_MS: "${FAILPOINT_MS}"
EOF
if command -v cygpath >/dev/null 2>&1; then
  OVERRIDE_WIN="$(cygpath -w "$OVERRIDE_DIR/r02-override.yaml")" || fail "cygpath lỗi (chạy trên Windows/Git Bash)"
else
  OVERRIDE_WIN="$OVERRIDE_DIR/r02-override.yaml" # Linux/macOS: path POSIX dùng trực tiếp
fi

echo "== recreate payment-service với failpoint ${FAILPOINT_MS}ms (override tạm: $OVERRIDE_WIN)"
FAILPOINT_ACTIVE=1
docker compose --env-file .env -f deploy/compose.yaml -f "$OVERRIDE_WIN" \
  up -d --no-build --force-recreate payment-service || fail "docker compose up (failpoint) lỗi"
wait_payment_ready

# ---------- complete một trip thật ----------
# Chốt thiết kế: heartbeat MỚI + re-affirm AVAILABLE ngay trước create. Reserve CAS của
# driver-service yêu cầu last_seen_at < 15 s (driver.heartbeat-stale-after-ms); recreate
# payment-service + readiness tốn ~10-20 s sau heartbeat lúc reset -> d1 stale đúng lúc
# reserve -> DRIVER_NOT_AVAILABLE, các attempt sau candidates=0 (bug run đầu R02).
D1_ID="$(set_driver_status "$D1_TOK" AVAILABLE "driver-1")"
heartbeat_d1
trip="$(create_trip "$P1_TOK" "r02")"
st="$(wait_state "$P1_TOK" "$trip" DRIVER_OFFERED 60)" || fail "trip $trip không về DRIVER_OFFERED (state cuối: $st)"

# ---------- accept (contract §3.2/§3.3): offer của d1 + body {"attempt":N}; gọi lại tới ACCEPTED ----------
line="$(http GET /api/drivers/me/offer "$D1_TOK")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "GET offer driver-1 -> HTTP $status: $body"
[ "$body" != "null" ] || fail "driver-1 không có offer (trip $trip chưa được offer?)"
offered_trip="$(json_field "$body" tripId)"
attempt="$(json_number "$body" attempt)"
[ -n "$attempt" ] || fail "offer driver-1 thiếu attempt: $body"
[ "$offered_trip" = "$trip" ] || fail "offer của driver-1 là trip khác ($offered_trip != $trip): $body"

accept_state=""
for _ in 1 2 3 4 5; do
  line="$(http POST "/api/trips/$trip/accept" "$D1_TOK" "{\"attempt\":$attempt}")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "202" ] || fail "accept $trip -> HTTP $status: $body"
  accept_state="$(json_field "$body" state)"
  [ "$accept_state" = "ACCEPTED" ] && break
  sleep 2
done
[ "$accept_state" = "ACCEPTED" ] || fail "accept chưa xác nhận sau 5 lần (state=${accept_state:-?}) — kiểm tra driver-service logs"

line="$(http POST "/api/trips/$trip/start" "$D1_TOK")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "start $trip -> HTTP $status: $body"

line="$(http POST "/api/trips/$trip/complete" "$D1_TOK")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "complete $trip -> HTTP $status: $body"

# ---------- chờ commit payments row (chứng minh DB commit đã xảy ra) ----------
count=0
for i in $(seq 1 30); do
  count="$(payments_count "$trip")" || fail "psql payment_db lỗi"
  [ "$count" -ge 1 ] && break
  sleep 1
done
[ "$count" -ge 1 ] || fail "không thấy payments row sau complete (TripCompleted không được consume?)"

# ---------- kill TRONG cửa sổ failpoint: sau commit, trước ACK ----------
sleep 3 # nằm trong cửa sổ ${FAILPOINT_MS}ms kể từ commit
docker kill "$PAYMENT_CONTAINER" || fail "docker kill $PAYMENT_CONTAINER lỗi"
echo "== killed payment-service SAU DB commit, TRƯỚC ACK (failpoint window)"

# message unacked phải bị broker requeue -> redelivery pending
depth=0
for i in $(seq 1 15); do
  depth="$(queue_depth payment.inbox)" || fail "rabbitmqctl list_queues lỗi"
  [ "$depth" -ge 1 ] && break
  sleep 1
done
[ "$depth" -ge 1 ] || fail "payment.inbox rỗng sau kill — không có message chờ redelivery (ACK đã gửi? cửa sổ lỗi không đúng?)"

# ---------- restart KHÔNG failpoint -> redelivery hit dedup ----------
docker compose --env-file .env -f deploy/compose.yaml \
  up -d --no-build --force-recreate payment-service || fail "docker compose up (no failpoint) lỗi"
FAILPOINT_ACTIVE=0
wait_payment_ready

depth=0
for i in $(seq 1 30); do
  depth="$(queue_depth payment.inbox)" || fail "rabbitmqctl list_queues lỗi"
  [ "$depth" -eq 0 ] && break
  sleep 2
done
[ "$depth" -eq 0 ] || fail "payment.inbox vẫn có $depth message sau redelivery (dedup không ACK?)"

# ---------- assertions: MỘT payment record, dedup log, trip SUCCEEDED ----------
count="$(payments_count "$trip")" || fail "psql payment_db lỗi"
[ "$count" -eq 1 ] || fail "số payments row cho trip = $count (kỳ vọng 1 — redelivery tạo trùng?)"

inbox_count="$(inbox_events_count "$trip")" || fail "psql inbox_events lỗi"
[ "$inbox_count" -eq 1 ] || fail "số inbox_events (payment-service) cho trip = $inbox_count (kỳ vọng 1)"

# Redelivery không được phát thêm event thanh toán, và trip chỉ ghi nhận kết quả một lần.
outbox_count="$(docker exec -e PGPASSWORD="$PAYMENT_DB_PASSWORD" ridehailing-postgres \
  psql -U payment_svc -d payment_db -Atc "SELECT count(*) FROM outbox WHERE aggregate_id = '$trip'")" || fail "psql outbox lỗi"
[ "$outbox_count" -eq 1 ] || fail "số outbox event thanh toán cho trip = $outbox_count (kỳ vọng 1)"
trip_payment_events="$(docker exec -e PGPASSWORD="$TRIP_DB_PASSWORD" ridehailing-postgres \
  psql -U trip_svc -d trip_db -Atc "SELECT count(*) FROM trip_events WHERE trip_id = '$trip' AND type IN ('PAYMENT_SUCCEEDED','PAYMENT_FAILED')")" || fail "psql trip_events lỗi"
[ "$trip_payment_events" -eq 1 ] || fail "số trip_event PAYMENT_* = $trip_payment_events (kỳ vọng 1)"

pstatus="$(trip_payment_status "$trip")" || fail "psql trip_db lỗi"
[ "$pstatus" = "SUCCEEDED" ] || fail "trip payment_status = $pstatus (kỳ vọng SUCCEEDED)"

DEDUP_LOG="$(docker logs --since 30m "$PAYMENT_CONTAINER" 2>&1 | grep -F "duplicate event skipped" | grep -F "$trip" || true)"
[ -n "$DEDUP_LOG" ] || fail "không tìm thấy log 'duplicate event skipped' cho $trip trong payment-service (redelivery không đi qua dedup?)"

# ---------- cleanup: driver-1 AVAILABLE sau finish ----------
line="$(http GET /api/drivers/me/offer "$D1_TOK")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "GET offer driver-1 -> HTTP $status: $body"
[ "$body" = "null" ] || fail "driver-1 vẫn có offer sau complete (body: $body)"
drv="$(driver_status_db "$D1_ID")" || fail "psql driver status lỗi"
[ "$drv" = "AVAILABLE" ] || fail "driver-1 không về AVAILABLE sau finish (status: $drv)"

echo "{\"tripId\":\"$trip\",\"failpoint_ms\":${FAILPOINT_MS},\"payments_rows\":$count,\"inbox_events_rows\":$inbox_count,\"outbox_events\":$outbox_count,\"trip_payment_events\":$trip_payment_events,\"payment_status\":\"$pstatus\",\"redelivery_pending_after_kill\":true,\"dedup_log_found\":true}" >> "$RESULTS_FILE"

DURATION=$((SECONDS - T_START))
{
  echo "R02 — Crash Payment sau DB commit nhưng trước ACK, restart (spec §13)"
  echo "BASE_URL=$BASE_URL failpoint=${FAILPOINT_MS}ms (test-only hook app.inbox.failpoint-delay-ms, compose override tạm)"
  echo "tripId: $trip"
  echo "payments rows = $count (kỳ vọng 1); inbox_events rows = $inbox_count; outbox events = $outbox_count; trip_event PAYMENT_* = $trip_payment_events; payment_status = $pstatus"
  echo "Redelivery pending sau kill: confirmed (payment.inbox depth >= 1 trước restart)"
  echo "duplicate event skipped log evidence:"
  printf '%s\n' "$DEDUP_LOG" | head -3
  echo "Total duration: ${DURATION} s"
} > "$SUMMARY_FILE"

echo "== R02 PASS — results: $RESULTS_FILE, summary: $SUMMARY_FILE"
