#!/usr/bin/env bash
# T33 — P01 (spec §13): đo tải 10/30/50 hành khách đồng thời trên 2 bộ dữ liệu tài xế, qua Nginx bằng k6 (container).
#   enough = mỗi hành khách một tài xế, tài xế xong ngay (đo năng lực hệ thống);
#   scarce = số tài xế bằng 1/5 số hành khách, mỗi chuyến giữ tài xế 10 s (quan sát contention).
# Sau mỗi lượt kiểm tra read-only trên DB của đúng service: gán trùng tài xế, thanh toán trùng, reservation còn sót.
# Yêu cầu: stack Docker Compose đang chạy. Tài khoản load-*@demo.local tự tạo qua public API, không đụng dữ liệu demo.
# Raw results: tests/performance/results/ (mỗi lượt: .trips.jsonl theo chuyến, .summary.json của k6, .db-check.txt,
# .docker-stats.csv, .queues.csv) + results/summary.md + results/machine.txt.
#
# Usage: bash scripts/load-p01.sh [--rounds N] [--only "enough:10 scarce:50"]    # mặc định 5 lượt/hành khách, đủ 6 cấu hình
set -eu

fail() { echo "P01 FAIL: $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
PERF_DIR="$REPO_ROOT/tests/performance"
RESULTS_DIR="$PERF_DIR/results"
K6_IMAGE="grafana/k6:2.3.0"
SETTLE_S=20 # > 15 s: tài xế lượt trước hết hạn heartbeat, finish/payment của lượt trước kịp xong

ROUNDS=5
MATRIX="enough:10 enough:30 enough:50 scarce:10 scarce:30 scarce:50"
while [ $# -gt 0 ]; do
  case "$1" in
    --rounds) ROUNDS="$2"; shift 2 ;;
    --only) MATRIX="$2"; shift 2 ;;
    *) fail "tham số lạ: $1" ;;
  esac
done

env_value() { sed -n "s/^$1=//p" "$REPO_ROOT/.env" | tr -d '\r' | tail -n 1; }
[ -f "$REPO_ROOT/.env" ] || fail "thiếu $REPO_ROOT/.env (cp .env.example .env)"
SEED_PASSWORD="$(env_value SEED_PASSWORD)"
DRIVER_DB_PASSWORD="$(env_value DRIVER_DB_PASSWORD)"
TRIP_DB_PASSWORD="$(env_value TRIP_DB_PASSWORD)"
PAYMENT_DB_PASSWORD="$(env_value PAYMENT_DB_PASSWORD)"
[ -n "$SEED_PASSWORD" ] || fail "thiếu SEED_PASSWORD trong .env"
docker inspect ridehailing-nginx >/dev/null 2>&1 || fail "stack chưa chạy (docker compose --env-file .env -f deploy/compose.yaml up --build -d)"

win_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }

sql() { # user db password query -> kết quả -At (read-only, tài khoản của chính service)
  docker exec -e PGPASSWORD="$3" ridehailing-postgres psql -U "$1" -d "$2" -Atc "$4"
}

mkdir -p "$RESULTS_DIR"
{
  echo "Máy đo (load generator k6 và toàn bộ stack chạy CHUNG một máy — số đo không tách được tải của k6):"
  if command -v powershell >/dev/null 2>&1; then
    powershell -NoProfile -c '$c=Get-CimInstance Win32_Processor; $s=Get-CimInstance Win32_ComputerSystem; $o=Get-CimInstance Win32_OperatingSystem; "CPU: $($c.Name.Trim()) ($($s.NumberOfLogicalProcessors) logical)"; "RAM: $([math]::Round($s.TotalPhysicalMemory/1GB,1)) GiB"; "OS: $($o.Caption) $($o.Version)"' | tr -d '\r'
  else
    echo "CPU: $(sed -n 's/^model name[^:]*: //p' /proc/cpuinfo | head -1) ($(nproc) logical)"
    echo "RAM: $(awk '/MemTotal/ {printf "%.1f GiB", $2/1048576}' /proc/meminfo)"
    echo "OS: $(uname -sr)"
  fi
  echo "Docker: $(docker info --format 'server {{.ServerVersion}}, {{.OperatingSystem}}, VM {{.NCPU}} CPU, {{.MemTotal}} bytes RAM')"
  echo "k6: $K6_IMAGE; Nginx: 1 worker, 512 connections; PostgreSQL và RabbitMQ: một instance dùng chung"
  echo "Commit: $(git -C "$REPO_ROOT" rev-parse --short HEAD)$(git -C "$REPO_ROOT" diff --quiet HEAD -- src deploy || echo ' + thay đổi chưa commit trong src/deploy')"
  echo "Thời điểm: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$RESULTS_DIR/machine.txt"

run_one() { # dataset vus
  local dataset="$1" vus="$2" drivers hold name t0 stats_pid
  case "$dataset" in
    enough) drivers=$vus; hold=0 ;;
    scarce) drivers=$((vus / 5)); hold=10 ;;
    *) fail "dataset lạ: $dataset" ;;
  esac
  name="p01-$dataset-$vus"
  echo "== $name: $vus hành khách, $drivers tài xế, giữ tài xế $hold s, $ROUNDS lượt/hành khách"
  t0="$(sql trip_svc trip_db "$TRIP_DB_PASSWORD" "SELECT clock_timestamp()")"

  : > "$RESULTS_DIR/$name.docker-stats.csv"; : > "$RESULTS_DIR/$name.queues.csv"; : > "$RESULTS_DIR/$name.trips.jsonl" # k6 ghi nối, không tự truncate
  ( while :; do
      docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' | sed "s/^/$(date -u +%H:%M:%S),/" >> "$RESULTS_DIR/$name.docker-stats.csv"
      docker exec ridehailing-rabbitmq rabbitmqctl -q list_queues name messages | tr -d '\r' | awk -v t="$(date -u +%H:%M:%S)" '$1 ~ /inbox/ {printf "%s,%s,%s\n", t, $1, $2}' >> "$RESULTS_DIR/$name.queues.csv"
      sleep 3
    done ) 2>/dev/null &
  stats_pid=$!

  local rc=0
  # MSYS_NO_PATHCONV: Git Bash không đổi /results, /scripts thành đường dẫn Windows. Password đi qua env, không qua argv.
  MSYS_NO_PATHCONV=1 SEED_PASSWORD="$SEED_PASSWORD" docker run --rm --network ridehailing_net \
    -v "$(win_path "$PERF_DIR"):/scripts:ro" -v "$(win_path "$RESULTS_DIR"):/results" \
    -e SEED_PASSWORD -e VUS="$vus" -e DRIVERS="$drivers" -e ROUNDS="$ROUNDS" -e DATASET="$dataset" -e HOLD_S="$hold" -e RUN_NAME="$name" \
    "$K6_IMAGE" run --quiet --log-format=raw --console-output="/results/$name.trips.jsonl" /scripts/p01.js || rc=$?
  kill "$stats_pid" 2>/dev/null || true
  wait "$stats_pid" 2>/dev/null || true
  [ "$rc" = "0" ] || fail "k6 $name thoát với mã $rc"

  sleep "$SETTLE_S"
  local overlaps residual dup_payments dup_accepts
  overlaps="$(sql driver_svc driver_db "$DRIVER_DB_PASSWORD" "SELECT count(*) FROM reservations a JOIN reservations b ON a.driver_id = b.driver_id AND a.id < b.id
    WHERE a.created_at >= '$t0' AND b.created_at >= '$t0' AND a.created_at < b.updated_at AND b.created_at < a.updated_at")"
  residual="$(sql driver_svc driver_db "$DRIVER_DB_PASSWORD" "SELECT count(*) FROM reservations WHERE created_at >= '$t0' AND state IN ('RESERVED','CONFIRMED')")"
  dup_payments="$(sql payment_svc payment_db "$PAYMENT_DB_PASSWORD" "SELECT count(*) FROM (SELECT trip_id, attempt FROM payments WHERE created_at >= '$t0' GROUP BY 1, 2 HAVING count(*) > 1) x")"
  dup_accepts="$(sql trip_svc trip_db "$TRIP_DB_PASSWORD" "SELECT count(*) FROM (SELECT trip_id FROM trip_events WHERE type = 'TRIP_ACCEPTED' AND created_at >= '$t0' GROUP BY 1 HAVING count(*) > 1) x")"
  {
    echo "từ thời điểm: $t0"
    echo "duplicate_assignments (cặp reservation chồng nhau trên cùng tài xế): $overlaps"
    echo "reservation_active_sau_${SETTLE_S}s: $residual"
    echo "duplicate_payments (trip+attempt > 1 payment): $dup_payments"
    echo "trip_nhận_TRIP_ACCEPTED_nhiều_lần: $dup_accepts"
  } > "$RESULTS_DIR/$name.db-check.txt"
  echo "   DB: duplicate_assignments=$overlaps residual=$residual duplicate_payments=$dup_payments duplicate_accepts=$dup_accepts"
}

for item in $MATRIX; do
  run_one "${item%%:*}" "${item##*:}"
  sleep 1
done

{
  echo "# P01 — kết quả đo ($ROUNDS lượt/hành khách)"
  echo
  sed 's/^/    /' "$RESULTS_DIR/machine.txt"
  echo
  echo "Latency ghi p50 / p95, đơn vị ms. \`create→offered\`: từ lúc gửi POST tới DRIVER_OFFERED; \`create→accepted\`: tới ACCEPTED (tài xế giả lập nhận ngay). Sai số poll tối đa 500 ms. Throughput tính trên cửa sổ đo, không gồm setup. \`scarce\` giữ tài xế 10 s mỗi chuyến nên throughput của nó bị chặn bởi số tài xế, không so trực tiếp với \`enough\`."
  echo
  echo "| Dataset | Hành khách | Tài xế | Giữ tài xế (s) | Trip tạo | Hoàn tất | NO_DRIVER | Tỷ lệ NO_DRIVER | POST 202 | create→offered | create→accepted | create→NO_DRIVER | Trip tạo/s | Hoàn tất/s | Lỗi kỹ thuật | Tỷ lệ lỗi KT | Offer mất | 409 nghiệp vụ | Heartbeat lỗi | Gán trùng |"
  echo "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
  for f in "$RESULTS_DIR"/p01-*.row.md; do
    [ -f "$f" ] || continue
    base="${f%.row.md}"
    printf '%s %s |\n' "$(sed 's/ *$//' "$f")" "$(sed -n 's/^duplicate_assignments[^:]*: //p' "$base.db-check.txt")"
  done
} > "$RESULTS_DIR/summary.md"

echo "== P01 xong: $RESULTS_DIR/summary.md"
