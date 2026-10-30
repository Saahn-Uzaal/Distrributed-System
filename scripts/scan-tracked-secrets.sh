#!/usr/bin/env bash
# T34 — quét secret trong file được git theo dõi (spec §13 "không có secret trong tracked files"):
# file .env/key thật và nội dung trông như key/token. .env.example chỉ chứa giá trị dev-only nên được phép.
# Chỉ phát hiện mẫu phổ biến, không thay thế công cụ chuyên dụng. Không in giá trị khớp ra log.
#
# Usage: bash scripts/scan-tracked-secrets.sh
set -eu

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(dirname "$SCRIPT_DIR")"

found=0

bad_files="$(git ls-files | grep -E '(^|/)\.env$|(^|/)\.env\.[^/]*$|\.pem$|\.key$|\.p12$|\.jks$|(^|/)id_(rsa|ed25519)$|(^|/)keys/' | grep -v '^\.env\.example$' || true)"
if [ -n "$bad_files" ]; then
  echo "FAIL: file secret được git theo dõi:"
  printf '%s\n' "$bad_files"
  found=1
fi

# -l: chỉ in tên file, không in giá trị. Bản thân script này chứa các mẫu nên được loại trừ.
# Header PEM phải đứng riêng một dòng như file key thật; code xử lý PEM (chuỗi trong nháy) không khớp.
patterns='^[[:space:]]*-----BEGIN [A-Z ]*PRIVATE KEY-----[[:space:]]*$|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{36}|sk-ant-[A-Za-z0-9_-]{20}|xox[baprs]-[A-Za-z0-9-]{20}'
# git grep: 0 = có khớp, 1 = không khớp, khác = lỗi. Lỗi phải làm fail, không được coi là "sạch".
rc=0
bad_content="$(git grep -lIE -e "$patterns" -- ':!scripts/scan-tracked-secrets.sh')" || rc=$?
[ "$rc" -le 1 ] || { echo "FAIL: git grep lỗi (exit $rc)"; exit 2; }
if [ -n "$bad_content" ]; then
  echo "FAIL: nội dung trông như key/token trong:"
  printf '%s\n' "$bad_content"
  found=1
fi

[ "$found" -eq 0 ] || exit 1
echo "PASS: không thấy secret trong $(git ls-files | wc -l | tr -d ' ') file được theo dõi."
