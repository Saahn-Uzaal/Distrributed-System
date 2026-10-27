#!/usr/bin/env bash
# T26 — Reset demo data QUA PUBLIC API (Nginx :8080) để seed/smoke chạy lại được.
#   - Hủy mọi trip chưa terminal của 3 passenger demo ở trạng thái CANCEL-được (SEARCHING, DRIVER_OFFERED;
#     CANCELLING = lần cancel trước chưa xác nhận release -> gọi lại cancel để tiếp tục, T20).
#     (ACCEPTED/IN_PROGRESS không cancel được qua API — báo rõ nếu gặp.)
#   - Đưa 3 driver demo về AVAILABLE + tọa độ seed (heartbeat mới).
# KHÔNG xóa volume Docker, KHÔNG ghi chéo DB service khác — chỉ dùng ranh giới API.
# Bắt buộc cờ --confirm để chạy thật; không có --confirm chỉ in kế hoạch và exit 2.
#
# Usage: bash scripts/reset-demo.sh --confirm [BASE_URL]     # default http://localhost:8080
# set -e (không pipefail): grep không match trong $() phải rơi vào fail() có ngữ cảnh, không exit im lặng.
set -eu

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

CONFIRM=0
if [ "${1:-}" = "--confirm" ]; then
  CONFIRM=1
  BASE_URL="${2:-${BASE_URL:-http://localhost:8080}}"
else
  BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
fi
[ "$CONFIRM" = "1" ] || {
  echo "reset-demo: cần cờ --confirm để chạy thật (an toàn cho dữ liệu demo)."
  echo "Kế hoạch: hủy trip SEARCHING/DRIVER_OFFERED của passenger-1..3@demo.local; đưa driver-1..3@demo.local về AVAILABLE + tọa độ seed."
  echo "Chạy: bash scripts/reset-demo.sh --confirm $BASE_URL"
  exit 2
}

SEED_PASSWORD="${SEED_PASSWORD:-}"
if [ -z "$SEED_PASSWORD" ] && [ -f "$REPO_ROOT/.env" ]; then
  SEED_PASSWORD="$(sed -n 's/^SEED_PASSWORD=//p' "$REPO_ROOT/.env" | tr -d '\r' | tail -n 1)"
fi
: "${SEED_PASSWORD:=SeedPass123!}"

PASS_EMAILS=("passenger-1@demo.local" "passenger-2@demo.local" "passenger-3@demo.local")
DRIVER_EMAILS=("driver-1@demo.local" "driver-2@demo.local" "driver-3@demo.local")
DRIVER_COORDS=("21.0348 105.8415" "21.0339 105.8401" "21.0352 105.8420")

fail() { echo "RESET FAIL: $*" >&2; exit 1; }

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

login() {
  local email="$1" line status body tok
  line="$(http POST /api/auth/login "" "{\"email\":\"$email\",\"password\":\"$SEED_PASSWORD\"}")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "200" ] || fail "login $email -> HTTP $status: $body (chạy scripts/seed-demo.sh trước)"
  tok="$(json_field "$body" accessToken)"
  [ -n "$tok" ] || fail "login $email: không có accessToken"
  echo "$tok"
}

echo "== reset-demo (--confirm): BASE_URL=$BASE_URL"

# ---------- hủy trip chưa terminal mà passenger cancel được ----------
for i in 0 1 2; do
  email="${PASS_EMAILS[$i]}"
  tok="$(login "$email")"
  sleep 0.2
  line="$(http GET "/api/trips?page=0&size=100" "$tok")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "200" ] || fail "GET trips $email -> HTTP $status: $body"

  # Tách từng item {tripId, state,...}: mỗi item là một cặp "tripId":"...","state":"..."
  while IFS=$'\t' read -r tid tstate; do
    [ -n "$tid" ] || continue
    case "$tstate" in
      SEARCHING|DRIVER_OFFERED|CANCELLING)
        line="$(http POST "/api/trips/$tid/cancel" "$tok")"
        s2="${line%%$'\t'*}"; b2="${line#*$'\t'}"
        [ "$s2" = "200" ] || [ "$s2" = "202" ] || fail "cancel $tid ($email, state=$tstate) -> HTTP $s2: $b2"
        new_state="$(json_field "$b2" state)"
        if [ "$new_state" = "CANCELLED" ]; then
          echo "canceled trip $tid ($email, was $tstate)"
        else
          # 202 CANCELLING: release chưa xác nhận — worker saga tiếp tục; chạy lại reset để kiểm tra.
          echo "WARNING: trip $tid ($email) đang ${new_state:-?} — release chưa xong, chạy lại reset-demo.sh --confirm sau vài giây"
        fi
        ;;
      ACCEPTED|IN_PROGRESS)
        echo "WARNING: trip $tid của $email ở $tstate — API không cho passenger cancel sau accept; hoàn thành thủ công (driver start/complete) hoặc reset DB dev nếu cần."
        ;;
    esac
  done < <(grep -o '"tripId":"[0-9a-f-]*","state":"[A-Z_]*"' <<<"$body" | sed 's/"tripId":"\([^"]*\)","state":"\([^"]*\)"/\1\t\2/')
done

# ---------- đưa drivers về AVAILABLE + tọa độ seed ----------
for i in 0 1 2; do
  email="${DRIVER_EMAILS[$i]}"
  tok="$(login "$email")"
  sleep 0.2
  line="$(http PUT /api/drivers/me/availability "$tok" '{"status":"AVAILABLE"}')"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  if [ "$status" = "409" ] && grep -q '"STATE_CONFLICT"' <<<"$body"; then
    # RESERVED/BUSY (offer của passenger khác, hoặc trip ACCEPTED/IN_PROGRESS) — không ép đổi; vẫn refresh tọa độ.
    echo "WARNING: $email đang RESERVED/BUSY — không đổi availability"
  else
    [ "$status" = "200" ] || fail "availability $email -> HTTP $status: $body"
  fi
  read -r dlat dlng <<<"${DRIVER_COORDS[$i]}"
  line="$(http PUT /api/drivers/me/location "$tok" "{\"latitude\":$dlat,\"longitude\":$dlng}")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "200" ] || fail "location $email -> HTTP $status: $body"
  echo "driver $email -> location @ $dlat,$dlng"
done

echo "== reset-demo OK: demo data sẵn sàng cho seed/smoke chạy lại"
