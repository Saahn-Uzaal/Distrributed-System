#!/usr/bin/env bash
# Demo spec §14 bước 5–6; kết quả ghi vào thư mục tạm, không đè bằng chứng trong tests/recovery/.
#   matching: dừng Matching, tạo trip, thấy queue giữ message, bật lại Matching, trip tiếp tục (~20 s).
#   payment:  giết Payment sau khi commit nhưng trước ACK (failpoint test), redelivery không tạo payment trùng (~40 s).
# Usage: bash scripts/demo-recovery.sh matching|payment [BASE_URL]
set -eu
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="$(mktemp -d)"
trap 'rm -rf "$RESULTS_DIR"' EXIT
export RESULTS_DIR
case "${1:-}" in
  matching) R01_PHASES=A bash "$SCRIPT_DIR/recovery-r01.sh" "${2:-http://localhost:8080}" ;;
  payment) bash "$SCRIPT_DIR/recovery-r02.sh" "${2:-http://localhost:8080}" ;;
  *) echo "Usage: bash scripts/demo-recovery.sh matching|payment [BASE_URL]" >&2; exit 2 ;;
esac
