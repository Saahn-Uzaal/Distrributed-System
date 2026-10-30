#!/usr/bin/env bash
# T26 — Smoke API trip HOÀN CHỈNH qua services thật (Nginx :8080):
#   quote -> create -> matching/offer -> accept -> start -> complete -> payment result
#   -> verify notifications + trip event history.
# Yêu cầu: 5 services local đang chạy + seed đã chạy (scripts/seed-demo.sh).
# Chạy lặp được: mỗi lần tạo Idempotency-Key mới; trip COMPLETED không chặn create kế tiếp.
# Nếu passenger kẹt trip chưa terminal (SEARCHING/DRIVER_OFFERED) -> chạy scripts/reset-demo.sh --confirm.
#
# Usage: bash scripts/smoke-trip.sh [BASE_URL]     # default http://localhost:8080
# set -e (không pipefail): grep không match trong $() phải rơi vào fail() có ngữ cảnh, không exit im lặng.
set -eu

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"

SEED_PASSWORD="${SEED_PASSWORD:-}"
if [ -z "$SEED_PASSWORD" ] && [ -f "$REPO_ROOT/.env" ]; then
  SEED_PASSWORD="$(sed -n 's/^SEED_PASSWORD=//p' "$REPO_ROOT/.env" | tr -d '\r' | tail -n 1)"
fi
: "${SEED_PASSWORD:=SeedPass123!}"

PASS_EMAIL="passenger-1@demo.local"
DRIVER_EMAILS=("driver-1@demo.local" "driver-2@demo.local" "driver-3@demo.local")

# Cùng tọa độ demo với seed-demo.sh (drivers nằm trong radius matching 5000 m của pickup).
PICKUP_LAT=21.0345
PICKUP_LNG=105.8409
DEST_LAT=21.0260
DEST_LNG=105.8570

fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }

http() { # METHOD PATH [BEARER] [JSON_BODY] [EXTRA_HEADER] -> "STATUS<TAB>BODY"
  local method="$1" path="$2" bearer="${3:-}" data="${4:-}" extra="${5:-}"
  local tmp status
  tmp="$(mktemp)"
  local args=(-s --max-time 10 -X "$method" "$BASE_URL$path")
  [ -n "$bearer" ] && args+=(-H "Authorization: Bearer $bearer")
  [ -n "$data" ] && args+=(-H 'Content-Type: application/json' -d "$data")
  [ -n "$extra" ] && args+=(-H "$extra")
  status="$(curl "${args[@]}" -o "$tmp" -w '%{http_code}')" || fail "HTTP lỗi (network) $method $path"
  printf '%s\t%s' "$status" "$(cat "$tmp")"
  rm -f "$tmp"
}

json_field() { sed -n 's/.*"'"$2"'":"\([^"]*\)".*/\1/p' <<<"$1" | head -1; }
json_number() { sed -n 's/.*"'"$2"'":\([0-9][0-9]*\).*/\1/p' <<<"$1" | head -1; }

gen_uuid() {
  local h
  h="$(openssl rand -hex 16)" || fail "openssl không khả dụng (cần cho Idempotency-Key)"
  echo "${h:0:8}-${h:8:4}-${h:12:4}-${h:16:4}-${h:20:12}"
}

echo "== smoke-trip: BASE_URL=$BASE_URL"
T0=$SECONDS

# ---------- 1) login (seed phải chạy trước) ----------
login() {
  local email="$1" line status body tok
  line="$(http POST /api/auth/login "" "{\"email\":\"$email\",\"password\":\"$SEED_PASSWORD\"}")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "200" ] || fail "login $email -> HTTP $status: $body (chạy scripts/seed-demo.sh trước)"
  tok="$(json_field "$body" accessToken)"
  [ -n "$tok" ] || fail "login $email: không có accessToken"
  echo "$tok"
}

pass_tok="$(login "$PASS_EMAIL")"
driver_toks=()
for email in "${DRIVER_EMAILS[@]}"; do
  driver_toks+=("$(login "$email")")
  sleep 0.2 # rate limit /api/auth/ (nginx burst=5, 10r/s)
done

# ---------- 2) heartbeat drivers (matching cần last_seen_at < 15 s) ----------
refresh_heartbeats() {
  local i line status body
  local coords=("21.0348 105.8415" "21.0339 105.8401" "21.0352 105.8420")
  for i in 0 1 2; do
    read -r dlat dlng <<<"${coords[$i]}"
    line="$(http PUT /api/drivers/me/location "${driver_toks[$i]}" "{\"latitude\":$dlat,\"longitude\":$dlng}")"
    status="${line%%$'\t'*}"; body="${line#*$'\t'}"
    [ "$status" = "200" ] || fail "heartbeat driver-$((i+1)) -> HTTP $status: $body"
  done
}

refresh_heartbeats

# ---------- 3) quote ----------
quote_body="{\"pickup\":{\"latitude\":$PICKUP_LAT,\"longitude\":$PICKUP_LNG},\"destination\":{\"latitude\":$DEST_LAT,\"longitude\":$DEST_LNG}}"
line="$(http POST /api/trips/quote "$pass_tok" "$quote_body")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "quote -> HTTP $status: $body"
distanceM="$(json_number "$body" distanceM)"
fareVnd="$(json_number "$body" fareVnd)"
echo "quote: distanceM=$distanceM fareVnd=$fareVnd"

# ---------- 4) create trip (Idempotency-Key mới mỗi lần chạy) ----------
idem_key="$(gen_uuid)"
line="$(http POST /api/trips "$pass_tok" "$quote_body" "Idempotency-Key: $idem_key")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
if [ "$status" = "409" ] && grep -q '"ACTIVE_TRIP_EXISTS"' <<<"$body"; then
  fail "passenger có trip chưa terminal — chạy scripts/reset-demo.sh --confirm rồi thử lại"
fi
[ "$status" = "202" ] || fail "create -> HTTP $status: $body"
trip_id="$(json_field "$body" tripId)"
echo "created trip=$trip_id (SEARCHING, idempotency key mới)"

# ---------- 5) chờ matching -> DRIVER_OFFERED (refresh heartbeat mỗi ~10 s) ----------
state=""
deadline=$((SECONDS + 90))
tick=0
while [ "$SECONDS" -lt "$deadline" ]; do
  line="$(http GET "/api/trips/$trip_id" "$pass_tok")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "200" ] || fail "GET trip -> HTTP $status: $body"
  state="$(json_field "$body" state)"
  case "$state" in
    DRIVER_OFFERED) break ;;
    NO_DRIVER|CANCELLED) fail "trip chuyển $state trước khi có offer (kiểm tra matching-service + heartbeat drivers)" ;;
  esac
  tick=$((tick + 1))
  if [ $((tick % 5)) -eq 0 ]; then refresh_heartbeats; fi
  sleep 2
done
[ "$state" = "DRIVER_OFFERED" ] || fail "timeout chờ DRIVER_OFFERED (state cuối: ${state:-?}) — kiểm tra matching-service logs + heartbeat drivers"
echo "offer nhận sau ~$((SECONDS - T0)) s: state=DRIVER_OFFERED"

# ---------- 6) tìm driver được offer (GET /api/drivers/me/offer, contract §3.2) ----------
offered_idx=-1
attempt=""
for i in 0 1 2; do
  line="$(http GET /api/drivers/me/offer "${driver_toks[$i]}")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "200" ] || fail "GET offer driver-$((i+1)) -> HTTP $status: $body"
  if [ "$body" != "null" ]; then
    offered_trip="$(json_field "$body" tripId)"
    attempt="$(json_number "$body" attempt)"
    if [ "$offered_trip" = "$trip_id" ]; then
      offered_idx=$i
      break
    fi
  fi
done
[ "$offered_idx" -ge 0 ] || fail "không driver nào có offer cho trip $trip_id (offer đã hết hạn? kiểm tra logs)"
echo "driver-$((offered_idx+1)) được offer (attempt=$attempt)"

# ---------- 7) accept -> ACCEPTED ----------
# 202 + state DRIVER_OFFERED = confirm chưa rõ kết quả (contract §3.3) -> gọi lại accept để tiếp tục.
accept_state=""
for _ in 1 2 3 4 5; do
  line="$(http POST "/api/trips/$trip_id/accept" "${driver_toks[$offered_idx]}" "{\"attempt\":$attempt}")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "202" ] || fail "accept -> HTTP $status: $body"
  accept_state="$(json_field "$body" state)"
  [ "$accept_state" = "ACCEPTED" ] && break
  sleep 2
done
[ "$accept_state" = "ACCEPTED" ] || fail "accept chưa xác nhận sau 5 lần (state=${accept_state:-?}) — kiểm tra driver-service"
echo "accepted (202, ACCEPTED)"

# ---------- 8) start -> IN_PROGRESS ----------
line="$(http POST "/api/trips/$trip_id/start" "${driver_toks[$offered_idx]}")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "start -> HTTP $status: $body"
echo "started (200)"

# ---------- 9) complete -> COMPLETED + payment PENDING ----------
line="$(http POST "/api/trips/$trip_id/complete" "${driver_toks[$offered_idx]}")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "complete -> HTTP $status: $body"
echo "completed (200)"

# ---------- 10) chờ payment result (payment-service simulate, default success) ----------
pay_status=""
deadline=$((SECONDS + 60))
while [ "$SECONDS" -lt "$deadline" ]; do
  line="$(http GET "/api/trips/$trip_id" "$pass_tok")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "200" ] || fail "GET trip (payment poll) -> HTTP $status: $body"
  pay_status="$(json_field "$body" paymentStatus)"
  case "$pay_status" in SUCCEEDED|FAILED) break ;; esac
  sleep 2
done
[ -n "$pay_status" ] && [ "$pay_status" != "PENDING" ] || fail "timeout chờ payment result (paymentStatus=${pay_status:-?}) — kiểm tra payment-service logs"
echo "payment result: $pay_status sau ~$((SECONDS - T0)) s tổng"

# ---------- 11) verify notifications (T25) ----------
line="$(http GET "/api/notifications?page=0&size=100" "$pass_tok")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "GET notifications (passenger) -> HTTP $status: $body"
for t in TRIP_OFFERED TRIP_ACCEPTED TRIP_COMPLETED; do
  n="$(grep -o "\"tripId\":\"$trip_id\",\"type\":\"$t\"" <<<"$body" | wc -l)"
  [ "$n" -ge 1 ] || fail "passenger thiếu notification $t cho trip $trip_id"
done
if [ "$pay_status" = "SUCCEEDED" ]; then want_pay=PAYMENT_SUCCEEDED; else want_pay=PAYMENT_FAILED; fi
n="$(grep -o "\"tripId\":\"$trip_id\",\"type\":\"$want_pay\"" <<<"$body" | wc -l)"
[ "$n" -ge 1 ] || fail "passenger thiếu notification $want_pay cho trip $trip_id"

line="$(http GET "/api/notifications?page=0&size=100" "${driver_toks[$offered_idx]}")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "GET notifications (driver) -> HTTP $status: $body"
for t in OFFER_RECEIVED TRIP_ACCEPTED TRIP_COMPLETED; do
  n="$(grep -o "\"tripId\":\"$trip_id\",\"type\":\"$t\"" <<<"$body" | wc -l)"
  [ "$n" -ge 1 ] || fail "driver thiếu notification $t cho trip $trip_id"
done
echo "notifications OK (passenger: TRIP_OFFERED/TRIP_ACCEPTED/TRIP_COMPLETED/$want_pay; driver: OFFER_RECEIVED/TRIP_ACCEPTED/TRIP_COMPLETED)"

# ---------- 12) verify trip event history (T25) ----------
line="$(http GET "/api/trips/$trip_id/events?page=0&size=50" "$pass_tok")"
status="${line%%$'\t'*}"; body="${line#*$'\t'}"
[ "$status" = "200" ] || fail "GET events -> HTTP $status: $body"
for t in TRIP_CREATED DRIVER_OFFERED TRIP_ACCEPTED TRIP_STARTED TRIP_COMPLETED; do
  grep -q "\"type\":\"$t\"" <<<"$body" || fail "events thiếu $t cho trip $trip_id"
done
grep -q "\"type\":\"$want_pay\"" <<<"$body" || fail "events thiếu $want_pay cho trip $trip_id"
echo "event history OK (TRIP_CREATED/DRIVER_OFFERED/TRIP_ACCEPTED/TRIP_STARTED/TRIP_COMPLETED/$want_pay)"

echo "== SMOKE PASS: trip $trip_id hoàn chỉnh (SEARCHING -> ... -> COMPLETED, payment $pay_status) trong ~$((SECONDS - T0)) s"
