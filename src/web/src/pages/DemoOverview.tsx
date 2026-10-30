/**
 * T29 — màn hình demo overview (spec §14):
 * - "Chuyến theo quyền đã cho phép" qua API hiện có GET /api/trips
 *   (passenger thấy chuyến của mình; driver thấy chuyến được offer/assign) — không endpoint mới.
 * - Links health nội bộ DEV-ONLY: publish CHỈ 127.0.0.1:808x trong compose, KHÔNG expose qua :8080.
 * - Correlation filter: lệnh scripts/correlation-filter.sh <cid> để grep logs 5 services + nginx.
 */
import { useState } from 'react'
import type { ReactElement } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Input, Space, Table, Tag, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { loadSession } from '../auth/session'
import { tripsApi } from '../api/trips'
import type { Trip } from '../trips/types'
import { formatDateTime, formatVnd, tripStateColor, tripStateLabel } from '../trips/types'
import { usePolling } from '../hooks/usePolling'
import { useUnauthorizedRedirect } from '../hooks/useUnauthorizedRedirect'

const LIST_POLL_MS = 5000

/** Health endpoints nội bộ — dev-only (publish CHỈ 127.0.0.1 trong compose). */
export const HEALTH_LINKS: { name: string; url: string }[] = [
  { name: 'identity-service', url: 'http://localhost:8081/actuator/health/readiness' },
  { name: 'driver-service', url: 'http://localhost:8082/actuator/health/readiness' },
  { name: 'trip-service', url: 'http://localhost:8083/actuator/health/readiness' },
  { name: 'matching-service', url: 'http://localhost:8084/actuator/health/readiness' },
  { name: 'payment-service', url: 'http://localhost:8085/actuator/health/readiness' }
]

/** T29 — lệnh correlation filter (pure, unit-testable). */
export function correlationFilterCommand(cid: string): string {
  return `bash scripts/correlation-filter.sh ${cid.trim()}`
}

export default function DemoOverview(): ReactElement {
  const navigate = useNavigate()
  const session = loadSession()
  const role = session?.user.role ?? 'PASSENGER'

  // "Theo quyền đã cho phép": GET /api/trips trả đúng tập trip caller được phép thấy.
  const { data, error } = usePolling((signal) => tripsApi.listTrips(0, 20, signal), { intervalMs: LIST_POLL_MS })
  useUnauthorizedRedirect(error)
  const [cidInput, setCidInput] = useState('')

  const trips = data?.content ?? []

  const columns: ColumnsType<Trip> = [
    {
      title: 'Trip',
      key: 'tripId',
      render: (_v, t) =>
        role === 'PASSENGER' ? (
          <Button type="link" size="small" onClick={() => navigate(`/passenger/trips/${t.tripId}`)}>
            {t.tripId}
          </Button>
        ) : (
          <Typography.Text copyable={{ text: t.tripId }}>{t.tripId}</Typography.Text>
        )
    },
    { title: 'Trạng thái', key: 'state', render: (_v, t) => <Tag color={tripStateColor(t.state)}>{tripStateLabel(t.state)}</Tag> },
    { title: 'Khoảng cách', key: 'distanceM', render: (_v, t) => `${t.distanceM} m` },
    { title: 'Giá', key: 'fareVnd', render: (_v, t) => formatVnd(t.fareVnd) },
    {
      title: 'Thanh toán',
      key: 'paymentStatus',
      render: (_v, t) => (t.paymentStatus === 'NOT_REQUESTED' ? <Typography.Text type="secondary">—</Typography.Text> : t.paymentStatus)
    },
    { title: 'Tạo lúc', key: 'createdAt', render: (_v, t) => formatDateTime(t.createdAt) }
  ]

  return (
    <Card style={{ maxWidth: 1080, margin: '24px auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <Space>
          <Typography.Title level={3} style={{ margin: 0 }}>
            Demo overview
          </Typography.Title>
          <Tag color="purple">DEMO</Tag>
          {session && <Tag color={role === 'PASSENGER' ? 'blue' : 'green'}>{role}</Tag>}
        </Space>
        {session && (
          <Typography.Text type="secondary">
            {session.user.fullName} ({session.user.email}) —{' '}
            <Button type="link" onClick={() => navigate(role === 'PASSENGER' ? '/passenger' : '/driver')}>
              về trang chính
            </Button>
          </Typography.Text>
        )}
      </div>

      <Space direction="vertical" size={16} style={{ display: 'flex', width: '100%' }}>
        <Card title="Chuyến theo quyền đã cho phép (GET /api/trips)" size="small">
          {error ? (
            <Alert type="warning" message={`Không tải được danh sách chuyến: ${error.code}`} />
          ) : trips.length === 0 ? (
            <Typography.Text type="secondary">Chưa có chuyến nào trong phạm vi quyền của bạn.</Typography.Text>
          ) : (
            <Table rowKey="tripId" columns={columns} dataSource={trips} pagination={false} size="small" />
          )}
        </Card>

        <Card title="Health nội bộ — dev-only" size="small">
          <Alert
            type="info"
            showIcon
            message="Các endpoint health dưới đây publish CHỈ trên 127.0.0.1 (dev/debug trong compose) và KHÔNG được expose qua gateway :8080."
            description="Mở từng link từ trình duyệt trên host đang chạy hệ thống; /actuator/health/readiness phản ánh DB thật (N17/C06)."
          />
          <Space direction="vertical" size={4} style={{ display: 'flex', marginTop: 8 }}>
            {HEALTH_LINKS.map((h) => (
              <Typography.Link key={h.name} href={h.url} target="_blank">
                {h.name} — {h.url}
              </Typography.Link>
            ))}
          </Space>
        </Card>

        <Card title="Correlation filter" size="small">
          <Typography.Paragraph type="secondary">
            Dán correlationId (header X-Correlation-ID trong response, hoặc access log Nginx) để sinh lệnh grep logs của 5 services + nginx:
          </Typography.Paragraph>
          <Input
            placeholder="vd 3f2c…-a1b2 (UUID)"
            value={cidInput}
            onChange={(e) => setCidInput(e.target.value)}
          />
          {cidInput.trim() && (
            <Typography.Paragraph copyable style={{ marginTop: 8, fontFamily: 'monospace' }}>
              {correlationFilterCommand(cidInput)}
            </Typography.Paragraph>
          )}
        </Card>
      </Space>
    </Card>
  )
}
