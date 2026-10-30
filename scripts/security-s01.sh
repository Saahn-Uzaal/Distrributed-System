#!/usr/bin/env bash
# T32 — S01 (spec §13): Token sai, thiếu token, gọi API khác role/owner -> 401/403/404 đúng chính sách; dữ liệu không đổi.
# Probes qua Nginx :8080:
#   P1  no token GET /api/trips                          -> 401 UNAUTHORIZED
#   P2  garbage token GET /api/trips                     -> 401 UNAUTHORIZED
#   P3  passenger PUT /api/drivers/me/availability       -> 403 FORBIDDEN (role DRIVER required)
#   P4  passenger GET /api/drivers/me/offer              -> 403 FORBIDDEN
#   P5  driver POST /api/trips (valid body + idem key)   -> 403 FORBIDDEN (role PASSENGER required)
#   P6  driver POST /api/trips/{id}/cancel của passenger -> 403 FORBIDDEN
#   P7  stranger GET /api/trips/{id}                     -> 404 NOT_FOUND (owner policy)
#   P8  stranger GET /api/trips/{id}/events              -> 404 NOT_FOUND
#   P9  login wrong password                             -> 401 INVALID_CREDENTIALS
#   P10 token alg=none / P11 chữ ký sai / P12 alg=HS256  -> 401 UNAUTHORIZED
# Trên trip ĐANG offer (P13–P17), không được đổi trip và không lộ state cho người ngoài:
#   P13 passenger lạ cancel                              -> 404 NOT_FOUND
#   P14 driver lạ accept / P15 driver lạ reject          -> 409 NOT_OFFERED_DRIVER
#   P16 driver lạ start / P17 driver lạ complete         -> 409 NOT_ASSIGNED_DRIVER
# Dữ liệu không đổi: snapshot từng row trips + drivers (md5 các cột nghiệp vụ, kèm count để đọc) trước/sau —
# read-only psql bằng tài khoản của chính từng service (không cross-service access của application, L02).
# Raw results: tests/security/s01-results.jsonl + s01-summary.txt.
set -eu

fail() { echo "S01 FAIL: $*" >&2; exit 1; }

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

PASS1="passenger-1@demo.local"; PASS2="passenger-2@demo.local"
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

trips_snapshot() { # read-only, tài khoản của chính trip-service -> "state=count;...|md5 từng row"
  local counts hash
  counts="$(docker exec -e PGPASSWORD="$TRIP_DB_PASSWORD" ridehailing-postgres \
    psql -U trip_svc -d trip_db -Atc "SELECT state || '=' || count(*) FROM trips GROUP BY state ORDER BY state" \
    | paste -sd';' -)"
  hash="$(docker exec -e PGPASSWORD="$TRIP_DB_PASSWORD" ridehailing-postgres \
    psql -U trip_svc -d trip_db -Atc "SELECT md5(string_agg(concat_ws('|', id, state, version, matching_attempt, driver_user_id, payment_status, updated_at), ',' ORDER BY id)) FROM trips")"
  echo "$counts|$hash"
}

drivers_snapshot() { # read-only, tài khoản của chính driver-service -> "status=count;...|md5 từng row"
  local counts hash
  counts="$(docker exec -e PGPASSWORD="$DRIVER_DB_PASSWORD" ridehailing-postgres \
    psql -U driver_svc -d driver_db -Atc "SELECT status || '=' || count(*) FROM drivers GROUP BY status ORDER BY status" \
    | paste -sd';' -)"
  hash="$(docker exec -e PGPASSWORD="$DRIVER_DB_PASSWORD" ridehailing-postgres \
    psql -U driver_svc -d driver_db -Atc "SELECT md5(string_agg(concat_ws('|', id, status, latitude, longitude, last_seen_at, current_reservation_id, current_trip_id), ',' ORDER BY id)) FROM drivers")"
  echo "$counts|$hash"
}

trip_row() { # trip_id -> state|version|driver_user_id (read-only)
  docker exec -e PGPASSWORD="$TRIP_DB_PASSWORD" ridehailing-postgres \
    psql -U trip_svc -d trip_db -Atc "SELECT concat_ws('|', state, version, driver_user_id) FROM trips WHERE id = '$1'"
}

b64url() { printf '%s' "$1" | openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# Như probe, thêm: body lỗi không được nêu state của trip (người ngoài không được biết).
probe_no_state_leak() { # id method path bearer body expected_status expected_code label
  local line status body
  probe "$@"
  line="$(http "$2" "$3" "${4:-}" "${5:-}")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  if grep -q '"state"' <<<"$body"; then fail "probe $1 ($8): lộ state của trip — body: $body"; fi
}

probe() { # id method path bearer body expected_status expected_code label
  local id="$1" method="$2" path="$3" bearer="${4:-}" data="${5:-}" exp_status="$6" exp_code="$7" label="$8"
  local line status body code
  line="$(http "$method" "$path" "$bearer" "$data")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "$exp_status" ] || fail "probe $id ($label): HTTP $status != $exp_status — body: $body"
  if [ -n "$exp_code" ]; then
    code="$(json_field "$body" code)"
    [ "$code" = "$exp_code" ] || fail "probe $id ($label): code '$code' != '$exp_code' — body: $body"
  fi
  echo "{\"probe\":\"$id\",\"label\":\"$label\",\"method\":\"$method\",\"path\":\"$path\",\"status\":$status,\"code\":\"${code:-}\"}" >> "$RESULTS_FILE"
}

echo "== S01: BASE_URL=$BASE_URL (token sai/thiếu, role/owner sai -> 401/403/404; dữ liệu không đổi)"

# ---------- logins MỘT lần, reuse token (tránh rate limit /api/auth) ----------
P1_TOK="$(login "$PASS1")"; sleep 0.2
P2_TOK="$(login "$PASS2")"; sleep 0.2
D1_TOK="$(login "$D1")";    sleep 0.2
D2_TOK="$(login "$D2")"; sleep 0.2
D3_TOK="$(login "$D3")"

RESULTS_DIR="$REPO_ROOT/tests/security"
mkdir -p "$RESULTS_DIR"
RESULTS_FILE="$RESULTS_DIR/s01-results.jsonl"
SUMMARY_FILE="$RESULTS_DIR/s01-summary.txt"
: > "$RESULTS_FILE"

TRIP_BODY="{\"pickup\":{\"latitude\":$PICKUP_LAT,\"longitude\":$PICKUP_LNG},\"destination\":{\"latitude\":$DEST_LAT,\"longitude\":$DEST_LNG}}"

# ---------- reset state + trip thật của passenger-1 cho probes owner/role ----------
# Chốt thiết kế: D3 OFFLINE để chỉ d1 là candidate (deterministic, tránh offer bay tới
# driver khác trong cửa sổ setup ~6 s).
D2_ID="$(set_driver_status "$D2_TOK" OFFLINE "driver-2")"
D3_ID="$(set_driver_status "$D3_TOK" OFFLINE "driver-3")"
cancel_nonterminal "$P1_TOK" "passenger-1"
D1_ID="$(set_driver_status "$D1_TOK" AVAILABLE "driver-1")"
heartbeat_d1

trip="$(create_trip "$P1_TOK" "s01-setup")"
# hủy NGAY để trip về CANCELLED (terminal) — matching chưa kịp offer (~6 s); nếu offer bay tới,
# stale event bị skip theo T20 và driver vẫn về AVAILABLE.
line="$(http POST "/api/trips/$trip/cancel" "$P1_TOK")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
# contract §3.3: 200 (SEARCHING, hủy ngay) hoặc 202 (DRIVER_OFFERED, CANCELLING transient)
case "$status" in 200|202) ;; *) fail "cancel setup trip $trip -> HTTP $status: $body" ;; esac
final="$(wait_state "$P1_TOK" "$trip" CANCELLED 30)" || fail "setup trip không về CANCELLED (state cuối: ${final:-?})"

# ---------- snapshot TRƯỚC các probes ----------
TRIPS_BEFORE="$(trips_snapshot)" || fail "psql trips_snapshot lỗi"
DRIVERS_BEFORE="$(drivers_snapshot)" || fail "psql drivers_snapshot lỗi"

echo "== probes bắt đầu (trip setup: $trip, state CANCELLED)"

probe P1 GET  /api/trips "" "" 401 UNAUTHORIZED "no token"
probe P2 GET  /api/trips "garbage.token.here" "" 401 UNAUTHORIZED "garbage token"
probe P3 PUT  /api/drivers/me/availability "$P1_TOK" "{\"status\":\"AVAILABLE\"}" 403 FORBIDDEN "passenger -> driver endpoint (state-changing)"
probe P4 GET  /api/drivers/me/offer "$P1_TOK" "" 403 FORBIDDEN "passenger -> driver offer"
# Role check chạy trước body/idempotency-key validation nên không cần Idempotency-Key cho probe này.
probe P5 POST /api/trips "$D1_TOK" "$TRIP_BODY" 403 FORBIDDEN "driver -> create trip (valid body)"
probe P6 POST "/api/trips/$trip/cancel" "$D1_TOK" "" 403 FORBIDDEN "driver -> cancel passenger's trip"
probe P7 GET  "/api/trips/$trip" "$P2_TOK" "" 404 NOT_FOUND "stranger -> other's trip detail"
probe P8 GET  "/api/trips/$trip/events" "$P2_TOK" "" 404 NOT_FOUND "stranger -> other's trip events"

# P9: login wrong password (không dùng token)
line="$(http POST /api/auth/login "" "{\"email\":\"$PASS1\",\"password\":\"WrongPass999!\"}")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "401" ] || fail "probe P9 (login wrong password): HTTP $status != 401 — body: $body"
code="$(json_field "$body" code)"
# contract §3.1: login sai credentials -> 401 INVALID_CREDENTIALS (cùng message cho email/password sai)
[ "$code" = "INVALID_CREDENTIALS" ] || fail "probe P9: code '$code' != 'INVALID_CREDENTIALS' — body: $body"
echo "{\"probe\":\"P9\",\"label\":\"login wrong password\",\"method\":\"POST\",\"path\":\"/api/auth/login\",\"status\":$status,\"code\":\"${code:-}\"}" >> "$RESULTS_FILE"

# Token giả mạo: giữ nguyên payload hợp lệ của passenger-1, thay header/chữ ký.
TOK_HDR="$(printf '%s' "$P1_TOK" | cut -d. -f1)"; TOK_PAYLOAD="$(printf '%s' "$P1_TOK" | cut -d. -f2)"
probe P10 GET /api/trips "$(b64url '{"alg":"none","typ":"JWT"}').$TOK_PAYLOAD." "" 401 UNAUTHORIZED "token alg=none"
probe P11 GET /api/trips "$TOK_HDR.$TOK_PAYLOAD.$(b64url not-a-real-signature)" "" 401 UNAUTHORIZED "token chữ ký sai"
probe P12 GET /api/trips "$(b64url '{"alg":"HS256","typ":"JWT"}').$TOK_PAYLOAD.$(b64url x)" "" 401 UNAUTHORIZED "token alg=HS256"

# ---------- snapshot SAU các probes + assert dữ liệu không đổi ----------
TRIPS_AFTER="$(trips_snapshot)" || fail "psql trips_snapshot lỗi"
DRIVERS_AFTER="$(drivers_snapshot)" || fail "psql drivers_snapshot lỗi"
[ "$TRIPS_BEFORE" = "$TRIPS_AFTER" ] || fail "dữ liệu trips ĐỔI sau probes: before='$TRIPS_BEFORE' after='$TRIPS_AFTER'"
[ "$DRIVERS_BEFORE" = "$DRIVERS_AFTER" ] || fail "dữ liệu drivers ĐỔI sau probes: before='$DRIVERS_BEFORE' after='$DRIVERS_AFTER'"

# ---------- người ngoài gọi API mutating trên trip ĐANG offer ----------
heartbeat_d1
active_trip="$(create_trip "$P1_TOK" "s01-active")"
st="$(wait_state "$P1_TOK" "$active_trip" DRIVER_OFFERED 60)" || fail "trip $active_trip không về DRIVER_OFFERED (state cuối: $st)"
ROW_BEFORE="$(trip_row "$active_trip")" || fail "psql trip_row lỗi"

probe P13 POST "/api/trips/$active_trip/cancel" "$P2_TOK" "" 404 NOT_FOUND "passenger lạ cancel trip đang offer"
probe_no_state_leak P14 POST "/api/trips/$active_trip/accept" "$D2_TOK" '{"attempt":1}' 409 NOT_OFFERED_DRIVER "driver lạ accept"
probe_no_state_leak P15 POST "/api/trips/$active_trip/reject" "$D2_TOK" '{"attempt":1}' 409 NOT_OFFERED_DRIVER "driver lạ reject"
probe_no_state_leak P16 POST "/api/trips/$active_trip/start" "$D2_TOK" "" 409 NOT_ASSIGNED_DRIVER "driver lạ start"
probe_no_state_leak P17 POST "/api/trips/$active_trip/complete" "$D2_TOK" "" 409 NOT_ASSIGNED_DRIVER "driver lạ complete"

ROW_AFTER="$(trip_row "$active_trip")" || fail "psql trip_row lỗi"
[ "$ROW_BEFORE" = "$ROW_AFTER" ] || fail "trip $active_trip ĐỔI sau probes: before='$ROW_BEFORE' after='$ROW_AFTER'"

line="$(http POST "/api/trips/$active_trip/cancel" "$P1_TOK")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
case "$status" in 200|202) ;; *) fail "cleanup cancel $active_trip -> HTTP $status: $body" ;; esac
final="$(wait_state "$P1_TOK" "$active_trip" CANCELLED 30)" || fail "trip $active_trip không về CANCELLED (state cuối: ${final:-?})"

# ---------- cleanup: driver-1 AVAILABLE (offer null) ----------
line="$(http GET /api/drivers/me/offer "$D1_TOK")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "GET offer driver-1 -> HTTP $status: $body"
[ "$body" = "null" ] || fail "driver-1 vẫn có offer sau cleanup (body: $body)"

{
  echo "S01 — Token sai, thiếu token, gọi API khác role/owner (spec §13)"
  echo "BASE_URL=$BASE_URL"
  echo "Setup trip (passenger-1, CANCELLED): $trip"
  echo "Probes: P1 no token=401; P2 garbage token=401; P3 passenger->driver PUT availability=403;"
  echo "        P4 passenger->driver GET offer=403; P5 driver->POST trips=403; P6 driver->cancel=403;"
  echo "        P7 stranger GET trip=404; P8 stranger GET events=404; P9 wrong password login=401;"
  echo "        P10-P12 token alg=none/chữ ký sai/HS256=401."
  echo "Trip đang offer $active_trip: P13 passenger lạ cancel=404; P14/P15 driver lạ accept/reject=409 NOT_OFFERED_DRIVER;"
  echo "        P16/P17 driver lạ start/complete=409 NOT_ASSIGNED_DRIVER; không lộ state; row trước=sau ('$ROW_BEFORE')."
  echo "Data unchanged: trips snapshot before/after identical ('$TRIPS_BEFORE'); drivers snapshot identical ('$DRIVERS_BEFORE')."
} > "$SUMMARY_FILE"

echo "== S01 PASS — results: $RESULTS_FILE, summary: $SUMMARY_FILE"
