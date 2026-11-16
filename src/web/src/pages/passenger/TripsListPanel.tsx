/**
 * T27 — danh sách chuyến của hành khách (GET /api/trips, phân trang §1.3).
 * Polling 2 s; dừng khi tab unmount (cleanup) hoặc sau thao tác → refresh().
 */
import { useEffect, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Button, Empty, Modal, Space, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { ApiError } from '../../api/client'
import { tripsApi } from '../../api/trips'
import type { Trip } from '../../trips/types'
import { canCancelTrip, formatDateTime, formatVnd, isTerminalTrip, tripStateColor, tripStateLabel } from '../../trips/types'
import { usePolling } from '../../hooks/usePolling'
import { useUnauthorizedRedirect } from '../../hooks/useUnauthorizedRedirect'
import StaleDataAlert from '../../ui/StaleDataAlert'

const LIST_POLL_MS = 2000 // spec §3
const PAGE_SIZE = 20

interface Props {
  /** Bump khi trip mới được tạo → list fetch ngay (không chờ chu kỳ polling). */
  refreshSignal?: number
}

export default function TripsListPanel({ refreshSignal = 0 }: Props): ReactElement {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const [cancellingId, setCancellingId] = useState<string | null>(null)

  const { data, error, loading, refresh } = usePolling((signal) => tripsApi.listTrips(page, PAGE_SIZE, signal), { intervalMs: LIST_POLL_MS })
  useUnauthorizedRedirect(error)

  // Đổi trang / trip mới tạo → fetch ngay (polling tiếp tục); bỏ qua lần mount
  // vì usePolling đã fetch ngay khi mount.
  const firstRun = useRef(true)
  useEffect(() => {
    if (firstRun.current) {
      firstRun.current = false
      return
    }
    refresh()
  }, [page, refreshSignal, refresh])

  const trips = data?.content ?? []
  const hasActiveTrip = trips.some((t) => !isTerminalTrip(t.state))

  async function handleCancel(trip: Trip): Promise<void> {
    setCancellingId(trip.tripId)
    try {
      const res = await tripsApi.cancelTrip(trip.tripId)
      if (res.state === 'CANCELLED') message.success('Chuyến đã hủy.')
      else message.info('Đang hủy — trạng thái sẽ cập nhật tự động.')
      refresh()
    } catch (e) {
      message.error(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Lỗi không xác định khi hủy chuyến.')
    } finally {
      setCancellingId(null)
    }
  }

  const columns: ColumnsType<Trip> = [
    {
      title: 'Trạng thái',
      key: 'state',
      render: (_v, t) => <Tag color={tripStateColor(t.state)}>{tripStateLabel(t.state)}</Tag>
    },
    {
      title: 'Từ → Đến',
      key: 'route',
      render: (_v, t) => (
        <Typography.Text>
          {t.pickup.latitude.toFixed(4)}, {t.pickup.longitude.toFixed(4)} → {t.destination.latitude.toFixed(4)},{''}{' '}
          {t.destination.longitude.toFixed(4)}
        </Typography.Text>
      )
    },
    { title: 'Khoảng cách', key: 'distanceM', render: (_v, t) => `${t.distanceM} m` },
    { title: 'Giá', key: 'fareVnd', render: (_v, t) => formatVnd(t.fareVnd) },
    {
      title: 'Thanh toán',
      key: 'paymentStatus',
      render: (_v, t) => (t.paymentStatus === 'NOT_REQUESTED' ? <Typography.Text type="secondary">—</Typography.Text> : t.paymentStatus)
    },
    { title: 'Tạo lúc', key: 'createdAt', render: (_v, t) => formatDateTime(t.createdAt) },
    {
      title: 'Hành động',
      key: 'actions',
      render: (_v, t) => (
        <Space>
          <Button size="small" onClick={() => navigate(`/passenger/trips/${t.tripId}`)}>
            Chi tiết
          </Button>
          {canCancelTrip(t.state) && (
            <Button
              size="small"
              danger
              loading={cancellingId === t.tripId}
              onClick={() =>
                Modal.confirm({
                  title: 'Hủy chuyến này?',
                  content: `Chuyến ${t.tripId.slice(0, 8)}… (${tripStateLabel(t.state)}) sẽ bị hủy.`,
                  okText: 'Hủy chuyến',
                  cancelText: 'Giữ lại',
                  onOk: () => handleCancel(t)
                })
              }
            >
              Hủy
            </Button>
          )}
        </Space>
      )
    }
  ]

  return (
    <div>
      {error && !data && (
        <Alert style={{ marginBottom: 12 }} type="error" message={`${error.code}: ${error.message}`} />
      )}
      {data && <StaleDataAlert error={error} />}
      {!loading && trips.length === 0 && !error ? (
        <Empty description="Chưa có chuyến nào. Đặt chuyến mới ở form bên trên." />
      ) : (
        <>
          {hasActiveTrip && (
            <Alert
              style={{ marginBottom: 12 }}
              type="info"
              message="Bạn đang có một chuyến chưa kết thúc — không thể đặt thêm cho đến khi chuyến này xong hoặc bị hủy."
            />
          )}
          <Table<Trip>
            rowKey="tripId"
            size="small"
            loading={loading && !data}
            columns={columns}
            dataSource={trips}
            pagination={{
              current: page + 1,
              pageSize: PAGE_SIZE,
              total: data?.totalElements ?? 0,
              onChange: (p) => setPage(p - 1),
              size: 'small'
            }}
          />
        </>
      )}
    </div>
  )
}
