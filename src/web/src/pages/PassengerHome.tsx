/**
 * T27 — màn hình hành khách: đặt chuyến (quote/create + Idempotency-Key),
 * danh sách/chuyến chi tiết với trạng thái chờ + payment, hủy chuyến, thông báo.
 */
import { useEffect, useState } from 'react'
import type { ReactElement } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Spin, Space, Tabs, Tag, Typography } from 'antd'
import { clearSession } from '../auth/session'
import { bumpAuthGeneration } from '../auth/generation'
import { useMe } from '../hooks/useMe'
import CreateTripForm from './passenger/CreateTripForm'
import TripsListPanel from './passenger/TripsListPanel'
import NotificationsPanel from './passenger/NotificationsPanel'

export default function PassengerHome(): ReactElement {
  const navigate = useNavigate()
  // R03 fix: chỉ tin dữ liệu /me từ server; không fallback sang session cache cũ.
  const { user, loading, error, retry } = useMe()

  // F19: CHỈ 401 (AUTH) mới chuyển về trang login; lỗi mạng/5xx hiện nút "Thử lại".
  useEffect(() => {
    if (!loading && !user && error === 'AUTH') navigate('/login', { replace: true })
  }, [loading, user, error, navigate])

  function logout(): void {
    bumpAuthGeneration()
    clearSession()
    navigate('/login')
  }

  return (
    <Card style={{ maxWidth: 1080, margin: '24px auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <Space>
          <Typography.Title level={3} style={{ margin: 0 }}>
            Hành khách
          </Typography.Title>
          <Tag color="blue">PASSENGER</Tag>
        </Space>
        {user && (
          <Typography.Text type="secondary">
            {user.fullName} ({user.email})
          </Typography.Text>
        )}
      </div>

      {loading ? (
        <Spin />
      ) : !user && error === 'NETWORK' ? (
        <Alert type="warning" message="Không thể xác thực phiên." description={<Button onClick={retry}>Thử lại</Button>} />
      ) : user ? (
        <Tabs
          items={[
            { key: 'trips', label: 'Chuyến đi', children: <TripsTab /> },
            { key: 'notifications', label: 'Thông báo', children: <NotificationsPanel /> }
          ]}
        />
      ) : null}

      <div style={{ marginTop: 16, textAlign: 'right' }}>
        <Button onClick={logout}>Đăng xuất</Button>
      </div>
    </Card>
  )
}

/** Tab "Chuyến đi": form đặt chuyến + danh sách (polling 5 s, cleanup khi đổi tab). */
function TripsTab(): ReactElement {
  // Bump sau khi tạo trip thành công → list refresh ngay, không chờ chu kỳ polling.
  const [createdTick, setCreatedTick] = useState(0)
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <CreateTripForm onCreated={() => setCreatedTick((t) => t + 1)} />
      <TripsListPanel refreshSignal={createdTick} />
    </div>
  )
}
