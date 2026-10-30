/**
 * T27 — chi tiết chuyến (GET /api/trips/{id}).
 * Polling 3 s; DỪNG khi trip kết thúc VÀ payment đã có kết quả, hoặc lỗi NOT_FOUND/UNAUTHORIZED,
 * và luôn cleanup khi unmount (acceptance "polling cleanup").
 */
import type { ReactElement } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Alert, Button, Card, Descriptions, Modal, Space, Spin, Tag, Typography, message } from 'antd'
import { ApiError } from '../api/client'
import { tripsApi } from '../api/trips'
import { canCancelTrip, formatDateTime, formatVnd, isTripSettled, paymentStatusLabel, tripStateColor, tripStateLabel } from '../trips/types'
import { usePolling } from '../hooks/usePolling'
import { useUnauthorizedRedirect } from '../hooks/useUnauthorizedRedirect'
import StaleDataAlert from '../ui/StaleDataAlert'

const DETAIL_POLL_MS = 3000

export default function TripDetailPage(): ReactElement {
  const navigate = useNavigate()
  const { tripId } = useParams<{ tripId: string }>()

  const { data, error, loading, refresh } = usePolling(
    (signal) => tripsApi.getTrip(tripId!, signal),
    {
      intervalMs: DETAIL_POLL_MS,
      stopWhen: isTripSettled,
      stopOnErrorCodes: ['NOT_FOUND']
    }
  )
  useUnauthorizedRedirect(error)

  async function handleCancel(): Promise<void> {
    try {
      const res = await tripsApi.cancelTrip(tripId!)
      if (res.state === 'CANCELLED') message.success('Chuyến đã hủy.')
      else message.info('Đang hủy — trạng thái sẽ cập nhật tự động.')
      refresh()
    } catch (e) {
      message.error(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Lỗi không xác định khi hủy chuyến.')
    }
  }

  if (!loading && error?.code === 'NOT_FOUND') {
    return (
      <Card style={{ maxWidth: 720, margin: '48px auto' }}>
        <Alert type="error" message="Không tìm thấy chuyến này hoặc bạn không có quyền xem." />
        <Button style={{ marginTop: 16 }} onClick={() => navigate('/passenger')}>
          Về danh sách chuyến
        </Button>
      </Card>
    )
  }

  if (!data) {
    return (
      <Card style={{ maxWidth: 720, margin: '48px auto' }}>
        {error ? (
          <Alert
            type="error"
            message={`Không tải được chuyến: ${error.code}`}
            description={<Button onClick={refresh}>Thử lại</Button>}
          />
        ) : (
          <Spin />
        )}
      </Card>
    )
  }

  const trip = data
  const showCancel = canCancelTrip(trip.state)

  return (
    <Card style={{ maxWidth: 720, margin: '24px auto' }}>
      <Space style={{ marginBottom: 16 }} wrap>
        <Typography.Title level={3} style={{ margin: 0 }}>
          Chi tiết chuyến {trip.tripId.slice(0, 8)}…
        </Typography.Title>
        <Tag color={tripStateColor(trip.state)}>{tripStateLabel(trip.state)}</Tag>
      </Space>

      <StaleDataAlert error={error} />
      {!isTripSettled(trip) && (
        <Alert style={{ marginBottom: 16 }} type="info" message="Trạng thái đang cập nhật tự động — không cần tải lại trang." />
      )}

      <Descriptions column={1} size="small">
        <Descriptions.Item label="Từ">
          {trip.pickup.latitude.toFixed(4)}, {trip.pickup.longitude.toFixed(4)}
        </Descriptions.Item>
        <Descriptions.Item label="Đến">
          {trip.destination.latitude.toFixed(4)}, {trip.destination.longitude.toFixed(4)}
        </Descriptions.Item>
        <Descriptions.Item label="Khoảng cách">{trip.distanceM} m</Descriptions.Item>
        <Descriptions.Item label="Giá dự kiến">{formatVnd(trip.fareVnd)}</Descriptions.Item>
        <Descriptions.Item label="Thanh toán">
          {paymentStatusLabel(trip.paymentStatus)}
          {trip.paymentStatus === 'PENDING' && (
            <Typography.Text type="secondary"> — đang xử lý, kết quả sẽ cập nhật tự động</Typography.Text>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="Tài xế">
          {trip.driverId ? (
            <>
              {trip.driverId.slice(0, 8)}…{' '}
              <Typography.Text type="secondary">(MVP chỉ hiển thị ID tài xế)</Typography.Text>
            </>
          ) : (
            'Chưa có'
          )}
        </Descriptions.Item>
        <Descriptions.Item label="Lượt tìm">{trip.matchingAttempt ?? 1}</Descriptions.Item>
        <Descriptions.Item label="Tạo lúc">{formatDateTime(trip.createdAt)}</Descriptions.Item>
        {trip.updatedAt && <Descriptions.Item label="Cập nhật lúc">{formatDateTime(trip.updatedAt)}</Descriptions.Item>}
      </Descriptions>

      <Space style={{ marginTop: 16 }}>
        <Link to="/passenger">
          <Button>Quay lại danh sách</Button>
        </Link>
        {showCancel && (
          <Button
            danger
            onClick={() =>
              Modal.confirm({
                title: 'Hủy chuyến này?',
                content: `Chuyến ${trip.tripId.slice(0, 8)}… (${tripStateLabel(trip.state)}) sẽ bị hủy.`,
                okText: 'Hủy chuyến',
                cancelText: 'Giữ lại',
                onOk: handleCancel
              })
            }
          >
            Hủy chuyến
          </Button>
        )}
      </Space>
    </Card>
  )
}
