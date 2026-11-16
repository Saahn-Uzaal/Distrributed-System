/**
 * T28 — offer hiện tại của tài xế (GET /api/drivers/me/offer). Chỉ mount khi server báo RESERVED;
 * polling 2 s, cleanup khi unmount.
 *
 * Accept chưa rõ kết quả (202 DRIVER_OFFERED, timeout, 5xx) không bị coi là thất bại: GET /api/drivers/me
 * ở trang cha sẽ chuyển sang BUSY khi Driver đã confirm; còn offer thì tài xế bấm lại được (idempotent).
 */
import { useEffect, useState } from 'react'
import type { ReactElement } from 'react'
import { Alert, Button, Card, Descriptions, Space, Typography, message } from 'antd'
import { ApiError } from '../../api/client'
import { driversApi } from '../../api/drivers'
import { tripsApi } from '../../api/trips'
import { offerRemainingMs } from '../../drivers/types'
import { usePolling } from '../../hooks/usePolling'
import { useUnauthorizedRedirect } from '../../hooks/useUnauthorizedRedirect'
import { formatDateTime, formatVnd } from '../../trips/types'
import StaleDataAlert from '../../ui/StaleDataAlert'
import { isAmbiguousOutcome } from '../../ui/errorHint'

const OFFER_POLL_MS = 2_000 // spec §3

interface Props {
  /** Thao tác xong → trang cha đọc lại state server ngay, không chờ chu kỳ poll. */
  onChanged: () => void
}

export default function OfferPanel({ onChanged }: Props): ReactElement {
  const { data: offer, error, refresh } = usePolling((signal) => driversApi.getOffer(signal), { intervalMs: OFFER_POLL_MS })
  useUnauthorizedRedirect(error)

  const [acting, setActing] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [nowMs, setNowMs] = useState(() => Date.now())

  /** Đếm ngược theo expiresAt (1 s/tick) — chỉ hiển thị; server quyết định khi accept. */
  useEffect(() => {
    const id = setInterval(() => setNowMs(Date.now()), 1000)
    return () => clearInterval(id)
  }, [])

  async function act(run: () => Promise<void>): Promise<void> {
    if (acting) return
    setActing(true)
    setActionError(null)
    try {
      await run()
    } catch (e) {
      if (e instanceof ApiError && isAmbiguousOutcome(e.code)) {
        message.info('Chưa rõ kết quả từ server — đang kiểm tra lại trạng thái...')
      } else {
        setActionError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Lỗi không xác định.')
      }
    } finally {
      setActing(false)
      refresh()
      onChanged()
    }
  }

  async function handleAccept(): Promise<void> {
    if (!offer) return
    const { tripId, attempt } = offer
    await act(async () => {
      const res = await tripsApi.acceptTrip(tripId, attempt)
      if (res.state === 'ACCEPTED') message.success('Đã nhận chuyến.')
      else message.info('Đang xác nhận với server — chờ đồng bộ...')
    })
  }

  async function handleReject(): Promise<void> {
    if (!offer) return
    const { tripId, attempt } = offer
    await act(async () => {
      const res = await tripsApi.rejectTrip(tripId, attempt)
      message.info(res.state === 'NO_DRIVER' ? 'Chuyến không còn tài xế (hết lượt đề nghị).' : 'Đã từ chối — hệ thống tìm tài xế khác.')
    })
  }

  if (!offer) {
    return (
      <>
        {actionError && <Alert type="error" message={actionError} />}
        <StaleDataAlert error={error} />
        <Alert type="info" message="Đang đồng bộ offer với server..." />
      </>
    )
  }

  const remaining = offerRemainingMs(offer.expiresAt, nowMs)

  return (
    <Card size="small" title={`Offer — chuyến ${offer.tripId.slice(0, 8)}…`}>
      {actionError && <Alert style={{ marginBottom: 12 }} type="error" message={actionError} />}
      <StaleDataAlert error={error} />
      <Descriptions column={1} size="small">
        <Descriptions.Item label="Từ">
          {offer.pickup.latitude.toFixed(4)}, {offer.pickup.longitude.toFixed(4)}
        </Descriptions.Item>
        <Descriptions.Item label="Đến">
          {offer.destination.latitude.toFixed(4)}, {offer.destination.longitude.toFixed(4)}
        </Descriptions.Item>
        <Descriptions.Item label="Khoảng cách">{offer.distanceM} m</Descriptions.Item>
        <Descriptions.Item label="Giá">{formatVnd(offer.fareVnd)}</Descriptions.Item>
        <Descriptions.Item label="Lượt đề nghị">{offer.attempt}</Descriptions.Item>
        <Descriptions.Item label="Hết hạn sau">
          {remaining > 0 ? (
            `${Math.ceil(remaining / 1000)} giây (${formatDateTime(offer.expiresAt)})`
          ) : (
            <Typography.Text type="secondary">Đã hết hạn</Typography.Text>
          )}
        </Descriptions.Item>
      </Descriptions>
      <Space style={{ marginTop: 12 }}>
        <Button type="primary" loading={acting} disabled={remaining <= 0} onClick={handleAccept}>
          Nhận chuyến
        </Button>
        <Button danger loading={acting} onClick={handleReject}>
          Từ chối
        </Button>
      </Space>
    </Card>
  )
}
