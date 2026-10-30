#!/usr/bin/env bash
# T32 — S02 (spec §13): Tọa độ sai, payload quá lớn, transition sai -> 400/413/409; không lưu dữ liệu sai.
# Probes qua Nginx :8080:
#   Q1 quote tọa độ lat=999                    -> 400 INVALID_INPUT
#   C1 create thiếu destination                -> 400 INVALID_INPUT
#   C2 create lat=-95 (ngoài [-90,90])         -> 400 INVALID_INPUT
#   B1 create body ~200 KiB (> 64 KiB nginx)   -> 413 PAYLOAD_TOO_LARGE
#   T1 start trên trip DRIVER_OFFERED          -> 409 STATE_CONFLICT (state phải ACCEPTED)
#   T2 accept bởi driver KHÔNG được offer      -> 409 NOT_OFFERED_DRIVER
#   T3 cancel trên trip COMPLETED              -> 409 STATE_CONFLICT (terminal khác CANCELLED)
# Không lưu dữ liệu sai: snapshot trips trước/sau — chỉ MỘT trip hợp lệ mới (COMPLETED); không có row
# nào pickup_lat ngoài [-90,90] hay destination NULL. Read-only psql bằng tài khoản của chính service (L02).
# Raw results: tests/security/s02-results.jsonl + s02-summary.txt.
set -eu

fail() { echo "S02 FAIL: $*" >&2; exit 1; }

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

TRIP_DB_PASSWORD="${TRIP_DB_PASSWORD:-}"
if [ -z "$TRIP_DB_PASSWORD" ] && [ -f "$REPO_ROOT/.env" ]; then
  TRIP_DB_PASSWORD="$(sed -n 's/^TRIP_DB_PASSWORD=//p' "$REPO_ROOT/.env" | tr -d '\r' | tail -n 1)"
fi
[ -n "$TRIP_DB_PASSWORD" ] || fail "thiếu TRIP_DB_PASSWORD trong .env"

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

post_create() { # tok body -> "STATUS<TAB>BODY" (Idempotency-Key mới)
  local idem tmp code
  idem="$(gen_uuid)"
  tmp="$(mktemp)"
  code="$(curl -s --max-time 10 -X POST "$BASE_URL/api/trips" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $idem" -d "$2" -o "$tmp" -w '%{http_code}')" || fail "POST /api/trips lỗi (network)"
  printf '%s\t%s' "$code" "$(cat "$tmp")"; rm -f "$tmp"
}

post_create_file() { # tok file_body -> "STATUS<TAB>BODY" (-d @file; body lớn vượt argv limit Windows)
  local idem tmp code
  idem="$(gen_uuid)"
  tmp="$(mktemp)"
  code="$(curl -s --max-time 10 -X POST "$BASE_URL/api/trips" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $idem" -d "@$2" -o "$tmp" -w '%{http_code}')" || fail "POST /api/trips lỗi (network)"
  printf '%s\t%s' "$code" "$(cat "$tmp")"; rm -f "$tmp"
}

trips_snapshot() { # read-only, tài khoản của chính trip-service -> "state=count;..."
  docker exec -e PGPASSWORD="$TRIP_DB_PASSWORD" ridehailing-postgres \
    psql -U trip_svc -d trip_db -Atc "SELECT state || '=' || count(*) FROM trips GROUP BY state ORDER BY state" \
    | paste -sd';' -
}

drivers_snapshot() { # read-only, tài khoản của chính driver-service -> "status=count;..."
  docker exec -e PGPASSWORD="$DRIVER_DB_PASSWORD" ridehailing-postgres \
    psql -U driver_svc -d driver_db -Atc "SELECT status || '=' || count(*) FROM drivers GROUP BY status ORDER BY status" \
    | paste -sd';' -
}

trips_total() { # snapshot -> total rows
  printf '%s\n' "$1" | awk -F';' '{for(i=1;i<=NF;i++){split($i,a,"="); s+=a[2]}} END{print s+0}'
}

trips_state_count() { # snapshot state -> count (0 nếu vắng)
  local v
  v="$(printf '%s\n' "$1" | tr ';' '\n' | awk -F'=' -v s="$2" '$1==s{print $2; exit}')"
  echo "${v:-0}"
}

bad_rows() { # read-only: row trips có tọa độ thiếu hoặc ngoài khoảng (pickup + destination)
  docker exec -e PGPASSWORD="$TRIP_DB_PASSWORD" ridehailing-postgres \
    psql -U trip_svc -d trip_db -Atc "SELECT count(*) FROM trips WHERE pickup_lat IS NULL OR pickup_lng IS NULL OR destination_lat IS NULL OR destination_lng IS NULL OR abs(pickup_lat) > 90 OR abs(destination_lat) > 90 OR abs(pickup_lng) > 180 OR abs(destination_lng) > 180"
}

probe() { # id label method path bearer body expected_status expected_code
  local id="$1" label="$2" method="$3" path="$4" bearer="${5:-}" data="${6:-}" exp_status="$7" exp_code="$8"
  local line status body code
  line="$(http "$method" "$path" "$bearer" "$data")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "$exp_status" ] || fail "probe $id ($label): HTTP $status != $exp_status — body: $body"
  if [ -n "$exp_code" ]; then
    code="$(json_field "$body" code)"
    [ "$code" = "$exp_code" ] || fail "probe $id ($label): code '$code' != '$exp_code' — body: $body"
  fi
  echo "{\"probe\":\"$id\",\"label\":\"$label\",\"status\":$status,\"code\":\"${code:-}\"}" >> "$RESULTS_FILE"
}

echo "== S02: BASE_URL=$BASE_URL (tọa độ sai / payload quá lớn / transition sai -> 400/413/409)"

# ---------- logins MỘT lần, reuse token (tránh rate limit /api/auth) ----------
P1_TOK="$(login "$PASS1")"; sleep 0.2
D1_TOK="$(login "$D1")";    sleep 0.2
D2_TOK="$(login "$D2")"; sleep 0.2
D3_TOK="$(login "$D3")"

RESULTS_DIR="$REPO_ROOT/tests/security"
mkdir -p "$RESULTS_DIR"
RESULTS_FILE="$RESULTS_DIR/s02-results.jsonl"
SUMMARY_FILE="$RESULTS_DIR/s02-summary.txt"
: > "$RESULTS_FILE"

TRIP_BODY="{\"pickup\":{\"latitude\":$PICKUP_LAT,\"longitude\":$PICKUP_LNG},\"destination\":{\"latitude\":$DEST_LAT,\"longitude\":$DEST_LNG}}"

# ---------- reset state ----------
# Chốt thiết kế: D3 OFFLINE để chỉ d1 là candidate (deterministic cho positive control).
D2_ID="$(set_driver_status "$D2_TOK" OFFLINE "driver-2")"
D3_ID="$(set_driver_status "$D3_TOK" OFFLINE "driver-3")"
cancel_nonterminal "$P1_TOK" "passenger-1"
D1_ID="$(set_driver_status "$D1_TOK" AVAILABLE "driver-1")"
heartbeat_d1

# ---------- snapshot TRƯỚC ----------
TRIPS_BEFORE="$(trips_snapshot)" || fail "psql trips_snapshot lỗi"
DRIVERS_BEFORE="$(drivers_snapshot)" || fail "psql drivers_snapshot lỗi"
BAD_BEFORE="$(bad_rows)" || fail "psql bad_rows lỗi"
[ "$BAD_BEFORE" -eq 0 ] || fail "DB đã có $BAD_BEFORE row tọa độ sai trước khi chạy (dữ liệu nền không sạch?)"

# ---------- probes 400: tọa độ / body sai ----------
probe Q1 "quote lat=999" POST /api/trips/quote "$P1_TOK" \
  "{\"pickup\":{\"latitude\":999,\"longitude\":$PICKUP_LNG},\"destination\":{\"latitude\":$DEST_LAT,\"longitude\":$DEST_LNG}}" \
  400 INVALID_INPUT

line="$(post_create "$P1_TOK" "{\"pickup\":{\"latitude\":$PICKUP_LAT,\"longitude\":$PICKUP_LNG}}")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "400" ] || fail "probe C1 (create thiếu destination): HTTP $status != 400 — body: $body"
code="$(json_field "$body" code)"
[ "$code" = "INVALID_INPUT" ] || fail "probe C1: code '$code' != 'INVALID_INPUT' — body: $body"
echo "{\"probe\":\"C1\",\"label\":\"create missing destination\",\"status\":$status,\"code\":\"${code:-}\"}" >> "$RESULTS_FILE"

line="$(post_create "$P1_TOK" "{\"pickup\":{\"latitude\":-95,\"longitude\":$PICKUP_LNG},\"destination\":{\"latitude\":$DEST_LAT,\"longitude\":$DEST_LNG}}")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "400" ] || fail "probe C2 (create lat=-95): HTTP $status != 400 — body: $body"
code="$(json_field "$body" code)"
[ "$code" = "INVALID_INPUT" ] || fail "probe C2: code '$code' != 'INVALID_INPUT' — body: $body"
echo "{\"probe\":\"C2\",\"label\":\"create lat=-95 out of range\",\"status\":$status,\"code\":\"${code:-}\"}" >> "$RESULTS_FILE"

# ---------- probe 413: payload quá lớn (> 64 KiB nginx) ----------
# Chốt thiết kế: body ~200 KiB vượt argv limit của curl trên Windows/Git Bash -> ghi ra file tạm,
# gửi bằng -d @file (không truyền chuỗi qua command line).
BIG_BODY_FILE="$(mktemp)" || fail "mktemp lỗi"
{
  printf '{"pickup":{"latitude":%s,"longitude":%s},"destination":{"latitude":%s,"longitude":%s},"pad":"' \
    "$PICKUP_LAT" "$PICKUP_LNG" "$DEST_LAT" "$DEST_LNG"
  head -c 196608 /dev/zero | tr '\0' 'A'
  printf '"}'
} > "$BIG_BODY_FILE" || fail "sinh body quá lớn lỗi"
line="$(post_create_file "$P1_TOK" "$BIG_BODY_FILE")"
rm -f "$BIG_BODY_FILE"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "413" ] || fail "probe B1 (body ~200 KiB): HTTP $status != 413 — body: $(head -c 200 <<<"$body")"
code="$(json_field "$body" code)"
[ "$code" = "PAYLOAD_TOO_LARGE" ] || fail "probe B1: code '$code' != 'PAYLOAD_TOO_LARGE' — body: $(head -c 200 <<<"$body")"
echo "{\"probe\":\"B1\",\"label\":\"oversized payload ~200KiB\",\"status\":$status,\"code\":\"${code:-}\"}" >> "$RESULTS_FILE"

# ---------- trip thật + probes transition sai (409) ----------
# Chốt thiết kế: heartbeat MỚI ngay trước create — reserve CAS yêu cầu last_seen_at < 15 s
# (driver.heartbeat-stale-after-ms); các probe Q1/C1/C2/B1 ở giữa tốn vài giây, không đảm bảo.
D1_ID="$(set_driver_status "$D1_TOK" AVAILABLE "driver-1")"
heartbeat_d1
line="$(post_create "$P1_TOK" "$TRIP_BODY")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "202" ] || fail "create trip hợp lệ -> HTTP $status: $body"
trip="$(json_field "$body" tripId)"
[ -n "$trip" ] || fail "không có tripId trong response: $body"

st="$(wait_state "$P1_TOK" "$trip" DRIVER_OFFERED 60)" || fail "trip $trip không về DRIVER_OFFERED (state cuối: $st)"

probe T1 "start trên DRIVER_OFFERED" POST "/api/trips/$trip/start" "$D1_TOK" "" 409 STATE_CONFLICT
probe T2 "accept bởi driver không được offer" POST "/api/trips/$trip/accept" "$D2_TOK" "{\"attempt\":1}" 409 NOT_OFFERED_DRIVER

# positive control: đúng driver, đúng state -> 202 (contract §3.3: gọi lại tới ACCEPTED)
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
  [ "$status" = "202" ] || fail "positive accept $trip -> HTTP $status: $body"
  accept_state="$(json_field "$body" state)"
  [ "$accept_state" = "ACCEPTED" ] && break
  sleep 2
done
[ "$accept_state" = "ACCEPTED" ] || fail "positive accept chưa xác nhận sau 5 lần (state=${accept_state:-?}) — kiểm tra driver-service logs"

line="$(http POST "/api/trips/$trip/start" "$D1_TOK")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "positive start $trip -> HTTP $status: $body"

line="$(http POST "/api/trips/$trip/complete" "$D1_TOK")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "positive complete $trip -> HTTP $status: $body"
probe T3 "cancel trên COMPLETED" POST "/api/trips/$trip/cancel" "$P1_TOK" "" 409 STATE_CONFLICT

# ---------- snapshot SAU + assertions không lưu dữ liệu sai ----------
TRIPS_AFTER="$(trips_snapshot)" || fail "psql trips_snapshot lỗi"
DRIVERS_AFTER="$(drivers_snapshot)" || fail "psql drivers_snapshot lỗi"
BAD_AFTER="$(bad_rows)" || fail "psql bad_rows lỗi"

TOTAL_BEFORE="$(trips_total "$TRIPS_BEFORE")"
TOTAL_AFTER="$(trips_total "$TRIPS_AFTER")"
COMPLETED_BEFORE="$(trips_state_count "$TRIPS_BEFORE" COMPLETED)"
COMPLETED_AFTER="$(trips_state_count "$TRIPS_AFTER" COMPLETED)"

[ "$BAD_AFTER" -eq 0 ] || fail "DB có $BAD_AFTER row tọa độ sai sau các probe (dữ liệu sai bị lưu?)"
[ "$TOTAL_AFTER" -eq $((TOTAL_BEFORE + 1)) ] || fail "total trips after=$TOTAL_AFTER != before+1 ($TOTAL_BEFORE) — create invalid tạo row?"
[ "$COMPLETED_AFTER" -eq $((COMPLETED_BEFORE + 1)) ] || fail "completed trips after=$COMPLETED_AFTER != before+1 ($COMPLETED_BEFORE)"

# ---------- cleanup: driver-1 AVAILABLE sau finish ----------
line="$(http GET /api/drivers/me/offer "$D1_TOK")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "GET offer driver-1 -> HTTP $status: $body"
[ "$body" = "null" ] || fail "driver-1 vẫn có offer sau complete (body: $body)"

{
  echo "S02 — Tọa độ sai, payload quá lớn, transition sai (spec §13)"
  echo "BASE_URL=$BASE_URL"
  echo "Probes: Q1 quote lat=999=400; C1 create thiếu destination=400; C2 create lat=-95=400;"
  echo "        B1 body ~200KiB=413 PAYLOAD_TOO_LARGE; T1 start on DRIVER_OFFERED=409 STATE_CONFLICT;"
  echo "        T2 accept wrong driver=409 NOT_OFFERED_DRIVER; T3 cancel on COMPLETED=409 STATE_CONFLICT."
  echo "Trip hợp lệ: $trip -> COMPLETED; positive accept=202, start/complete=200."
  echo "No bad data: bad_rows after=$BAD_AFTER (kỳ vọng 0); total trips +1 only ($TOTAL_BEFORE -> $TOTAL_AFTER); completed +1 ($COMPLETED_BEFORE -> $COMPLETED_AFTER)."
} > "$SUMMARY_FILE"

echo "== S02 PASS — results: $RESULTS_FILE, summary: $SUMMARY_FILE"
