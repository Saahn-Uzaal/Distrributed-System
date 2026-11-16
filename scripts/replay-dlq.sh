#!/usr/bin/env bash
# Xem và replay DLQ (events.md §6) qua RabbitMQ management API, theo dõi trong container broker.
# Mặc định chỉ liệt kê, không đổi gì. --confirm mới replay: đẩy lại message về ride.events với routing key gốc,
# giữ nguyên body và eventId; consumer dedup theo eventId nên replay lặp là an toàn.
# Message có x-dlq-reason (malformed/unsupported) bị từ chối vì nội dung nên replay cũng vô ích: giữ nguyên trong DLQ.
# Thứ tự publish rồi mới xóa khỏi DLQ: lỗi giữa chừng có thể trùng nhưng không mất message.
#
# Usage: bash scripts/replay-dlq.sh [--queue trip.dlq|matching.dlq|payment.dlq] [--confirm]
set -eu

fail() { echo "REPLAY FAIL: $*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
QUEUES="matching.dlq trip.dlq payment.dlq"
CONFIRM=0
while [ $# -gt 0 ]; do
  case "$1" in
    --queue) QUEUES="$2"; shift 2 ;;
    --confirm) CONFIRM=1; shift ;;
    *) fail "tham số lạ: $1" ;;
  esac
done
for q in $QUEUES; do
  case "$q" in matching.dlq|trip.dlq|payment.dlq) ;; *) fail "queue không hợp lệ: $q" ;; esac
done

env_value() { sed -n "s/^$1=//p" "$REPO_ROOT/.env" 2>/dev/null | tr -d '\r' | tail -n 1; }
RABBITMQ_USER="${RABBITMQ_USER:-$(env_value RABBITMQ_USER)}"
RABBITMQ_PASSWORD="${RABBITMQ_PASSWORD:-$(env_value RABBITMQ_PASSWORD)}"
[ -n "$RABBITMQ_USER" ] && [ -n "$RABBITMQ_PASSWORD" ] || fail "thiếu RABBITMQ_USER/RABBITMQ_PASSWORD trong .env"

# python3 có sẵn trong image broker; chạy trong container để không thêm yêu cầu cho host. Password đi qua env.
docker exec -i -e MQ_USER="$RABBITMQ_USER" -e MQ_PASS="$RABBITMQ_PASSWORD" -e QUEUES="$QUEUES" -e CONFIRM="$CONFIRM" \
  ridehailing-rabbitmq python3 -c '
import base64, json, os, sys, urllib.request

ROUTING = {"TripRequested": "trip.requested", "DriverProposed": "matching.proposed", "NoDriverFound": "matching.unavailable",
           "TripCompleted": "trip.completed", "PaymentSucceeded": "payment.succeeded", "PaymentFailed": "payment.failed"}
auth = base64.b64encode((os.environ["MQ_USER"] + ":" + os.environ["MQ_PASS"]).encode()).decode()

def api(method, path, body=None):
    req = urllib.request.Request("http://localhost:15672/api" + path, method=method,
                                 data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": "Basic " + auth, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.load(r)

def get(queue, count, ackmode):
    return api("POST", f"/queues/%2F/{queue}/get", {"count": count, "ackmode": ackmode, "encoding": "auto"})

def describe(m):
    headers = m["properties"].get("headers") or {}
    try:
        event = json.loads(m["payload"]) if m["payload_encoding"] == "string" else {}
    except ValueError:
        event = {}
    deaths = headers.get("x-death") or []
    return event, headers.get("x-dlq-reason"), deaths

confirm = os.environ["CONFIRM"] == "1"
replayed = kept = 0
for queue in os.environ["QUEUES"].split():
    # Đọc thẳng queue: số messages của management API trễ vài giây so với thực tế.
    messages = get(queue, 1000, "ack_requeue_true")
    print(f"== {queue}: {len(messages)} message")
    for m in messages:
        event, reason, deaths = describe(m)
        if reason:
            cause = "reason=" + str(reason)
        elif deaths:
            cause = "x-death count=" + str(deaths[0].get("count")) + " from=" + str(deaths[0].get("queue"))
        else:
            cause = "không rõ"
        event_id = event.get("eventId", m["properties"].get("message_id", "?"))
        print("  eventId=" + str(event_id) + " eventType=" + str(event.get("eventType", "?"))
              + " tripId=" + str(event.get("aggregateId", "?")) + " " + cause)
    if not confirm:
        continue
    for _ in range(len(messages)):
        m = get(queue, 1, "ack_requeue_true")[0]
        event, reason, deaths = describe(m)
        props = {k: v for k, v in m["properties"].items() if k != "headers"}
        headers = {k: v for k, v in (m["properties"].get("headers") or {}).items() if k != "x-death"}
        if reason:
            target, routing = "ride.events.dlx", queue
            kept += 1
        else:
            routing = ROUTING.get(event.get("eventType"))
            if deaths and deaths[0].get("routing-keys"):
                routing = deaths[0]["routing-keys"][0]
            if not routing:
                sys.exit(f"không xác định được routing key gốc cho message trong {queue}")
            target = "ride.events"
            replayed += 1
            headers["x-replayed-from"] = queue
        props["headers"] = headers
        result = api("POST", f"/exchanges/%2F/{target}/publish", {"properties": props, "routing_key": routing,
                     "payload": m["payload"], "payload_encoding": m["payload_encoding"]})
        if not result.get("routed"):
            sys.exit(f"publish tới {target} rk={routing} không được route; message vẫn ở {queue}")
        get(queue, 1, "ack_requeue_false")
if confirm:
    print(f"== replay xong: đẩy lại {replayed} message, giữ {kept} message bị từ chối vì nội dung")
else:
    print("== chỉ liệt kê; thêm --confirm để replay")
'
