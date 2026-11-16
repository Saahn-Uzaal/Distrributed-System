#!/usr/bin/env bash
# Demo spec §14 bước 3: 2 hành khách đặt xe gần như đồng thời khi chỉ có 1 tài xế.
# Một lượt C01; kết quả ghi vào thư mục tạm, không đè bằng chứng trong tests/concurrency/.
# Usage: bash scripts/demo-concurrency.sh [BASE_URL]
set -eu
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="$(mktemp -d)"
trap 'rm -rf "$RESULTS_DIR"' EXIT
export RESULTS_DIR
bash "$SCRIPT_DIR/concurrency-c01.sh" "${1:-http://localhost:8080}" 1
echo
echo "Kết quả lượt này (trip nào giữ được tài xế, trip kia NO_DRIVER; không có reservation chồng nhau):"
tr ',' '\n' < "$RESULTS_DIR/c01-results.jsonl" | grep -E 'final_state|overlapping|orphan|max_db' | tr -d '"{}'
