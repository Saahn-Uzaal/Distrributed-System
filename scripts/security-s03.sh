#!/usr/bin/env bash
# T32 (bổ sung) — S03, phần còn lại của spec §20.6 dòng T32 mà S01/S02 chưa kiểm tra:
#   CORS: hệ thống chỉ phục vụ same-origin qua Nginx, nên KHÔNG origin lạ nào được nhận Access-Control-Allow-*;
#         same-origin vẫn dùng được (nếu không thì "không có header" chỉ là do API hỏng).
#   Internal credential: /internal/** của driver-service, gọi thẳng port 127.0.0.1:8082: không token, sai token,
#         JWT người dùng đều bị từ chối, token đúng được phục vụ; lệnh ghi bị từ chối không để lại dữ liệu.
#   Login rate limit: dồn dập qua Nginx -> có 429 RATE_LIMITED, không 5xx; hết cửa sổ thì đăng nhập lại được.
# Chỉ đọc hoặc bị từ chối, không đổi dữ liệu nghiệp vụ. Raw results: tests/security/s03-results.jsonl + s03-summary.txt.
# Scan secret: scripts/scan-tracked-secrets.sh (job riêng trong CI vì cần git repo).
#
# Usage: bash scripts/security-s03.sh [BASE_URL]     # default http://localhost:8080
#   DRIVER_URL (default http://localhost:8082), INTERNAL_API_TOKEN (default từ .env)
set -eu

fail() { echo "S03 FAIL: $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
DRIVER_URL="${DRIVER_URL:-http://localhost:8082}"

env_value() { sed -n "s/^$1=//p" "$REPO_ROOT/.env" 2>/dev/null | tr -d '\r' | tail -n 1; }
SEED_PASSWORD="${SEED_PASSWORD:-$(env_value SEED_PASSWORD)}"
INTERNAL_API_TOKEN="${INTERNAL_API_TOKEN:-$(env_value INTERNAL_API_TOKEN)}"
DRIVER_DB_PASSWORD="${DRIVER_DB_PASSWORD:-$(env_value DRIVER_DB_PASSWORD)}"
[ -n "$SEED_PASSWORD" ] || fail "thiếu SEED_PASSWORD trong .env"
[ -n "$INTERNAL_API_TOKEN" ] || fail "thiếu INTERNAL_API_TOKEN trong .env"
[ -n "$DRIVER_DB_PASSWORD" ] || fail "thiếu DRIVER_DB_PASSWORD trong .env"

RESULTS_DIR="$REPO_ROOT/tests/security"
RESULTS_FILE="$RESULTS_DIR/s03-results.jsonl"
SUMMARY_FILE="$RESULTS_DIR/s03-summary.txt"
mkdir -p "$RESULTS_DIR"
: > "$RESULTS_FILE"

EVIL_ORIGIN="https://evil.example"
CANDIDATES="/internal/drivers/candidates?lat=21.0345&lng=105.8409"

record() { # id label status [extra-json-fields]
  echo "{\"probe\":\"$1\",\"label\":\"$2\",\"status\":\"$3\"${4:+,$4}}" >> "$RESULTS_FILE"
}

status_of() { curl -s -m 10 -o /dev/null -w '%{http_code}' "$@"; }

no_cors_headers() { # id label curl-args... : gọi với Origin lạ, fail nếu có Access-Control-Allow-*
  local id="$1" label="$2" headers status
  shift 2
  headers="$(curl -s -m 10 -D - -o /dev/null -H "Origin: $EVIL_ORIGIN" "$@")"
  status="$(printf '%s\n' "$headers" | sed -n '1s/^HTTP[^ ]* \([0-9]*\).*/\1/p')"
  [ -n "$status" ] || fail "$id ($label): không nhận được response"
  if printf '%s\n' "$headers" | grep -qi '^access-control-allow'; then
    fail "$id ($label): origin lạ nhận header CORS: $(printf '%s\n' "$headers" | grep -i '^access-control-allow' | tr -d '\r' | tr '\n' ' ')"
  fi
  record "$id" "$label" "$status" '"access_control_headers":0'
  echo "$id $label -> HTTP $status, không có Access-Control-Allow-*"
}

echo "== S03: BASE_URL=$BASE_URL DRIVER_URL=$DRIVER_URL"

# ---------- CORS ----------
LOGIN_BODY="{\"email\":\"passenger-1@demo.local\",\"password\":\"$SEED_PASSWORD\"}"
login_resp="$(curl -s -m 10 -w '\n%{http_code}' -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' -d "$LOGIN_BODY")"
login_status="${login_resp##*$'\n'}"
[ "$login_status" = "200" ] || fail "login passenger-1 -> HTTP $login_status (chạy scripts/seed-demo.sh trước)"
P1_TOK="$(sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p' <<<"$login_resp" | head -1)"
[ -n "$P1_TOK" ] || fail "login passenger-1: không có accessToken"

no_cors_headers C1 "preflight POST /api/trips" -X OPTIONS "$BASE_URL/api/trips" -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: authorization,content-type'
no_cors_headers C2 "preflight POST /api/auth/login" -X OPTIONS "$BASE_URL/api/auth/login" -H 'Access-Control-Request-Method: POST'
no_cors_headers C3 "GET /api/trips kèm token" "$BASE_URL/api/trips" -H "Authorization: Bearer $P1_TOK"
no_cors_headers C4 "preflight thẳng driver-service" -X OPTIONS "$DRIVER_URL/api/drivers/me/offer" -H 'Access-Control-Request-Method: GET'

# Same-origin vẫn hoạt động: trình duyệt gửi Origin trùng host khi POST (đây là cách UI gọi API).
sleep 0.3
same_origin="$(status_of -X POST "$BASE_URL/api/auth/login" -H "Origin: $BASE_URL" -H 'Content-Type: application/json' -d "$LOGIN_BODY")"
[ "$same_origin" = "200" ] || fail "C5 same-origin POST login -> HTTP $same_origin (kỳ vọng 200)"
record C5 "same-origin POST login" "$same_origin"
echo "C5 same-origin POST login -> HTTP $same_origin"

# ---------- internal credential ----------
expect_rejected() { # id label curl-args...
  local id="$1" label="$2" code
  shift 2
  code="$(status_of "$@")"
  case "$code" in
    401|403) ;;
    *) fail "$id ($label): HTTP $code (kỳ vọng 401/403)" ;;
  esac
  record "$id" "$label" "$code"
  echo "$id $label -> HTTP $code"
}

expect_rejected I1 "không có token" "$DRIVER_URL$CANDIDATES"
expect_rejected I2 "sai token" "$DRIVER_URL$CANDIDATES" -H 'X-Internal-Token: wrong-token'
expect_rejected I3 "token rỗng" "$DRIVER_URL$CANDIDATES" -H 'X-Internal-Token;'
expect_rejected I4 "JWT người dùng thay cho internal token" "$DRIVER_URL$CANDIDATES" -H "Authorization: Bearer $P1_TOK"

# Token đúng phải được phục vụ — nếu không thì 401 ở trên có thể chỉ vì endpoint hỏng.
ok_code="$(status_of "$DRIVER_URL$CANDIDATES" -H "X-Internal-Token: $INTERNAL_API_TOKEN")"
[ "$ok_code" = "200" ] || fail "I5 token đúng -> HTTP $ok_code (kỳ vọng 200)"
record I5 "token đúng" "$ok_code"
echo "I5 token đúng -> HTTP $ok_code"

# Lệnh ghi không có credential: bị từ chối và không tạo tombstone (release reservation lạ vốn tạo tombstone nếu hợp lệ).
probe_rid="$(openssl rand -hex 16 | sed 's/\(.\{8\}\)\(.\{4\}\)\(.\{4\}\)\(.\{4\}\)\(.\{12\}\)/\1-\2-\3-\4-\5/')"
expect_rejected I6 "POST release không có token" -X POST "$DRIVER_URL/internal/reservations/$probe_rid/release" -H 'Content-Type: application/json' \
  -d "{\"commandId\":\"$probe_rid\",\"commandVersion\":3,\"reason\":\"CANCELLED\"}"
rows="$(docker exec -e PGPASSWORD="$DRIVER_DB_PASSWORD" ridehailing-postgres psql -U driver_svc -d driver_db -Atc "SELECT count(*) FROM reservations WHERE id = '$probe_rid'")" || fail "psql driver_db lỗi"
[ "$rows" = "0" ] || fail "I6: lệnh bị từ chối vẫn tạo $rows reservation"

# Có token đúng cũng không đi được qua gateway.
via_nginx="$(status_of "$BASE_URL$CANDIDATES" -H "X-Internal-Token: $INTERNAL_API_TOKEN")"
[ "$via_nginx" = "404" ] || fail "I7 /internal qua gateway với token đúng -> HTTP $via_nginx (kỳ vọng 404)"
record I7 "qua gateway với token đúng" "$via_nginx"
echo "I7 qua gateway với token đúng -> HTTP $via_nginx"

# ---------- login rate limit ----------
sleep 2 # xả cửa sổ rate limit từ các login ở trên
BURST=30
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
for i in $(seq 1 "$BURST"); do
  curl -s -m 15 -o "$TMP_DIR/body$i" -w '%{http_code}' -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' -d '{"email":"nobody@demo.local","password":"WrongPass999!"}' > "$TMP_DIR/code$i" &
done
wait
limited=0; rejected=0; other=""
for i in $(seq 1 "$BURST"); do
  code="$(cat "$TMP_DIR/code$i")"
  case "$code" in
    429) limited=$((limited + 1)); limited_body="$TMP_DIR/body$i" ;;
    401) rejected=$((rejected + 1)) ;;
    *) other="$other $code" ;;
  esac
done
[ -z "$other" ] || fail "R1 login dồn dập: status ngoài 401/429:$other"
[ "$limited" -ge 1 ] || fail "R1 login dồn dập: $BURST request không có 429 (rate limit không hoạt động?)"
[ "$rejected" -ge 1 ] || fail "R1 login dồn dập: không request nào tới được service ($limited/$BURST là 429)"
grep -q '"code":"RATE_LIMITED"' "$limited_body" || fail "R1: body 429 không phải RATE_LIMITED: $(cat "$limited_body")"
grep -q '"correlationId"' "$limited_body" || fail "R1: body 429 thiếu correlationId"
record R1 "login dồn dập $BURST request" "429x$limited,401x$rejected" "\"limited\":$limited,\"reached_service\":$rejected"
echo "R1 login dồn dập $BURST request -> 429 x$limited (RATE_LIMITED), 401 x$rejected"

sleep 2
after="$(status_of -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' -d "$LOGIN_BODY")"
[ "$after" = "200" ] || fail "R2 đăng nhập sau khi hết cửa sổ -> HTTP $after (kỳ vọng 200)"
record R2 "đăng nhập sau khi hết cửa sổ" "$after"
echo "R2 đăng nhập sau khi hết cửa sổ -> HTTP $after"

{
  echo "S03 — CORS, internal credential, login rate limit (spec §20.6 dòng T32)"
  echo "BASE_URL=$BASE_URL DRIVER_URL=$DRIVER_URL"
  echo "CORS: C1-C4 origin lạ không nhận Access-Control-Allow-*; C5 same-origin POST login=$same_origin."
  echo "Internal: I1-I4 không có/sai/rỗng token, JWT người dùng bị từ chối; I5 token đúng=$ok_code; I6 lệnh ghi bị từ chối, 0 reservation; I7 qua gateway=$via_nginx."
  echo "Rate limit: R1 $BURST login dồn dập -> 429 x$limited, 401 x$rejected, không status khác; R2 sau khi hết cửa sổ=$after."
} > "$SUMMARY_FILE"

echo "== S03 PASS — results: $RESULTS_FILE, summary: $SUMMARY_FILE"
