#!/usr/bin/env bash
# Kiểm chứng retry + DLQ + dedup + correlation ID trên hệ thống thật (spec §13 "Acceptance cuối"), dùng queue trip.inbox:
#   D1 event malformed: không retry, vào trip.dlq kèm x-dlq-reason;
#   D2 event hợp lệ nhưng xử lý lỗi (trip không tồn tại): retry 3 lần rồi vào trip.dlq, chỉ mang x-death; log có đủ retry;
#   D3 replay-dlq.sh: giữ message malformed, đẩy lại message D2 với cùng eventId -> trip-service xử lý lại, lại vào DLQ;
#   D4 tìm log theo correlationId của D2 (correlation-filter.sh).
# Yêu cầu trip.dlq rỗng khi bắt đầu; message thử do script tạo được xóa khi kết thúc.
# Raw results: tests/recovery/dlq-summary.txt.
#
# Usage: bash scripts/recovery-dlq.sh
set -eu

fail() { echo "DLQ FAIL: $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
RABBIT=ridehailing-rabbitmq
TRIP=ridehailing-trip-service
QUEUE=trip.dlq

env_value() { sed -n "s/^$1=//p" "$REPO_ROOT/.env" 2>/dev/null | tr -d '\r' | tail -n 1; }
MQ_USER="$(env_value RABBITMQ_USER)"; MQ_PASS="$(env_value RABBITMQ_PASSWORD)"
[ -n "$MQ_USER" ] && [ -n "$MQ_PASS" ] || fail "thiếu RABBITMQ_USER/RABBITMQ_PASSWORD trong .env"

uuid() { openssl rand -hex 16 | sed 's/\(.\{8\}\)\(.\{4\}\)\(.\{4\}\)\(.\{4\}\)\(.\{12\}\)/\1-\2-\3-\4-\5/'; }

depth() {
  docker exec "$RABBIT" rabbitmqctl -q list_queues name messages | tr -d '\r' | awk -v q="$QUEUE" '$1==q {print $2}'
}

publish() { # routing_key message_id correlation_id body
  docker exec -i "$RABBIT" rabbitmqadmin -u "$MQ_USER" -p "$MQ_PASS" publish exchange=ride.events routing_key="$1" \
    payload="$4" properties="{\"delivery_mode\":2,\"content_type\":\"application/json\",\"message_id\":\"$2\",\"correlation_id\":\"$3\"}" >/dev/null
}

wait_depth() { # expected max_seconds
  local deadline=$((SECONDS + $2)) d=""
  while [ "$SECONDS" -lt "$deadline" ]; do
    d="$(depth)"
    [ "$d" = "$1" ] && return 0
    sleep 1
  done
  echo "$d"
  return 1
}

[ "$(depth)" = "0" ] || fail "$QUEUE đang có message: xem bằng scripts/replay-dlq.sh rồi xử lý trước khi chạy"
trap 'docker exec "$RABBIT" rabbitmqctl -q purge_queue "$QUEUE" >/dev/null 2>&1 || true' EXIT

echo "== D1: event malformed"
BAD_ID="$(uuid)"; BAD_CID="$(uuid)"
publish payment.succeeded "$BAD_ID" "$BAD_CID" "{\"eventId\":\"$BAD_ID\",\"eventType\":\"PaymentSucceeded\"}"
wait_depth 1 15 >/dev/null || fail "D1: message malformed không vào $QUEUE"
LIST="$(bash "$SCRIPT_DIR/replay-dlq.sh" --queue "$QUEUE")"
printf '%s\n' "$LIST"
grep -F "$BAD_ID" <<<"$LIST" | grep -q 'reason=malformed_envelope' || fail "D1: thiếu x-dlq-reason=malformed_envelope"

echo "== D2: event hợp lệ, xử lý lỗi (trip không tồn tại): retry 1 s + 3 s + 10 s rồi vào DLQ"
EVENT_ID="$(uuid)"; CID="$(uuid)"; GHOST_TRIP="$(uuid)"; PAYMENT_ID="$(uuid)"
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
BODY="{\"eventId\":\"$EVENT_ID\",\"eventType\":\"PaymentSucceeded\",\"schemaVersion\":1,\"occurredAt\":\"$NOW\",\"producer\":\"recovery-dlq\",\"correlationId\":\"$CID\",\"aggregateId\":\"$GHOST_TRIP\",\"payload\":{\"tripId\":\"$GHOST_TRIP\",\"paymentId\":\"$PAYMENT_ID\",\"attempt\":1,\"amountVnd\":1000}}"
T0=$SECONDS
publish payment.succeeded "$EVENT_ID" "$CID" "$BODY"
wait_depth 2 40 >/dev/null || fail "D2: message không vào $QUEUE sau khi hết retry"
ELAPSED=$((SECONDS - T0))
LIST="$(bash "$SCRIPT_DIR/replay-dlq.sh" --queue "$QUEUE")"
printf '%s\n' "$LIST"
grep -F "$EVENT_ID" <<<"$LIST" | grep -q 'x-death count=1 from=trip.inbox' || fail "D2: thiếu x-death từ trip.inbox"
RETRIES="$(docker logs --since 2m "$TRIP" 2>&1 | grep -F "$EVENT_ID" | grep -c 'inbox_retry' || true)"
[ "$RETRIES" = "3" ] || fail "D2: log có $RETRIES lần retry (kỳ vọng 3)"
echo "   retry trong log: $RETRIES lần; vào DLQ sau ~${ELAPSED} s"

echo "== D3: replay giữ message malformed, đẩy lại message D2 với cùng eventId"
bash "$SCRIPT_DIR/replay-dlq.sh" --queue "$QUEUE" --confirm | tail -1
sleep 2
wait_depth 2 40 >/dev/null || fail "D3: sau replay $QUEUE không về 2 message"
RETRIES_AFTER="$(docker logs --since 3m "$TRIP" 2>&1 | grep -F "$EVENT_ID" | grep -c 'inbox_retry' || true)"
[ "$RETRIES_AFTER" = "6" ] || fail "D3: event không được xử lý lại với cùng eventId (log retry = $RETRIES_AFTER, kỳ vọng 6)"
LIST="$(bash "$SCRIPT_DIR/replay-dlq.sh" --queue "$QUEUE")"
grep -q "$BAD_ID" <<<"$LIST" || fail "D3: message malformed không còn trong DLQ"
grep -q "$EVENT_ID" <<<"$LIST" || fail "D3: message replay không quay lại DLQ"
echo "   cùng eventId được xử lý lại: log retry $RETRIES -> $RETRIES_AFTER; message malformed giữ nguyên"

echo "== D4: tìm log theo correlationId"
CORRELATED="$(bash "$SCRIPT_DIR/correlation-filter.sh" "$CID" | grep -c "$CID" || true)"
[ "$CORRELATED" -ge 6 ] || fail "D4: chỉ có $CORRELATED dòng log theo correlationId $CID"
echo "   $CORRELATED dòng log mang correlationId $CID"

SUMMARY="$REPO_ROOT/tests/recovery/dlq-summary.txt"
mkdir -p "$(dirname "$SUMMARY")"
{
  echo "Retry, DLQ, replay, correlation ID trên trip.inbox (scripts/recovery-dlq.sh)"
  echo "D1 malformed -> $QUEUE, x-dlq-reason=malformed_envelope, không retry."
  echo "D2 hợp lệ nhưng xử lý lỗi -> 3 lần retry trong log, vào $QUEUE sau ~${ELAPSED} s, mang x-death từ trip.inbox."
  echo "D3 replay-dlq.sh --confirm: message malformed giữ nguyên; message D2 xử lý lại với cùng eventId (log retry $RETRIES -> $RETRIES_AFTER) rồi quay lại DLQ vì trip vẫn không tồn tại."
  echo "D4 correlation-filter.sh: $CORRELATED dòng log mang cùng correlationId."
} > "$SUMMARY"
echo "== DLQ PASS — $SUMMARY"
