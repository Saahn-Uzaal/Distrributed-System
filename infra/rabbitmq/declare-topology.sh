#!/usr/bin/env bash
# T04 — Declare RabbitMQ topology theo spec mục 9 (idempotent, chạy lại được).
# Chạy SAU khi: docker compose --env-file .env -f deploy/compose.yaml up -d
# Cách hoạt động: one-off container trên mạng ridehailing_net kết nối AMQP tới host "rabbitmq"
# qua DNS của Docker network (cũng là bằng chứng service tìm được nhau qua DNS).
# Dùng amqp_client có sẵn trong image rabbitmq — không phụ thuộc HTTP management API.
# Topology (spec mục 9 + sửa F02 đợt 19/10/2026 — contract events.md §3.5/§3.6):
#   exchange topic durable : ride.events        (event fan-out)
#   exchange topic durable : ride.events.dlx    (dead-letter)
#   inbox queues (durable, dead-lettered):
#     matching.inbox  <- trip.requested          (Trip -> Matching)
#     trip.inbox      <- matching.#              (Matching -> Trip: proposed + unavailable)
#                       <- payment.#              (Payment -> Trip: succeeded + failed) [F02]
#     payment.inbox   <- trip.completed          (Trip -> Payment)
#   DLQs (durable): matching.dlq, trip.dlq, payment.dlq
set -euo pipefail

cd "$(dirname "$0")/../.."   # về root repo (script nằm ở infra/rabbitmq/)
if [ -f .env ]; then set -a; . ./.env; set +a; fi
RABBITMQ_USER="${RABBITMQ_USER:?thiếu RABBITMQ_USER trong .env}"
RABBITMQ_PASSWORD="${RABBITMQ_PASSWORD:?thiếu RABBITMQ_PASSWORD trong .env}"

# Git Bash (MSYS2) tự động đổi argument kiểu Unix sang path Windows — tắt để docker nhận đúng /topo
export MSYS_NO_PATHCONV=1

# Docker Desktop (Windows) cần host path dạng Windows; cygpath có sẵn trong Git Bash
if command -v cygpath >/dev/null 2>&1; then
  TOPO_HOST="$(cygpath -w "$(pwd)/infra/rabbitmq")"
else
  TOPO_HOST="$(pwd)/infra/rabbitmq"
fi

docker run --rm \
  -e RABBITMQ_USER="$RABBITMQ_USER" \
  -e RABBITMQ_PASSWORD="$RABBITMQ_PASSWORD" \
  -e ERL_LIBS=/opt/rabbitmq/plugins \
  --network ridehailing_net \
  -v "${TOPO_HOST}:/topo:ro" \
  rabbitmq:3-management-alpine \
  escript /topo/declare_topology.es

echo "T04 (+F02): topology declared — ride.events + ride.events.dlx, 3 inbox queues (trip.inbox bind matching.# + payment.#), 3 DLQs."
