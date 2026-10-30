#!/usr/bin/env bash
# T34 — chạy scripts/ci-system-check.sh từ một bản sao sạch: chỉ gồm file không bị gitignore, .env và JWT key
# tạo mới, build không dùng cache, volume riêng. Lộ các lỗi "chỉ chạy được trên máy này".
# Tên container và port cố định nên phải tạm dừng stack đang chạy (KHÔNG xóa volume của nó) rồi khởi động lại đúng
# project cũ khi xong. Stack cũ dùng lại image vừa build từ bản sao này, tức cùng mã nguồn.
#
# Usage: bash scripts/clean-deploy-check.sh --confirm [--head]
#   --head: chỉ lấy file đã commit (mặc định: gồm cả file chưa commit không bị gitignore)
set -u

fail() { echo "CLEAN DEPLOY FAIL: $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
CONFIRM=0; HEAD_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --confirm) CONFIRM=1 ;;
    --head) HEAD_ONLY=1 ;;
    *) fail "tham số lạ: $arg" ;;
  esac
done
if [ "$CONFIRM" != "1" ]; then
  echo "clean-deploy-check: sẽ tạm dừng stack đang chạy (không xóa volume) rồi khởi động lại. Chạy lại với --confirm."
  exit 2
fi

WORK="$(mktemp -d)" || fail "mktemp lỗi"
PROJECT="rhclean$$"
# Project thật của stack đang chạy (nếu có), để dừng và khởi động lại đúng nó cùng volume của nó.
ORIGINAL_PROJECT="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' ridehailing-nginx 2>/dev/null || true)"

repo_compose() { ( cd "$REPO_ROOT" && docker compose -p "$ORIGINAL_PROJECT" --env-file .env -f deploy/compose.yaml "$@" ); }
clean_compose() { ( cd "$WORK" && docker compose -p "$PROJECT" --env-file .env -f deploy/compose.yaml "$@" ); }

restore() {
  rc=$?
  clean_compose down -v --remove-orphans >/dev/null 2>&1
  rm -rf "$WORK"
  if [ -n "$ORIGINAL_PROJECT" ]; then
    if repo_compose up -d --no-build >/dev/null 2>&1; then
      echo "== đã khởi động lại stack ban đầu (project $ORIGINAL_PROJECT)"
    else
      echo "WARNING: không khởi động lại được stack ban đầu (project $ORIGINAL_PROJECT)"
    fi
  fi
  exit "$rc"
}
trap restore EXIT

cd "$REPO_ROOT" || fail "không vào được repo"
if [ "$HEAD_ONLY" = "1" ]; then
  git archive HEAD | tar -xf - -C "$WORK" || fail "git archive lỗi"
  echo "== bản sao từ commit $(git rev-parse --short HEAD)"
else
  git ls-files -co --exclude-standard -z | tar --null -T - -cf - | tar -xf - -C "$WORK" || fail "copy lỗi"
  echo "== bản sao gồm $(git ls-files -co --exclude-standard | wc -l | tr -d ' ') file không bị gitignore"
fi
[ ! -e "$WORK/.env" ] && [ ! -e "$WORK/keys" ] || fail "bản sao không sạch: có .env hoặc keys/"

if [ -n "$ORIGINAL_PROJECT" ]; then
  repo_compose down >/dev/null 2>&1 || fail "không dừng được stack đang chạy"
fi

cd "$WORK" || fail "không vào được bản sao"
cp .env.example .env
if [ "$(uname -s)" = "Linux" ]; then
  { echo "HOST_UID=$(id -u)"; echo "HOST_GID=$(id -g)"; } >> .env
fi
bash scripts/generate-dev-keys.sh >/dev/null 2>&1 || fail "generate-dev-keys lỗi"

echo "== build không dùng cache"
clean_compose build --no-cache || fail "build từ bản sao sạch thất bại"

# COMPOSE_PROJECT_NAME chỉ áp dụng cho lệnh này: bước khôi phục phải chạy dưới project gốc.
COMPOSE_PROJECT_NAME="$PROJECT" bash scripts/ci-system-check.sh --no-build --keep || fail "kiểm tra hệ thống từ bản sao sạch thất bại"
echo "== CLEAN DEPLOY PASS"
