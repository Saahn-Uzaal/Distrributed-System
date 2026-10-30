#!/usr/bin/env bash
# T30 — C02 (spec §13): Accept và cancel ĐỒNG THỜI qua HTTP thật.
# Invariants kiểm tra mỗi lượt:
#   - Đúng MỘT intent thắng: một response 2xx, response kia 409 STATE_CONFLICT (saga row-lock, T18/T20).
#   - Final state nhất quán: accept thắng -> ACCEPTED rồi cleanup start+complete giải phóng driver;
#     cancel thắng -> CANCELLED, reservation release, driver về AVAILABLE. Không trip bị start sau cancel,
#     không driver kẹt RESERVED/BUSY (verify DB read-only + offer null).
# Mỗi lượt ghi thời điểm gửi + thời gian xử lý từng request; summary đếm số lượt 2 request THẬT SỰ chồng
# nhau (overlap_ms > 0) — không có lượt nào chồng nhau thì test không chứng minh được gì và FAIL.
# Lặp 20 lượt (C02_REPEATS). Raw results: tests/concurrency/c02-results.jsonl + c02-summary.txt.
# Yêu cầu: hệ thống chạy bằng docker compose (T29) + seed đã chạy; driver-2/driver-3 OFFLINE trong script.
set -eu

fail() { echo "C02 FAIL: $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
C02_REPEATS="${2:-${C02_REPEATS:-20}}"

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

set_driver_status() { # tok status label — echo driverId
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

wait_state() { # tok trip_id want_state max_seconds -> echo final state; return 1 nếu timeout/terminal khác
  local deadline=$((SECONDS + $4)) st="" line status body
  while [ "$SECONDS" -lt "$deadline" ]; do
    line="$(http GET "/api/trips/$2" "$1")"
    status="${line%%$'\t'*}"; body="${line#*$'\t'}"
    [ "$status" = "200" ] || fail "GET trip $2 -> HTTP $status: $body"
    st="$(json_field "$body" state)"
    [ "$st" = "$3" ] && { echo "$st"; return 0; }
    case "$st" in CANCELLED|COMPLETED|NO_DRIVER) echo "$st"; return 1 ;; esac # terminal khác mong đợi
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
        # contract §3.3: 200 = CANCELLED ngay (SEARCHING), 202 = từ DRIVER_OFFERED — cả hai hợp lệ.
        case "$status" in 200|202) ;; *) fail "cancel $tid ($2) -> HTTP $status: $body" ;; esac
        if ! final="$(wait_state "$1" "$tid" "CANCELLED" 30)"; then
          fail "trip $tid ($2) không về CANCELLED sau cancel (state cuối: $final)"
        fi
        ;;
      CANCELLING)
        # transient — chỉ chờ terminal
        if ! final="$(wait_state "$1" "$tid" "CANCELLED" 30)"; then
          fail "trip $tid ($2) không về CANCELLED (state cuối: $final)"
        fi
        ;;
      *) : ;; # đã terminal — bỏ qua, không cancel/wait
    esac
  done <<<"$pairs"
}

is_2xx() { [[ "$1" =~ ^2[0-9][0-9]$ ]]; }

wait_offer() { # max_seconds -> echo attempt của offer hiện tại; return 1 nếu không có offer
  local deadline=$((SECONDS + $1)) line status body attempt
  while [ "$SECONDS" -lt "$deadline" ]; do
    line="$(http GET /api/drivers/me/offer "$D1_TOK")"
    status="${line%%$'\t'*}"; body="${line#*$'\t'}"
    [ "$status" = "200" ] || fail "GET offer driver-1 -> HTTP $status: $body"
    if [ "$body" != "null" ]; then
      attempt="$(json_number "$body" attempt)"
      [ -n "$attempt" ] || fail "offer body thiếu attempt: $body"
      echo "$attempt"; return 0
    fi
    sleep 1
  done
  return 1
}

wait_driver_released() { # max_seconds — offer null + DB status AVAILABLE; echo DB status cuối; return 1 nếu timeout
  local deadline=$((SECONDS + $1)) line status body drv_status=""
  while [ "$SECONDS" -lt "$deadline" ]; do
    line="$(http GET /api/drivers/me/offer "$D1_TOK")"
    status="${line%%$'\t'*}"; body="${line#*$'\t'}"
    if [ "$status" = "200" ] && [ "$body" = "null" ]; then
      drv_status="$(docker exec -e PGPASSWORD="$DRIVER_DB_PASSWORD" ridehailing-postgres \
        psql -U driver_svc -d driver_db -Atc "SELECT status FROM drivers WHERE id = '$D1_ID'")" || fail "psql driver status lỗi"
      [ "$drv_status" = "AVAILABLE" ] && { echo "$drv_status"; return 0; }
    fi
    sleep 2
  done
  echo "${drv_status:-?}"
  return 1
}

echo "== concurrency-c02: BASE_URL=$BASE_URL repeats=$C02_REPEATS"

# ---------- logins MỘT lần, reuse token qua các lượt (tránh rate limit /api/auth) ----------
P1_TOK="$(login "$PASS1")"; sleep 0.2
D1_TOK="$(login "$D1")";    sleep 0.2
D2_TOK="$(login "$D2")";    sleep 0.2
D3_TOK="$(login "$D3")"

RESULTS_DIR="$REPO_ROOT/tests/concurrency"
mkdir -p "$RESULTS_DIR"
RESULTS_FILE="$RESULTS_DIR/c02-results.jsonl"
: > "$RESULTS_FILE"

TRIP_BODY="{\"pickup\":{\"latitude\":$PICKUP_LAT,\"longitude\":$PICKUP_LNG},\"destination\":{\"latitude\":$DEST_LAT,\"longitude\":$DEST_LNG}}"

total_ok=0; total_fail=0; accept_wins=0; cancel_wins=0; overlap_iterations=0
T_START=$SECONDS

for i in $(seq 1 "$C02_REPEATS"); do
  echo "== C02 iteration $i/$C02_REPEATS"
  IT_T0=$SECONDS

  # ---------- reset: chỉ driver-1 khả dụng; passenger sạch trip ----------
  D2_ID="$(set_driver_status "$D2_TOK" OFFLINE "driver-2")"
  D3_ID="$(set_driver_status "$D3_TOK" OFFLINE "driver-3")"
  cancel_nonterminal "$P1_TOK" "passenger-1"
  D1_ID="$(set_driver_status "$D1_TOK" AVAILABLE "driver-1")"
  heartbeat_d1

  # ---------- create trip (Idempotency-Key — http() không hỗ trợ extra header, dùng curl trực tiếp) ----------
  idem="$(gen_uuid)"
  tmpC="$(mktemp)"
  codeC="$(curl -s --max-time 10 -X POST "$BASE_URL/api/trips" \
    -H "Authorization: Bearer $P1_TOK" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $idem" -d "$TRIP_BODY" -o "$tmpC" -w '%{http_code}')" || fail "create trip lỗi (network)"
  bodyC="$(cat "$tmpC")"; rm -f "$tmpC"
  [ "$codeC" = "202" ] || fail "create trip -> HTTP $codeC: $bodyC (409 ACTIVE_TRIP_EXISTS? reset chưa sạch)"
  tripId="$(json_field "$bodyC" tripId)"

  # ---------- chờ DRIVER_OFFERED + đọc attempt từ offer của driver-1 ----------
  attempt=""
  if ! attempt="$(wait_offer 60)"; then
    line="$(http GET "/api/trips/$tripId" "$P1_TOK")"
    st="$(json_field "${line#*$'\t'}" state)"
    echo "{\"iteration\":$i,\"tripId\":\"$tripId\",\"attempt\":null,\"winner\":null,\"final_state\":\"$st\",\"ok\":false,\"note\":\"no offer within 60s (matching lỗi?)\"}" >> "$RESULTS_FILE"
    total_fail=$((total_fail + 1))
    continue
  fi

  # ---------- barrier đồng thời: accept (driver token) + cancel (passenger token)
  # Jitter ngẫu nhiên 0–49 ms mỗi phía (subshell sleep trước curl) để thứ tự đến server
  # không tất định — phân xử saga row-lock phải đúng với MỌI thứ tự; cả hai kết quả
  # (accept thắng / cancel thắng) đều được exercise qua các lượt.
  tmpA="$(mktemp)"; tmpB="$(mktemp)"
  jitter_a=$((RANDOM % 50)); jitter_b=$((RANDOM % 50))
  ( sleep "$(printf '0.%03d' "$jitter_a")"; date +%s%3N > "${tmpA}.start"; curl -s --max-time 10 -X POST "$BASE_URL/api/trips/$tripId/accept" \
      -H "Authorization: Bearer $D1_TOK" -H 'Content-Type: application/json' \
      -d "{\"attempt\":$attempt}" -o "$tmpA" -w '%{http_code} %{time_total}
' > "${tmpA}.code" ) &
  ( sleep "$(printf '0.%03d' "$jitter_b")"; date +%s%3N > "${tmpB}.start"; curl -s --max-time 10 -X POST "$BASE_URL/api/trips/$tripId/cancel" \
      -H "Authorization: Bearer $P1_TOK" -o "$tmpB" -w '%{http_code} %{time_total}
' > "${tmpB}.code" ) &
  wait
  read -r accept_status accept_time < "${tmpA}.code"; accept_body="$(cat "$tmpA")"
  read -r cancel_status cancel_time < "${tmpB}.code"; cancel_body="$(cat "$tmpB")"
  # Thời gian thực tế: request nào gửi trước bao lâu, và 2 khoảng xử lý có chồng nhau không.
  read -r gap_ms overlap_ms <<<"$(awk -v sa="$(cat "${tmpA}.start")" -v sc="$(cat "${tmpB}.start")" -v ta="$accept_time" -v tc="$cancel_time" 'BEGIN {
    ea = sa + ta * 1000; ec = sc + tc * 1000
    lo = (sa > sc) ? sa : sc; hi = (ea < ec) ? ea : ec
    printf "%d %d", sa - sc, (hi > lo) ? hi - lo : 0 }')"
  [ "$overlap_ms" -gt 0 ] && overlap_iterations=$((overlap_iterations + 1))
  rm -f "$tmpA" "$tmpB" "${tmpA}.code" "${tmpB}.code" "${tmpA}.start" "${tmpB}.start"

  a_ok=0; is_2xx "$accept_status" && a_ok=1
  c_ok=0; is_2xx "$cancel_status" && c_ok=1
  accept_state="$(json_field "$accept_body" state)"
  cancel_code="$(json_field "$cancel_body" code)"

  winner=""; ok=1; note=""
  if [ "$a_ok" = "1" ] && [ "$c_ok" = "1" ]; then
    ok=0; winner="BOTH"
    note="VIOLATION: cả accept và cancel đều 2xx (accept=$accept_status/$accept_state, cancel=$cancel_status) — saga row-lock không phân xử?"
  elif [ "$a_ok" = "0" ] && [ "$c_ok" = "0" ]; then
    ok=0; winner="NONE"
    note="NO WINNER: accept=$accept_status code=$(json_field "$accept_body" code), cancel=$cancel_status code=$cancel_code (offer hết hạn giữa race?)"
  elif [ "$a_ok" = "1" ]; then
    winner="ACCEPT"
    if [ "$cancel_status" != "409" ] || [ "$cancel_code" != "STATE_CONFLICT" ]; then
      ok=0; note="VIOLATION: cancel không thua bằng 409 STATE_CONFLICT (status=$cancel_status code=$cancel_code)"
    fi
  else
    winner="CANCEL"
    if [ "$accept_status" != "409" ] || [ "$(json_field "$accept_body" code)" != "STATE_CONFLICT" ]; then
      ok=0; note="VIOLATION: accept không thua bằng 409 STATE_CONFLICT (status=$accept_status code=$(json_field "$accept_body" code))"
    fi
  fi

  final_state=""
  if [ "$ok" = "1" ] && [ -n "$winner" ]; then
    if [ "$winner" = "ACCEPT" ]; then
      # accept thắng: poll tới ACCEPTED (confirm pending -> DRIVER_OFFERED transient, T18)
      if ! final_state="$(wait_state "$P1_TOK" "$tripId" "ACCEPTED" 90)"; then
        ok=0; note="VIOLATION: accept thắng nhưng trip không về ACCEPTED (state=$final_state)"
      fi
      # cleanup: start + complete -> saga finish giải phóng driver
      if [ "$ok" = "1" ]; then
        line="$(http POST "/api/trips/$tripId/start" "$D1_TOK")"
        status="${line%%$'\t'*}"; body="${line#*$'\t'}"
        [ "$status" = "200" ] || { ok=0; note="cleanup start -> HTTP $status: $body"; }
      fi
      if [ "$ok" = "1" ]; then
        line="$(http POST "/api/trips/$tripId/complete" "$D1_TOK")"
        status="${line%%$'\t'*}"; body="${line#*$'\t'}"
        [ "$status" = "200" ] || { ok=0; note="cleanup complete -> HTTP $status: $body"; }
      fi
      if [ "$ok" = "1" ]; then
        if ! final_state="$(wait_driver_released 60)"; then
          ok=0; note="VIOLATION: driver kẹt sau accept+complete (DB status=$final_state)"
        fi
      fi
    else # CANCEL thắng
      if ! final_state="$(wait_state "$P1_TOK" "$tripId" "CANCELLED" 90)"; then
        case "$final_state" in
          IN_PROGRESS|COMPLETED) ok=0; note="VIOLATION: trip bị start sau cancel (state=$final_state)" ;;
          *) ok=0; note="VIOLATION: cancel thắng nhưng trip không về CANCELLED (state=$final_state)" ;;
        esac
      fi
      if [ "$ok" = "1" ]; then
        if ! final_state="$(wait_driver_released 30)"; then
          ok=0; note="VIOLATION: driver kẹt sau cancel (DB status=$final_state)"
        fi
      fi
    fi
  fi

  [ "$winner" = "ACCEPT" ] && accept_wins=$((accept_wins + 1))
  [ "$winner" = "CANCEL" ] && cancel_wins=$((cancel_wins + 1))
  if [ "$ok" = "1" ]; then total_ok=$((total_ok + 1)); else total_fail=$((total_fail + 1)); fi

  echo "{\"iteration\":$i,\"tripId\":\"$tripId\",\"attempt\":${attempt:-null},\"jitter_ms\":[$jitter_a,$jitter_b],\"accept_minus_cancel_start_ms\":$gap_ms,\"overlap_ms\":$overlap_ms,\"accept_status\":$accept_status,\"cancel_status\":$cancel_status,\"winner\":\"$winner\",\"final_state\":\"${final_state:-?}\",\"ok\":$([ "$ok" = 1 ] && echo true || echo false),\"note\":\"$note\",\"duration_s\":$((SECONDS - IT_T0))}" >> "$RESULTS_FILE"
done

DURATION=$((SECONDS - T_START))
SUMMARY="$RESULTS_DIR/c02-summary.txt"
{
  echo "C02 — accept và cancel đồng thời (spec §13)"
  echo "BASE_URL=$BASE_URL"
  echo "repeats=$C02_REPEATS"
  echo "iterations_ok=$total_ok iterations_fail=$total_fail"
  echo "accept_wins=$accept_wins cancel_wins=$cancel_wins"
  echo "overlap_iterations=$overlap_iterations (2 request thật sự chồng nhau trên server)"
  echo "duration_s=$DURATION"
  echo "raw: $RESULTS_FILE"
} > "$SUMMARY"

echo "== C02 done: ok=$total_ok fail=$total_fail (accept_wins=$accept_wins, cancel_wins=$cancel_wins, overlap=$overlap_iterations/$C02_REPEATS, ~${DURATION}s)"
[ "$overlap_iterations" -gt 0 ] || { echo "C02 FAIL: không lượt nào có 2 request chồng nhau — test không tạo được race" >&2; exit 1; }
{ [ "$accept_wins" -gt 0 ] && [ "$cancel_wins" -gt 0 ]; } || echo "WARNING: chỉ một intent từng thắng — lượt này chỉ kiểm chứng một kết quả."
[ "$total_fail" -eq 0 ] || { echo "C02 FAIL: $total_fail lượt vi phạm invariant (xem $RESULTS_FILE)" >&2; exit 1; }
echo "== C02 PASS: mỗi race đúng một intent thắng, driver không kẹt, không trip start sau cancel"
