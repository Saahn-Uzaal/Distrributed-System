/**
 * T28 — panel chuyến đang phục vụ của tài xế (GET /api/trips/{id}).
 * Polling 2 s; dừng khi trip kết thúc hoặc NOT_FOUND, cleanup khi unmount.
 * Start chỉ từ ACCEPTED, complete chỉ từ IN_PROGRESS ("disable thao tác sai trạng thái").
 * Panel tự biến mất khi GET /api/drivers/me báo tài xế không còn BUSY (finish xong, T22).
 */
import { useState } from 'react'
import type { ReactElement } from 'react'
import { Alert, Button, Card, Descriptions, Space, Spin, Tag, Typography, message } from 'antd'
import { ApiError } from '../../api/client'
import { tripsApi } from '../../api/trips'
import { canCompleteTrip, canStartTrip, formatDateTime, formatVnd, isTerminalTrip, isTripSettled, paymentStatusLabel, tripStateColor, tripStateLabel } from '../../trips/types'
import { usePolling } from '../../hooks/usePolling'
import { useUnauthorizedRedirect } from '../../hooks/useUnauthorizedRedirect'
import StaleDataAlert from '../../ui/StaleDataAlert'

const TRIP_POLL_MS = 2000 // spec §3

interface Props {
  tripId: string
}

export default function BusyTripPanel({ tripId }: Props): ReactElement {
  const [acting, setActing] = useState(false)

  const { data, error, loading, refresh } = usePolling(
    (signal) => tripsApi.getTrip(tripId, signal),
    { intervalMs: TRIP_POLL_MS, stopWhen: isTripSettled, stopOnErrorCodes: ['NOT_FOUND'] }
  )
  useUnauthorizedRedirect(error)

  async function handleAction(action: 'start' | 'complete'): Promise<void> {
    setActing(true)
    try {
      if (action === 'start') await tripsApi.startTrip(tripId)
      else await tripsApi.completeTrip(tripId)
      if (action === 'complete') message.success('Chuyến đã hoàn thành — đang xử lý thanh toán.')
      else message.success('Đã bắt đầu chuyến.')
    } catch (e) {
      message.error(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Lỗi không xác định.')
    } finally {
      setActing(false)
      refresh()
    }
  }

  if (!loading && error?.code === 'NOT_FOUND') {
    return (
      <Card size="small" title={`Chuyến ${tripId.slice(0, 8)}…`}>
        <Alert type="error" message="Không tìm thấy chuyến này hoặc bạn không còn quyền xem." />
      </Card>
    )
  }

  if (!data) {
    return (
      <Card size="small" title={`Chuyến ${tripId.slice(0, 8)}…`}>
        {error ? (
          <Alert type="warning" message={`Không tải được chuyến: ${error.code}`} description={<Button onClick={refresh}>Thử lại</Button>} />
        ) : (
          <Spin />
        )}
      </Card>
    )
  }

  const trip = data
  return (
    <Card size="small" title={`Chuyến đang phục vụ — ${tripId.slice(0, 8)}…`}>
      <StaleDataAlert error={error} />
      <Space style={{ marginBottom: 12 }} wrap>
        <Tag color={tripStateColor(trip.state)}>{tripStateLabel(trip.state)}</Tag>
        {trip.paymentStatus !== 'NOT_REQUESTED' && (
          <Typography.Text type="secondary">{paymentStatusLabel(trip.paymentStatus)}</Typography.Text>
        )}
      </Space>
      <Descriptions column={1} size="small">
        <Descriptions.Item label="Từ">
          {trip.pickup.latitude.toFixed(4)}, {trip.pickup.longitude.toFixed(4)}
        </Descriptions.Item>
        <Descriptions.Item label="Đến">
          {trip.destination.latitude.toFixed(4)}, {trip.destination.longitude.toFixed(4)}
        </Descriptions.Item>
        <Descriptions.Item label="Khoảng cách">{trip.distanceM} m</Descriptions.Item>
        <Descriptions.Item label="Giá">{formatVnd(trip.fareVnd)}</Descriptions.Item>
        <Descriptions.Item label="Tạo lúc">{formatDateTime(trip.createdAt)}</Descriptions.Item>
      </Descriptions>
      <Space style={{ marginTop: 12 }}>
        {canStartTrip(trip.state) && (
          <Button type="primary" loading={acting} onClick={() => handleAction('start')}>
            Bắt đầu chuyến
          </Button>
        )}
        {canCompleteTrip(trip.state) && (
          <Button type="primary" loading={acting} onClick={() => handleAction('complete')}>
            Hoàn thành chuyến
          </Button>
        )}
        {isTerminalTrip(trip.state) && (
          <Typography.Text type="secondary">Chuyến đã kết thúc — trạng thái tài xế sẽ tự cập nhật.</Typography.Text>
        )}
      </Space>
    </Card>
  )
}
