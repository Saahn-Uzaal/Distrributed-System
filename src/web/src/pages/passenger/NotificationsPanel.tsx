/**
 * T27 — thông báo của hành khách (GET /api/notifications, contract §3.4).
 * Polling 2 s; dừng khi tab unmount (cleanup). MVP không có mark-as-read.
 */
import type { ReactElement } from 'react'
import { Alert, Empty, List, Space, Tag, Typography } from 'antd'
import { tripsApi } from '../../api/trips'
import { formatDateTime, notificationTypeColor } from '../../trips/types'
import { usePolling } from '../../hooks/usePolling'
import { useUnauthorizedRedirect } from '../../hooks/useUnauthorizedRedirect'
import StaleDataAlert from '../../ui/StaleDataAlert'

const NOTIFICATIONS_POLL_MS = 2000 // spec §3

export default function NotificationsPanel(): ReactElement {
  const { data, error, loading } = usePolling((signal) => tripsApi.listNotifications(0, 20, signal), { intervalMs: NOTIFICATIONS_POLL_MS })
  useUnauthorizedRedirect(error)

  const items = data?.content ?? []

  return (
    <div>
      {error && !data && <Alert style={{ marginBottom: 12 }} type="error" message={`${error.code}: ${error.message}`} />}
      {data && <StaleDataAlert error={error} />}
      {!loading && items.length === 0 && !error ? (
        <Empty description="Chưa có thông báo nào." />
      ) : (
        <List
          size="small"
          dataSource={items}
          renderItem={(n) => (
            <List.Item>
              <Space direction="vertical">
                <Space>
                  <Tag color={notificationTypeColor(n.type)}>{n.type}</Tag>
                  <Typography.Text type="secondary">{formatDateTime(n.createdAt)}</Typography.Text>
                </Space>
                <Typography.Text>{n.message}</Typography.Text>
              </Space>
            </List.Item>
          )}
        />
      )}
    </div>
  )
}
