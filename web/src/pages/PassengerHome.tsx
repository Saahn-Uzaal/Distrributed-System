import { useEffect } from 'react'
import type { ReactElement } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Card, Spin, Tag, Typography } from 'antd'
import { clearSession } from '../auth/session'
import { useMe } from '../hooks/useMe'

/** Placeholder — màn hình nghiệp vụ hành khách triển khai ở T27. */
export default function PassengerHome(): ReactElement {
  const navigate = useNavigate()
  // R03 fix: chỉ tin dữ liệu /me từ server; không fallback sang session cache cũ.
  const { user, loading } = useMe()

  // Server từ chối phiên (401 -> client đã xóa session) hoặc /me lỗi -> về trang login.
  useEffect(() => {
    if (!loading && !user) navigate('/login', { replace: true })
  }, [loading, user, navigate])

  function logout(): void {
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
      <Typography.Paragraph type="secondary">
        Màn hình nghiệp vụ hành khách (quote, tạo chuyến, theo dõi trạng thái) sẽ được triển khai ở task T27.
      </Typography.Paragraph>
      <Button onClick={logout}>Đăng xuất</Button>
    </Card>
  )
}
