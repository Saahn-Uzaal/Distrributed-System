/**
 * T27 — form quote + đặt chuyến cho hành khách (contract §3.3).
 *
 * Idempotency (acceptance "bấm lại không tạo trip trùng"):
 * - Mỗi lần bấm "Đặt chuyến" dùng một Idempotency-Key UUID.
 * - Nếu request lỗi timeout/mạng/5xx (kết quả server chưa rõ — Nginx timeout 3 s trả 504 trước
 *   timeout client), key được GIỮ —
 *   bấm lại replay cùng key + payload → server trả response gốc, không tạo trip mới.
 * - Khi người dùng sửa tọa độ, key bị xóa → lần sau sinh key mới (tránh 409 IDEMPOTENCY_MISMATCH).
 */
import { useRef, useState } from 'react'
import type { ReactElement } from 'react'
import { Alert, Button, Card, Form, InputNumber, Space, Typography, message } from 'antd'
import { ApiError, newUuid } from '../../api/client'
import { tripsApi } from '../../api/trips'
import type { Coord, QuoteResult } from '../../trips/types'
import { formatVnd } from '../../trips/types'
import { errorHint, isAmbiguousOutcome } from '../../ui/errorHint'

interface Props {
  /** Gọi khi trip tạo thành công (list tự refresh qua polling). */
  onCreated: () => void
}

function validCoord(lat: number | null, lng: number | null): boolean {
  return (
    lat !== null &&
    lng !== null &&
    Number.isFinite(lat) &&
    Number.isFinite(lng) &&
    Math.abs(lat) <= 90 &&
    Math.abs(lng) <= 180
  )
}

export default function CreateTripForm({ onCreated }: Props): ReactElement {
  const [pickupLat, setPickupLat] = useState<number | null>(null)
  const [pickupLng, setPickupLng] = useState<number | null>(null)
  const [destLat, setDestLat] = useState<number | null>(null)
  const [destLng, setDestLng] = useState<number | null>(null)

  const [quote, setQuote] = useState<QuoteResult | null>(null)
  const [quoting, setQuoting] = useState(false)
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [hint, setHint] = useState<string | null>(null)

  // Key của request tạo chuyến đang "chưa rõ kết quả" — giữ để retry không trùng trip.
  const [pendingKey, setPendingKey] = useState<string | null>(null)

  // Quote đang bay khi người dùng sửa tọa độ là quote của tọa độ cũ — bỏ kết quả của nó.
  const quoteGeneration = useRef(0)

  function coordsChanged(): void {
    quoteGeneration.current += 1
    setPendingKey(null)
    setQuote(null)
  }

  const ready = validCoord(pickupLat, pickupLng) && validCoord(destLat, destLng)

  async function handleQuote(): Promise<void> {
    if (!ready || quoting) return
    setError(null)
    setHint(null)
    setQuoting(true)
    const generation = quoteGeneration.current
    try {
      const res = await tripsApi.quote(
        { latitude: pickupLat!, longitude: pickupLng! },
        { latitude: destLat!, longitude: destLng! }
      )
      if (generation === quoteGeneration.current) setQuote(res)
    } catch (e) {
      if (generation === quoteGeneration.current) {
        setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Lỗi không xác định')
        setHint(e instanceof ApiError ? errorHint(e.code) : null)
      }
    } finally {
      setQuoting(false)
    }
  }

  async function handleCreate(): Promise<void> {
    if (!ready || creating) return
    const pickup: Coord = { latitude: pickupLat!, longitude: pickupLng! }
    const destination: Coord = { latitude: destLat!, longitude: destLng! }
    // Retry sau lỗi chưa rõ kết quả tái dùng key cũ; các trường hợp khác sinh key mới.
    const key = pendingKey ?? newUuid()
    setPendingKey(key)
    setError(null)
    setHint(null)
    setCreating(true)
    try {
      const res = await tripsApi.createTrip(key, pickup, destination)
      message.success(`Đã đặt chuyến — đang tìm tài xế (trip ${res.tripId.slice(0, 8)}…).`)
      setPendingKey(null)
      onCreated()
    } catch (e) {
      if (e instanceof ApiError && isAmbiguousOutcome(e.code)) {
        // Giữ pendingKey → bấm lại replay cùng key, không tạo trip trùng.
        setHint(errorHint(e.code))
      } else {
        setPendingKey(null)
        setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Lỗi không xác định')
        setHint(e instanceof ApiError ? errorHint(e.code) : null)
      }
    } finally {
      setCreating(false)
    }
  }

  return (
    <Card size="small" title="Đặt chuyến mới">
      <Form layout="vertical">
        <Space.Compact block>
          <InputNumber
            addonBefore="Từ (lat)"
            placeholder="21.0345"
            step={0.0001}
            value={pickupLat}
            onChange={(v) => {
              setPickupLat(v ?? null)
              coordsChanged()
            }}
            style={{ width: '50%' }}
          />
          <InputNumber
            addonBefore="lng"
            placeholder="105.8409"
            step={0.0001}
            value={pickupLng}
            onChange={(v) => {
              setPickupLng(v ?? null)
              coordsChanged()
            }}
            style={{ width: '50%' }}
          />
        </Space.Compact>
        <Space.Compact block>
          <InputNumber
            addonBefore="Đến (lat)"
            placeholder="21.0364"
            step={0.0001}
            value={destLat}
            onChange={(v) => {
              setDestLat(v ?? null)
              coordsChanged()
            }}
            style={{ width: '50%' }}
          />
          <InputNumber
            addonBefore="lng"
            placeholder="105.8496"
            step={0.0001}
            value={destLng}
            onChange={(v) => {
              setDestLng(v ?? null)
              coordsChanged()
            }}
            style={{ width: '50%' }}
          />
        </Space.Compact>

        <Space wrap style={{ marginTop: 12 }}>
          <Button onClick={handleQuote} loading={quoting} disabled={!ready}>
            Tính giá
          </Button>
          <Button type="primary" onClick={handleCreate} loading={creating} disabled={!ready || creating}>
            Đặt chuyến
          </Button>
        </Space>

        {quote && (
          <Alert
            style={{ marginTop: 12 }}
            type="info"
            message={`Khoảng cách ${quote.distanceM} m — dự kiến ${formatVnd(quote.fareVnd)} (giá server tính, không tin frontend).`}
          />
        )}
        {error && <Alert style={{ marginTop: 12 }} type="error" message={error} description={hint ?? undefined} />}
        {!error && hint && <Alert style={{ marginTop: 12 }} type="warning" message={hint} />}
        <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
          Mỗi hành khách chỉ có tối đa một chuyến chưa kết thúc — server chặn tạo trùng (409 ACTIVE_TRIP_EXISTS).
        </Typography.Text>
      </Form>
    </Card>
  )
}
