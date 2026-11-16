#!/usr/bin/env bash
# T30 — C01 (spec §13): 2 passengers, 1 driver, request ĐỒNG THỜI qua HTTP thật.
# Invariants kiểm tra mỗi lượt:
#   - Không lúc nào có >1 trip trong DRIVER_OFFERED cùng thời điểm (mẫu ~2 s, 2 GET liên tiếp).
#   - Reservation active (RESERVED/CONFIRMED) của driver-1 <= 1 tại mọi snapshot DB (read-only psql từ host,
#     tài khoản driver_svc — DB của chính driver-service; không phải cross-service access của application).
#   - KHÔNG phụ thuộc mẫu: sau cleanup, khoảng sống [created_at, updated_at] của các reservation thuộc 2 trip
#     trên cùng driver không chồng nhau; driver-1 không còn reservation active, current_* = NULL (không orphan).
#   - Sau convergence: mỗi trip ở DRIVER_OFFERED hoặc NO_DRIVER; cleanup hủy trip offered -> driver về AVAILABLE.
# Jitter 0-49 ms MỖI phía để thứ tự 2 POST không cố định; summary ghi trip nào thắng.
# Giới hạn: matching xử lý TripRequested tuần tự nên 2 reserve không chạm nhau ở tầng này — race CAS
# reserve tại driver-service do ReserveIntegrationTest kiểm tra (concurrent, PostgreSQL thật).
# Lặp >= 20 lượt (spec §13 "Kiểm tra C01 lặp ít nhất 20 lượt"). Raw results: tests/concurrency/c01-*.jsonl/.txt.
# Yêu cầu: hệ thống chạy bằng docker compose (T29) + seed đã chạy; script tự đặt driver-2/driver-3 OFFLINE
# để chỉ còn MỘT driver khả dụng trong mỗi lượt.
set -eu

fail() { echo "C01 FAIL: $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
C01_REPEATS="${2:-${C01_REPEATS:-20}}"

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

driver_sql() { # read-only trên driver_db (tài khoản của chính service)
  docker exec -e PGPASSWORD="$DRIVER_DB_PASSWORD" ridehailing-postgres \
    psql -U driver_svc -d driver_db -Atc "$1"
}

db_active_reservations() { # driver_id -> số reservation đang giữ driver
  driver_sql "SELECT count(*) FROM reservations WHERE driver_id = '$1' AND state IN ('RESERVED','CONFIRMED')"
}

db_overlapping_reservations() { # driver_id tripA tripB -> số cặp reservation có khoảng sống chồng nhau
  driver_sql "SELECT count(*) FROM reservations a JOIN reservations b ON a.driver_id = b.driver_id AND a.id < b.id
    WHERE a.driver_id = '$1' AND a.trip_id IN ('$2','$3') AND b.trip_id IN ('$2','$3')
      AND a.created_at < b.updated_at AND b.created_at < a.updated_at"
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
        # contract §3.3: 200 = CANCELLED ngay (SEARCHING), 202 = từ DRIVER_OFFERED — cả hai hợp lệ.
        case "$status" in 200|202) ;; *) fail "cancel $tid ($2) -> HTTP $status: $body" ;; esac
        ;;
      CANCELLING) : ;; # transient — chỉ chờ terminal
    esac
    if ! final="$(wait_terminal "$1" "$tid" 30)"; then
      fail "trip $tid ($2) không về terminal sau cancel (state cuối: $final)"
    fi
  done <<<"$pairs"
}

echo "== concurrency-c01: BASE_URL=$BASE_URL repeats=$C01_REPEATS"

# ---------- logins MỘT lần, reuse token qua các lượt (tránh rate limit /api/auth) ----------
P1_TOK="$(login "$PASS1")"; sleep 0.2
P2_TOK="$(login "$PASS2")"; sleep 0.2
D1_TOK="$(login "$D1")";    sleep 0.2
D2_TOK="$(login "$D2")";    sleep 0.2
D3_TOK="$(login "$D3")"

RESULTS_DIR="${RESULTS_DIR:-$REPO_ROOT/tests/concurrency}"
mkdir -p "$RESULTS_DIR"
RESULTS_FILE="$RESULTS_DIR/c01-results.jsonl"
SNAPSHOTS_FILE="$RESULTS_DIR/c01-snapshots.jsonl"
: > "$RESULTS_FILE"
: > "$SNAPSHOTS_FILE"

TRIP_BODY="{\"pickup\":{\"latitude\":$PICKUP_LAT,\"longitude\":$PICKUP_LNG},\"destination\":{\"latitude\":$DEST_LAT,\"longitude\":$DEST_LNG}}"

total_ok=0; total_fail=0; offers_seen_iterations=0; a_wins=0; b_wins=0
T_START=$SECONDS

for i in $(seq 1 "$C01_REPEATS"); do
  echo "== C01 iteration $i/$C01_REPEATS"
  IT_T0=$SECONDS

  # ---------- reset state: chỉ driver-1 khả dụng; passengers sạch trip ----------
  D2_ID="$(set_driver_status "$D2_TOK" OFFLINE "driver-2")"
  D3_ID="$(set_driver_status "$D3_TOK" OFFLINE "driver-3")"
  cancel_nonterminal "$P1_TOK" "passenger-1"
  cancel_nonterminal "$P2_TOK" "passenger-2"
  D1_ID="$(set_driver_status "$D1_TOK" AVAILABLE "driver-1")"
  heartbeat_d1

  # ---------- barrier đồng thời: 2 POST /api/trips song song ----------
  idem_a="$(gen_uuid)"; idem_b="$(gen_uuid)"
  tmpA="$(mktemp)"; tmpB="$(mktemp)"
  jitter_a=$((RANDOM % 50)); jitter_b=$((RANDOM % 50))
  ( sleep "$(printf '0.%03d' "$jitter_a")"; curl -s --max-time 10 -X POST "$BASE_URL/api/trips" \
    -H "Authorization: Bearer $P1_TOK" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $idem_a" -d "$TRIP_BODY" -o "$tmpA" -w '%{http_code}' > "${tmpA}.code" ) &
  ( sleep "$(printf '0.%03d' "$jitter_b")"; curl -s --max-time 10 -X POST "$BASE_URL/api/trips" \
    -H "Authorization: Bearer $P2_TOK" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $idem_b" -d "$TRIP_BODY" -o "$tmpB" -w '%{http_code}' > "${tmpB}.code" ) &
  wait
  codeA="$(cat "${tmpA}.code")"; bodyA="$(cat "$tmpA")"
  codeB="$(cat "${tmpB}.code")"; bodyB="$(cat "$tmpB")"
  rm -f "$tmpA" "$tmpB" "${tmpA}.code" "${tmpB}.code"
  [ "$codeA" = "202" ] || fail "create A -> HTTP $codeA: $bodyA (409 ACTIVE_TRIP_EXISTS? reset chưa sạch)"
  [ "$codeB" = "202" ] || fail "create B -> HTTP $codeB: $bodyB (409 ACTIVE_TRIP_EXISTS? reset chưa sạch)"
  tripA="$(json_field "$bodyA" tripId)"; tripB="$(json_field "$bodyB" tripId)"

  # ---------- poll cả 2 trips + invariant DB mỗi ~2 s ----------
  max_offered=0; max_reserved=0; stateA=""; stateB=""
  offered_seen=0
  deadline=$((SECONDS + 90))
  converged=0
  while [ "$SECONDS" -lt "$deadline" ]; do
    line="$(http GET "/api/trips/$tripA" "$P1_TOK")"
    status="${line%%$'\t'*}"; body="${line#*$'\t'}"
    [ "$status" = "200" ] || fail "GET trip A -> HTTP $status: $body"
    stateA="$(json_field "$body" state)"
    line="$(http GET "/api/trips/$tripB" "$P2_TOK")"
    status="${line%%$'\t'*}"; body="${line#*$'\t'}"
    [ "$status" = "200" ] || fail "GET trip B -> HTTP $status: $body"
    stateB="$(json_field "$body" state)"

    offered=0
    [ "$stateA" = "DRIVER_OFFERED" ] && offered=1
    [ "$stateB" = "DRIVER_OFFERED" ] && offered=$((offered + 1))
    [ $offered -gt "$max_offered" ] && max_offered=$offered
    [ $offered -ge 1 ] && offered_seen=1

    reserved="$(db_active_reservations "$D1_ID")" || fail "psql driver_db lỗi (docker exec)"
    [ "$reserved" -gt "$max_reserved" ] && max_reserved=$reserved

    echo "{\"iteration\":$i,\"t_offset_s\":$((SECONDS - IT_T0)),\"stateA\":\"$stateA\",\"stateB\":\"$stateB\",\"offered_now\":$offered,\"db_active_reservations\":$reserved}" >> "$SNAPSHOTS_FILE"

    if [[ "$stateA" =~ ^(DRIVER_OFFERED|NO_DRIVER)$ ]] && [[ "$stateB" =~ ^(DRIVER_OFFERED|NO_DRIVER)$ ]]; then
      converged=1; break
    fi
    sleep 2
  done
  [ "$converged" = "1" ] || { stateA="${stateA:-?}"; stateB="${stateB:-?}"; }
  [ "$stateA" = "DRIVER_OFFERED" ] && a_wins=$((a_wins + 1))
  [ "$stateB" = "DRIVER_OFFERED" ] && b_wins=$((b_wins + 1))

  # ---------- cleanup: hủy trip offered (nếu còn) -> driver-1 về AVAILABLE ----------
  for t in "$tripA" "$tripB"; do
    if [ "$t" = "$tripA" ]; then tok="$P1_TOK"; else tok="$P2_TOK"; fi
    line="$(http GET "/api/trips/$t" "$tok")"
    st="$(json_field "${line#*$'\t'}" state)"
    if [ "$st" = "DRIVER_OFFERED" ]; then
      line="$(http POST "/api/trips/$t/cancel" "$tok")"
      status="${line%%$'\t'*}"; body="${line#*$'\t'}"
      case "$status" in 200|202) ;; *) fail "cleanup cancel $t -> HTTP $status: $body" ;; esac
      if ! final="$(wait_terminal "$tok" "$t" 30)"; then
        fail "trip $t không về terminal sau cleanup (state cuối: $final)"
      fi
    fi
  done

  # driver-1 phải AVAILABLE lại (offer null + DB không RESERVED/BUSY)
  line="$(http GET /api/drivers/me/offer "$D1_TOK")"
  status="${line%%$'\t'*}"; body="${line#*$'\t'}"
  [ "$status" = "200" ] || fail "GET offer driver-1 -> HTTP $status: $body"
  [ "$body" = "null" ] || fail "driver-1 vẫn có offer sau cleanup (body: $body)"
  drv_status="$(docker exec -e PGPASSWORD="$DRIVER_DB_PASSWORD" ridehailing-postgres \
    psql -U driver_svc -d driver_db -Atc "SELECT status FROM drivers WHERE id = '$D1_ID'")" || fail "psql driver status lỗi"
  [ "$drv_status" = "AVAILABLE" ] || fail "driver-1 không về AVAILABLE sau cleanup (status: $drv_status)"

  # ---------- invariant không phụ thuộc mẫu + orphan ----------
  overlaps="$(db_overlapping_reservations "$D1_ID" "$tripA" "$tripB")" || fail "psql overlap lỗi"
  orphans="$(db_active_reservations "$D1_ID")" || fail "psql orphan lỗi"
  dangling="$(driver_sql "SELECT count(*) FROM drivers WHERE id = '$D1_ID' AND (current_reservation_id IS NOT NULL OR current_trip_id IS NOT NULL)")" || fail "psql driver current_* lỗi"

  # ---------- assertions invariant ----------
  ok=1
  [ "$max_offered" -le 1 ] || { ok=0; echo "VIOLATION iteration $i: max_concurrent_offered=$max_offered (>1)"; }
  [ "$max_reserved" -le 1 ] || { ok=0; echo "VIOLATION iteration $i: max_db_active_reservations=$max_reserved (>1)"; }
  [ "$overlaps" -eq 0 ] || { ok=0; echo "VIOLATION iteration $i: $overlaps cặp reservation chồng nhau trên driver-1"; }
  [ "$orphans" -eq 0 ] || { ok=0; echo "VIOLATION iteration $i: $orphans reservation active còn lại sau cleanup"; }
  [ "$dangling" -eq 0 ] || { ok=0; echo "VIOLATION iteration $i: driver-1 còn current_reservation_id/current_trip_id"; }
  [ "$converged" = "1" ] || { ok=0; echo "TIMEOUT iteration $i: không convergence (A=$stateA B=$stateB)"; }

  if [ "$ok" = "1" ]; then total_ok=$((total_ok + 1)); else total_fail=$((total_fail + 1)); fi
  [ "$offered_seen" = "1" ] && offers_seen_iterations=$((offers_seen_iterations + 1))

  echo "{\"iteration\":$i,\"tripA\":\"$tripA\",\"tripB\":\"$tripB\",\"max_concurrent_offered\":$max_offered,\"max_db_active_reservations\":$max_reserved,\"overlapping_reservations\":$overlaps,\"orphan_reservations\":$orphans,\"jitter_ms\":[$jitter_a,$jitter_b],\"final_stateA\":\"$stateA\",\"final_stateB\":\"$stateB\",\"converged\":$([ "$converged" = 1 ] && echo true || echo false),\"duration_s\":$((SECONDS - IT_T0)),\"ok\":$([ "$ok" = 1 ] && echo true || echo false)}" >> "$RESULTS_FILE"
done

DURATION=$((SECONDS - T_START))
SUMMARY="$RESULTS_DIR/c01-summary.txt"
{
  echo "C01 — 2 passengers, 1 driver, request đồng thời (spec §13)"
  echo "BASE_URL=$BASE_URL"
  echo "repeats=$C01_REPEATS (yêu cầu >= 20)"
  echo "iterations_ok=$total_ok iterations_fail=$total_fail"
  echo "iterations_with_offer_seen=$offers_seen_iterations"
  echo "offer_won_by: tripA=$a_wins tripB=$b_wins"
  echo "duration_s=$DURATION"
  echo "raw: $RESULTS_FILE ; snapshots: $SNAPSHOTS_FILE"
} > "$SUMMARY"

echo "== C01 done: ok=$total_ok fail=$total_fail offers_seen=$offers_seen_iterations/$C01_REPEATS wins A/B=$a_wins/$b_wins (~${DURATION}s)"
{ [ "$C01_REPEATS" -lt 2 ] || { [ "$a_wins" -gt 0 ] && [ "$b_wins" -gt 0 ]; }; } || echo "WARNING: chỉ một trip từng thắng offer — lượt này chỉ kiểm chứng một thứ tự đến."

# Zero-offer trong TẤT CẢ lượt = test không exercise contention -> FAIL.
if [ "$offers_seen_iterations" -eq 0 ]; then
  fail "không iteration nào có offer — matching/heartbeat lỗi, kiểm tra logs services"
fi
[ "$total_fail" -eq 0 ] || { echo "C01 FAIL: $total_fail lượt vi phạm invariant (xem $RESULTS_FILE)" >&2; exit 1; }
echo "== C01 PASS: zero double assignment, reservation không chồng nhau, không orphan sau cleanup"
