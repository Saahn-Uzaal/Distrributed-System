#!/usr/bin/env bash
# T29 — Correlation filter: grep một correlationId trong logs của 5 service containers + access log Nginx.
# Dùng sau khi xem demo (spec §14 bước 8): minh họa đường đi sự kiện end-to-end qua các services.
# Yêu cầu: hệ thống đang chạy bằng docker compose (container names ridehailing-<service>).
#
# Usage: bash scripts/correlation-filter.sh <correlation-id>
# Exit codes: 0 = tìm thấy ít nhất một dòng; 3 = không tìm thấy trong logs nào.
set -eu

CID="${1:?Usage: bash scripts/correlation-filter.sh <correlation-id>}"

SERVICES=(identity-service driver-service trip-service matching-service payment-service)
found=0

for svc in "${SERVICES[@]}"; do
  container="ridehailing-${svc}"
  lines="$(docker logs "$container" 2>/dev/null | grep -F -- "$CID" || true)"
  if [ -n "$lines" ]; then
    echo "=== ${svc} ==="
    printf '%s\n' "$lines"
    found=1
  fi
done

# Nginx access log ghi cid=<correlation-id> (log_format main, T08-8).
nginx_lines="$(docker logs ridehailing-nginx 2>/dev/null | grep -F -- "cid=${CID}" || true)"
if [ -n "$nginx_lines" ]; then
  echo "=== nginx ==="
  printf '%s\n' "$nginx_lines"
  found=1
fi

if [ "$found" = "0" ]; then
  echo "(không tìm thấy ${CID} trong logs của 5 services + nginx)" >&2
  exit 3
fi
