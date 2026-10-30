#!/usr/bin/env bash
# T34 — kiểm tra hệ thống từ thư mục sạch theo đúng runbook README (spec §12): tạo .env + JWT key nếu thiếu,
# docker compose up --build, chờ healthy, seed, smoke, security S01/S02/S03, route private không lộ.
# Script chạy từ bất kỳ cwd nào. Mặc định dọn stack + volume khi xong, nên CHỈ dùng trong bản checkout/copy
# dành riêng cho kiểm tra; --keep giữ stack để chạy tiếp (CI dùng cho E2E).
#
# Usage: bash scripts/ci-system-check.sh [--keep] [--no-build]
set -eu

fail() { echo "SYSTEM CHECK FAIL: $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
KEEP=0; BUILD="--build"
for arg in "$@"; do
  case "$arg" in
    --keep) KEEP=1 ;;
    --no-build) BUILD="" ;;
    *) fail "tham số lạ: $arg" ;;
  esac
done

command -v docker >/dev/null 2>&1 || fail "cần Docker"
docker info >/dev/null 2>&1 || fail "Docker daemon không chạy"
command -v curl >/dev/null 2>&1 || fail "cần curl"
command -v openssl >/dev/null 2>&1 || fail "cần openssl"

ENV_FILE="$REPO_ROOT/.env"
if [ ! -f "$ENV_FILE" ]; then
  cp "$REPO_ROOT/.env.example" "$ENV_FILE"
  echo "== tạo .env từ .env.example"
  # Linux: key private mode 600 thuộc user hiện tại; container identity phải chạy cùng uid mới đọc được.
  if [ "$(uname -s)" = "Linux" ]; then
    { echo "HOST_UID=$(id -u)"; echo "HOST_GID=$(id -g)"; } >> "$ENV_FILE"
  fi
fi
bash "$SCRIPT_DIR/generate-dev-keys.sh"

COMPOSE_FILE="$REPO_ROOT/deploy/compose.yaml"
compose() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"; }

cleanup() {
  rc=$?
  if [ "$rc" != "0" ]; then
    echo "== log container (lỗi, exit $rc)"
    compose ps -a || true
    compose logs --no-color --tail=60 || true
  fi
  if [ "$KEEP" = "0" ]; then compose down -v >/dev/null 2>&1 || true; fi
  exit "$rc"
}
trap cleanup EXIT

T0=$SECONDS
echo "== docker compose up $BUILD"
# shellcheck disable=SC2086 # BUILD rỗng hoặc đúng một cờ
compose up $BUILD -d

echo "== chờ 8 container healthy"
healthy=0
for _ in $(seq 1 80); do
  healthy="$(compose ps --format '{{.Name}} {{.Health}}' | grep -c ' healthy$' || true)"
  [ "$healthy" -ge 8 ] && break
  sleep 3
done
compose ps --format 'table {{.Name}}\t{{.Status}}'
[ "$healthy" -ge 8 ] || fail "chỉ $healthy/8 container healthy"
echo "== healthy sau $((SECONDS - T0)) s"

topo="$(compose ps -a --format '{{.Name}} {{.ExitCode}}' | grep topo-declare || true)"
case "$topo" in *" 0") ;; *) fail "topo-declare không thành công: ${topo:-không có}" ;; esac

# Chạy từ thư mục khác để chứng minh script không phụ thuộc cwd (spec §12).
cd /
bash "$SCRIPT_DIR/seed-demo.sh"
bash "$SCRIPT_DIR/seed-demo.sh" >/dev/null || fail "seed chạy lại không idempotent"
bash "$SCRIPT_DIR/smoke-trip.sh"
bash "$SCRIPT_DIR/security-s01.sh"
bash "$SCRIPT_DIR/security-s02.sh"
bash "$SCRIPT_DIR/security-s03.sh"

for path in /actuator/health /actuator/env /internal/drivers/candidates; do
  code="$(curl -s -m 5 -o /dev/null -w '%{http_code}' "http://localhost:8080$path")"
  [ "$code" = "404" ] || fail "route private $path qua :8080 trả $code"
done

set +e
bash "$SCRIPT_DIR/reset-demo.sh" >/dev/null 2>&1
reset_rc=$?
set -e
[ "$reset_rc" = "2" ] || fail "reset-demo không có --confirm phải exit 2, nhận $reset_rc"

echo "== SYSTEM CHECK PASS sau $((SECONDS - T0)) s"
