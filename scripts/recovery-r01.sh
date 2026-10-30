#!/usr/bin/env bash
# T31 — R01 (spec §13): Stop Matching trước khi consume, tạo trip, start lại.
#   Phase A: matching STOPPED -> TripRequested nằm trong queue matching.inbox; start matching
#            TRONG deadline (mặc định 120 s) -> trip tiếp tục tới DRIVER_OFFERED (queue giữ message).
#   Phase B: matching STOPPED QUÁ deadline -> NO_DRIVER kết thúc hợp lệ; start matching với driver-1
#            heartbeat tươi -> matching đề xuất thật -> DriverProposed stale bị skip (audit
#            stale_event_skipped, không reservation nào được tạo), trip GIỮ NGUYÊN NO_DRIVER,
#            driver vẫn AVAILABLE (không reservation nào được tạo).
# Raw results: tests/recovery/r01-results.jsonl + r01-summary.txt.
# Yêu cầu: stack Docker Compose (T29) đang chạy + seed demo đã chạy; KHÔNG đổi config deadline.
set -eu

fail() { echo "R01 FAIL: $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"

SEED_PASSWORD="${SEED_PASSWORD:-}"
if [ -z "$SEED_PASSWORD" ] && [ -f "$REPO_ROOT/.env" ]; then
  SEED_PASSWORD="$(sed -n 's/^SEED_PASSWORD=//p' "$REPO_ROOT/.env" | tr -d '\r' | tail -n 1)"
fi
: "${SEED_PASSWORD:=SeedPass123!}"

DRIVER_DB_PASSWORD="${DRIVER_DB_PASSWORD:-}"
if [ -z "$DRIVER_DB_PASSWORD" ] && [ -f "$REPO_ROOT/.env" ]; then
  DRIVER_DB_PASSWORD="$(sed -n 's/^DRIVER_DB_PASSWORD=//p' "$REPO_ROOT/.env" | tr -d '\r' | tail -n 1)"
fi
[ -n "$DRIVER_DB_PASSWORD" ] || fail "thiếu DRIVER_DB_PASSWORD trong .env"

MATCHING_CONTAINER="ridehailing-matching-service"
RABBITMQ_CONTAINER="ridehailing-rabbitmq"
TRIP_CONTAINER="ridehailing-trip-service"
MATCHING_READINESS_URL="http://127.0.0.1:8084/actuator/health/readiness"

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

wait_terminal() { # tok trip_id max_seconds -> echo final state; return 1 nếu timeout
  local deadline=$((SECONDS + $3)) st="" line status body
  while [ "$SECONDS" -lt "$deadline" ]; do
    line="$(http GET "/api/trips/$2" "$1")"
    status="${line%%$'\t'*}"; body="${line#*$'\t'}"
    [ "$status" = "200" ] || fail "GET trip $2 -> HTTP $status: $body"
    st="$(json_field "$body" state)"
    case "$st" in CANCELLED|COMPLETED|NO_DRIVER) echo "$st"; return 0 ;; esac
    sleep 2
  done
  echo "${st:-?}"
  return 1
}

cancel_nonterminal() { # tok label — hủy mọi trip chưa terminal của user (T12-6: tối đa 1)
  local line status body pairs pair tid st final
  line="$(http GET /api/trips "$1")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "200" ] || fail "GET trips $2 -> HTTP $status: $body"
  pairs="$(grep -o '"tripId":"[0-9a-f-]*","state":"[A-Z_]*"' <<<"$body" || true)"
  while IFS= read -r pair; do
    [ -z "$pair" ] && continue
    tid="$(sed 's/.*"tripId":"\([^"]*\)".*/\1/' <<<"$pair")"
    st="$(sed 's/.*"state":"\([A-Z_]*\)".*/\1/' <<<"$pair")"
    case "$st" in
      SEARCHING|DRIVER_OFFERED)
        line="$(http POST "/api/trips/$tid/cancel" "$1")"
        status="${line%%$'\t'*}"; body="${line#*$'\t'}"
        [ "$status" = "202" ] || fail "cancel $tid ($2) -> HTTP $status: $body"
        ;;
      CANCELLING) : ;; # transient — chỉ chờ terminal
    esac
    if ! final="$(wait_terminal "$1" "$tid" 30)"; then
      fail "trip $tid ($2) không về terminal sau cancel (state cuối: $final)"
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

wait_matching_ready() {
  local i code
  for i in $(seq 1 40); do
    code="$(curl -s -o /dev/null -w '%{http_code}' "$MATCHING_READINESS_URL" || true)"
    [ "$code" = "200" ] && return 0
    sleep 3
  done
  fail "matching-service không ready sau 120 s (docker logs $MATCHING_CONTAINER | tail -50 để debug)"
}

driver_status_db() { # driver_id -> status (read-only, tài khoản của chính driver-service)
  docker exec -e PGPASSWORD="$DRIVER_DB_PASSWORD" ridehailing-postgres \
    psql -U driver_svc -d driver_db -Atc "SELECT status FROM drivers WHERE id = '$1'"
}

# Fail giữa chừng không được để matching-service dừng lại ảnh hưởng các lần chạy sau.
trap 'docker start "$MATCHING_CONTAINER" >/dev/null 2>&1 || true' EXIT

echo "== R01: BASE_URL=$BASE_URL (deadline mặc định 120 s — không đổi config)"

# ---------- logins MỘT lần, reuse token qua các phase (tránh rate limit /api/auth) ----------
P1_TOK="$(login "$PASS1")"; sleep 0.2
D1_TOK="$(login "$D1")";    sleep 0.2
D2_TOK="$(login "$D2")";    sleep 0.2
D3_TOK="$(login "$D3")"

RESULTS_DIR="$REPO_ROOT/tests/recovery"
mkdir -p "$RESULTS_DIR"
RESULTS_FILE="$RESULTS_DIR/r01-results.jsonl"
SUMMARY_FILE="$RESULTS_DIR/r01-summary.txt"
: > "$RESULTS_FILE"

TRIP_BODY="{\"pickup\":{\"latitude\":$PICKUP_LAT,\"longitude\":$PICKUP_LNG},\"destination\":{\"latitude\":$DEST_LAT,\"longitude\":$DEST_LNG}}"

T_START=$SECONDS

# ================= PHASE A: queue giữ message, trip tiếp tục trong deadline =================
echo "== Phase A: stop matching -> create trip -> verify queue depth >= 1 -> start matching"

D2_ID="$(set_driver_status "$D2_TOK" OFFLINE "driver-2")"
D3_ID="$(set_driver_status "$D3_TOK" OFFLINE "driver-3")"
cancel_nonterminal "$P1_TOK" "passenger-1"
D1_ID="$(set_driver_status "$D1_TOK" AVAILABLE "driver-1")"
heartbeat_d1

# baseline: purge message cũ từ các run trước để depth bắt đầu = 0
docker exec "$RABBITMQ_CONTAINER" rabbitmqctl purge_queue matching.inbox >/dev/null || fail "purge matching.inbox lỗi"

A_T0=$SECONDS
docker stop "$MATCHING_CONTAINER" || fail "docker stop $MATCHING_CONTAINER lỗi"
sleep 2 # đảm bảo listener đã down trước khi publish TripRequested

tripA="$(create_trip "$P1_TOK" "phaseA")"
echo "Phase A: trip created $tripA (matching STOPPED)"

depth=0
for i in $(seq 1 15); do
  depth="$(queue_depth matching.inbox)" || fail "rabbitmqctl list_queues lỗi"
  [ "$depth" -ge 1 ] && break
  sleep 1
done
[ "$depth" -ge 1 ] || fail "Phase A: matching.inbox rỗng sau khi tạo trip — TripRequested không được giữ trong queue (depth=$depth)"
A_DEPTH="$depth"

# Matching khởi động mất ~10 s: giữ heartbeat tươi (< 15 s) để driver-1 còn eligible khi TripRequested được xử lý.
docker start "$MATCHING_CONTAINER" || fail "docker start $MATCHING_CONTAINER lỗi"
stA=""
for i in $(seq 1 30); do
  heartbeat_d1
  line="$(http GET "/api/trips/$tripA" "$P1_TOK")"
  stA="$(json_field "${line#*$'\t'}" state)"
  case "$stA" in
    DRIVER_OFFERED) break ;;
    NO_DRIVER|CANCELLED|COMPLETED) fail "Phase A: trip $tripA về $stA thay vì DRIVER_OFFERED sau khi start matching trong deadline" ;;
  esac
  sleep 2
done
[ "$stA" = "DRIVER_OFFERED" ] || fail "Phase A: trip $tripA không về DRIVER_OFFERED sau khi start matching trong deadline (state cuối: $stA)"
wait_matching_ready
echo "Phase A: trip tiếp tục tới DRIVER_OFFERED sau khi start matching trong deadline"

# cleanup Phase A: cancel -> CANCELLED, driver-1 AVAILABLE
line="$(http POST "/api/trips/$tripA/cancel" "$P1_TOK")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "202" ] || fail "Phase A cleanup cancel $tripA -> HTTP $status: $body"
finalA="$(wait_terminal "$P1_TOK" "$tripA" 30)" || fail "Phase A: trip không về terminal sau cancel (state cuối: $finalA)"
[ "$finalA" = "CANCELLED" ] || fail "Phase A: state cuối $finalA != CANCELLED"

line="$(http GET /api/drivers/me/offer "$D1_TOK")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "GET offer driver-1 -> HTTP $status: $body"
[ "$body" = "null" ] || fail "driver-1 vẫn có offer sau cleanup Phase A (body: $body)"
drvA="$(driver_status_db "$D1_ID")" || fail "psql driver status lỗi"
[ "$drvA" = "AVAILABLE" ] || fail "driver-1 không về AVAILABLE sau cleanup Phase A (status: $drvA)"

echo "{\"phase\":\"A\",\"tripId\":\"$tripA\",\"queue_depth_while_stopped\":$A_DEPTH,\"final_state\":\"$stA\",\"cleanup_state\":\"$finalA\",\"duration_s\":$((SECONDS - A_T0))}" >> "$RESULTS_FILE"

# ================= PHASE B: deadline hết -> NO_DRIVER hợp lệ; stale event bị skip =================
echo "== Phase B: stop matching -> wait past 120 s deadline -> NO_DRIVER -> start matching -> stale skip"

cancel_nonterminal "$P1_TOK" "passenger-1"
D1_ID="$(set_driver_status "$D1_TOK" AVAILABLE "driver-1")"
heartbeat_d1
docker exec "$RABBITMQ_CONTAINER" rabbitmqctl purge_queue matching.inbox >/dev/null || fail "purge matching.inbox lỗi"

B_T0=$SECONDS
docker stop "$MATCHING_CONTAINER" || fail "docker stop $MATCHING_CONTAINER lỗi"
sleep 2

tripB="$(create_trip "$P1_TOK" "phaseB")"
echo "Phase B: trip created $tripB (matching STOPPED, deadline ~120 s)"

stB="$(wait_state "$P1_TOK" "$tripB" NO_DRIVER 200)" || fail "Phase B: trip $tripB không về NO_DRIVER sau khi quá deadline với matching stopped (state cuối: $stB)"
echo "Phase B: NO_DRIVER hợp lệ sau deadline (matching im lặng) — kết thúc hợp lệ"

# Driver-1 phải còn eligible (heartbeat < 15 s) để matching ĐỀ XUẤT thật: đây là đường rủi ro —
# DriverProposed tới sau khi trip đã NO_DRIVER không được reserve. Heartbeat suốt lúc matching khởi động.
docker start "$MATCHING_CONTAINER" || fail "docker start $MATCHING_CONTAINER lỗi"
STALE_LOG=""
for i in $(seq 1 45); do
  heartbeat_d1
  STALE_LOG="$(docker logs --since 15m "$TRIP_CONTAINER" 2>&1 | grep -F "stale event skipped eventType=DriverProposed" | grep -F "$tripB" || true)"
  [ -n "$STALE_LOG" ] && break
  sleep 2
done
[ -n "$STALE_LOG" ] || fail "Phase B: không thấy trip-service skip DriverProposed stale cho $tripB (matching không đề xuất? heartbeat?)"
wait_matching_ready

depth=0
for i in $(seq 1 30); do
  depth="$(queue_depth matching.inbox)" || fail "rabbitmqctl list_queues lỗi"
  [ "$depth" -eq 0 ] && break
  sleep 2
done
[ "$depth" -eq 0 ] || fail "Phase B: matching.inbox vẫn có $depth message sau khi start matching (stale event không được consume?)"

reservationsB="$(docker exec -e PGPASSWORD="$DRIVER_DB_PASSWORD" ridehailing-postgres \
  psql -U driver_svc -d driver_db -Atc "SELECT count(*) FROM reservations WHERE trip_id = '$tripB' AND state <> 'RELEASED'")" || fail "psql reservations lỗi"
[ "$reservationsB" -eq 0 ] || fail "Phase B: DriverProposed stale đã tạo $reservationsB reservation cho $tripB"

# trip GIỮ NGUYÊN NO_DRIVER; driver-1 vẫn AVAILABLE (không reservation nào được tạo)
line="$(http GET "/api/trips/$tripB" "$P1_TOK")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "GET trip $tripB -> HTTP $status: $body"
stB_final="$(json_field "$body" state)"
[ "$stB_final" = "NO_DRIVER" ] || fail "Phase B: trip $tripB bị đổi trạng thái sau stale event (state: $stB_final, kỳ vọng NO_DRIVER)"

drvB="$(driver_status_db "$D1_ID")" || fail "psql driver status lỗi"
[ "$drvB" = "AVAILABLE" ] || fail "Phase B: driver-1 không còn AVAILABLE sau stale event (status: $drvB — reservation bị tạo?)"

echo "{\"phase\":\"B\",\"tripId\":\"$tripB\",\"no_driver_after_deadline\":true,\"stale_event_type\":\"DriverProposed\",\"reservations_created\":$reservationsB,\"final_state\":\"$stB_final\",\"driver_status\":\"$drvB\",\"duration_s\":$((SECONDS - B_T0))}" >> "$RESULTS_FILE"

DURATION=$((SECONDS - T_START))
{
  echo "R01 — Stop Matching trước khi consume, tạo trip, start lại (spec §13)"
  echo "BASE_URL=$BASE_URL"
  echo "Phase A: matching stopped -> queue depth=$A_DEPTH giữ TripRequested; start matching trong deadline -> DRIVER_OFFERED; cleanup CANCELLED + driver AVAILABLE."
  echo "Phase B: matching stopped quá deadline 120 s -> NO_DRIVER (kết thúc hợp lệ); start matching với heartbeat tươi -> DriverProposed stale bị skip (audit stale_event_skipped, $reservationsB reservation), trip GIỮ NGUYÊN NO_DRIVER, driver AVAILABLE."
  echo "stale_event_skipped log evidence:"
  printf '%s\n' "$STALE_LOG" | head -3
  echo "Total duration: ${DURATION} s"
} > "$SUMMARY_FILE"

echo "== R01 PASS (Phase A + Phase B) — results: $RESULTS_FILE, summary: $SUMMARY_FILE"
