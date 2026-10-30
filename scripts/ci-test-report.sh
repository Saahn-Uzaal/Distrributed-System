#!/usr/bin/env bash
# T34 — tổng hợp surefire report của backend. Fail khi: thiếu report của một module, có failure/error,
# hoặc có test bị skip — test không chạy được vì thiếu môi trường không được tính là pass.
#
# Usage: bash scripts/ci-test-report.sh
set -eu

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
MODULES="identity-service driver-service trip-service matching-service payment-service"

attr() { sed -n "s/.*<testsuite [^>]*[[:space:]]$2=\"\([0-9]*\)\".*/\1/p" "$1" | head -1; }

total=0; failures=0; errors=0; skipped=0; problems=""
echo "| Module | Tests | Failures | Errors | Skipped |"
echo "|---|---|---|---|---|"
for module in $MODULES; do
  t=0; f=0; e=0; s=0; files=0
  for xml in "$REPO_ROOT/src/$module/target/surefire-reports"/TEST-*.xml; do
    [ -f "$xml" ] || continue
    files=$((files + 1))
    t=$((t + $(attr "$xml" tests))); f=$((f + $(attr "$xml" failures)))
    e=$((e + $(attr "$xml" errors))); s=$((s + $(attr "$xml" skipped)))
  done
  [ "$files" -gt 0 ] || problems="$problems\n- $module: không có surefire report (test chưa chạy)"
  echo "| $module | $t | $f | $e | $s |"
  total=$((total + t)); failures=$((failures + f)); errors=$((errors + e)); skipped=$((skipped + s))
done
echo "| **Tổng** | **$total** | **$failures** | **$errors** | **$skipped** |"

[ "$failures" -eq 0 ] && [ "$errors" -eq 0 ] || problems="$problems\n- có $failures failure, $errors error"
[ "$skipped" -eq 0 ] || problems="$problems\n- có $skipped test bị skip: không tính là pass"

if [ -n "$problems" ]; then
  printf '\nFAIL:%b\n' "$problems"
  exit 1
fi
echo
echo "PASS: $total test đã chạy, không failure/error/skip."
