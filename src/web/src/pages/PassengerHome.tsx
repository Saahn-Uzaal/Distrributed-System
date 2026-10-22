import { useEffect } from 'react'
import type { ReactElement } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Card, Spin, Tag, Typography } from 'antd'
import { clearSession } from '../auth/session'
import { bumpAuthGeneration } from '../auth/generation'
import { useMe } from '../hooks/useMe'

/** Placeholder — màn hình nghiệp vụ hành khách triển khai ở T27. */
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
    <Card style={{ maxWidth: 720, margin: '48px auto' }}>
      <Typography.Title level={3}>Hành khách — màn hình placeholder</Typography.Title>
      <Tag color="blue">PASSENGER</Tag>
      {loading ? (
        <Spin />
      ) : user && (
        <p>
          Đăng nhập thành công: <strong>{user.fullName}</strong> ({user.email})
        </p>
      )}
      {!loading && !user && error === 'NETWORK' && (
        <Button onClick={retry}>Thử lại</Button>
      )}
      <Typography.Paragraph type="secondary">
        Màn hình nghiệp vụ hành khách (quote, tạo chuyến, theo dõi trạng thái) sẽ được triển khai ở task T27.
      </Typography.Paragraph>
      <Button onClick={logout}>Đăng xuất</Button>
    </Card>
  )
}
