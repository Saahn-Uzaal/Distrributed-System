#!/usr/bin/env bash
# T26 — Seed 3 passengers + 3 drivers QUA PUBLIC API (Nginx :8080) — đúng ranh giới, không ghi DB trực tiếp.
# Idempotent: register trả 409 EMAIL_TAKEN nếu email đã tồn tại -> bỏ qua và login tiếp. Chạy lại nhiều lần an toàn.
# Không chạm volume Docker, không xóa dữ liệu (reset riêng: scripts/reset-demo.sh --confirm).
#
# Usage: bash scripts/seed-demo.sh [BASE_URL]     # default http://localhost:8080
# set -e (không pipefail): grep không match trong $() phải rơi vào fail() có ngữ cảnh, không exit im lặng.
set -eu

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"

# SEED_PASSWORD (dev-only) từ .env; fallback giá trị dev trong .env.example nếu thiếu.
SEED_PASSWORD="${SEED_PASSWORD:-}"
if [ -z "$SEED_PASSWORD" ] && [ -f "$REPO_ROOT/.env" ]; then
  SEED_PASSWORD="$(sed -n 's/^SEED_PASSWORD=//p' "$REPO_ROOT/.env" | tr -d '\r' | tail -n 1)"
fi
: "${SEED_PASSWORD:=SeedPass123!}"

# Demo users cố định -> seed chạy lại không tạo trùng (409 EMAIL_TAKEN).
PASS_EMAILS=("passenger-1@demo.local" "passenger-2@demo.local" "passenger-3@demo.local")
DRIVER_EMAILS=("driver-1@demo.local" "driver-2@demo.local" "driver-3@demo.local")

# Tọa độ demo quanh Hồ Gươm, Hà Nội. Pickup = điểm đón; drivers nằm trong radius matching 5000 m.
PICKUP_LAT=21.0345
PICKUP_LNG=105.8409
DRIVER_COORDS=("21.0348 105.8415" "21.0339 105.8401" "21.0352 105.8420")

fail() { echo "SEED FAIL: $*" >&2; exit 1; }

# http METHOD PATH [BEARER] [JSON_BODY] -> in ra "STATUS<TAB>BODY"
http() {
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

json_field() { # BODY FIELD -> giá trị string trong "FIELD":"..."
  sed -n 's/.*"'"$2"'":"\([^"]*\)".*/\1/p' <<<"$1" | head -1
}

echo "== seed-demo: BASE_URL=$BASE_URL"

# ---------- passengers ----------
for i in 0 1 2; do
  email="${PASS_EMAILS[$i]}"
  line="$(http POST /api/auth/register "" "{\"email\":\"$email\",\"password\":\"$SEED_PASSWORD\",\"fullName\":\"Passenger $((i+1))\",\"role\":\"PASSENGER\"}")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  if [ "$status" = "201" ]; then
    echo "registered passenger: $email"
  elif [ "$status" = "409" ] && grep -q '"EMAIL_TAKEN"' <<<"$body"; then
    echo "exists       passenger: $email (skip)"
  else
    fail "register $email -> HTTP $status: $body"
  fi
  sleep 0.2 # rate limit /api/auth/ (nginx burst=5, 10r/s)
done

# ---------- drivers ----------
for i in 0 1 2; do
  email="${DRIVER_EMAILS[$i]}"
  line="$(http POST /api/auth/register "" "{\"email\":\"$email\",\"password\":\"$SEED_PASSWORD\",\"fullName\":\"Driver $((i+1))\",\"role\":\"DRIVER\"}")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  if [ "$status" = "201" ]; then
    echo "registered driver:    $email"
  elif [ "$status" = "409" ] && grep -q '"EMAIL_TAKEN"' <<<"$body"; then
    echo "exists       driver:    $email (skip)"
  else
    fail "register $email -> HTTP $status: $body"
  fi
  sleep 0.2

  line="$(http POST /api/auth/login "" "{\"email\":\"$email\",\"password\":\"$SEED_PASSWORD\"}")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "200" ] || fail "login $email -> HTTP $status: $body"
  tok="$(json_field "$body" accessToken)"
  [ -n "$tok" ] || fail "login $email: không có accessToken trong response"

  # Profile idempotent (201 lần đầu, 200 replay) — contract §3.2.
  line="$(http POST /api/drivers/me/profile "$tok" "{\"displayName\":\"Driver $((i+1))\",\"vehicle\":{\"plateNumber\":\"81-DEMO-$((i+1))\",\"vehicleType\":\"MOTORBIKE\"}}")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  { [ "$status" = "201" ] || [ "$status" = "200" ]; } || fail "profile $email -> HTTP $status: $body"

  line="$(http PUT /api/drivers/me/availability "$tok" '{"status":"AVAILABLE"}')"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  if [ "$status" = "409" ] && grep -q '"STATE_CONFLICT"' <<<"$body"; then
    # Driver đang RESERVED/BUSY với một trip (contract §3.2: mọi PUT availability lúc đó trả 409) — giữ nguyên.
    echo "WARNING: $email đang RESERVED/BUSY — giữ trạng thái hiện tại (trip kẹt: chạy reset-demo.sh --confirm)"
  else
    [ "$status" = "200" ] || fail "availability $email -> HTTP $status: $body"
  fi

  read -r dlat dlng <<<"${DRIVER_COORDS[$i]}"
  line="$(http PUT /api/drivers/me/location "$tok" "{\"latitude\":$dlat,\"longitude\":$dlng}")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "200" ] || fail "location $email -> HTTP $status: $body"

  echo "seeded         driver:    $email (AVAILABLE @ $dlat,$dlng)"
done

echo "== seed-demo OK: 3 passengers + 3 drivers sẵn sàng qua $BASE_URL"
echo "   pickup demo: lat=$PICKUP_LAT lng=$PICKUP_LNG (smoke-trip.sh dùng cùng tọa độ này)"
