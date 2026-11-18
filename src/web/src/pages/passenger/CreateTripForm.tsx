import { useState } from 'react'
import type { ReactElement } from 'react'
import { Alert, Button, Card, Col, Descriptions, Row, Space, Typography, message } from 'antd'
import { ApiError, newUuid } from '../../api/client'
import { tripsApi } from '../../api/trips'
import type { LocationSelection, QuoteResult, TripPoint } from '../../trips/types'
import { formatVnd } from '../../trips/types'
import { errorHint, isAmbiguousOutcome } from '../../ui/errorHint'
import AddressSearchField from '../../maps/AddressSearchField'
import RouteMap from '../../maps/RouteMap'

interface Props { onCreated: () => void }

export default function CreateTripForm({ onCreated }: Props): ReactElement {
  const [pickup, setPickup] = useState<LocationSelection | null>(null)
  const [destination, setDestination] = useState<LocationSelection | null>(null)
  const [quote, setQuote] = useState<QuoteResult | null>(null)
  const [quoting, setQuoting] = useState(false)
  const [creating, setCreating] = useState(false)
  const [pendingKey, setPendingKey] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [hint, setHint] = useState<string | null>(null)

  function invalidateQuote(): void {
    setQuote(null)
    setPendingKey(null)
    setError(null)
    setHint(null)
  }

  function selectPickup(value: LocationSelection): void { invalidateQuote(); setPickup(value) }
  function selectDestination(value: LocationSelection): void { invalidateQuote(); setDestination(value) }
  function editPickup(): void { invalidateQuote(); setPickup(null) }
  function editDestination(): void { invalidateQuote(); setDestination(null) }

  async function handleQuote(): Promise<void> {
    if (!pickup || !destination || quoting) return
    setQuoting(true); setError(null); setHint(null)
    try {
      setQuote(await tripsApi.quote(pickup.locationToken, destination.locationToken))
    } catch (e) {
      setQuote(null)
      setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Không thể tính tuyến đường lúc này.')
      setHint(e instanceof ApiError ? errorHint(e.code) : null)
    } finally { setQuoting(false) }
  }

  async function handleCreate(): Promise<void> {
    if (!quote || creating) return
    if (Date.parse(quote.expiresAt) <= Date.now()) {
      setQuote(null); setError('Báo giá đã hết hạn. Vui lòng xem lại tuyến đường và giá.'); return
    }
    const key = pendingKey ?? newUuid()
    setPendingKey(key); setCreating(true); setError(null); setHint(null)
    try {
      const result = await tripsApi.createTrip(key, quote.quoteToken)
      message.success(`Đã đặt chuyến — đang tìm tài xế (trip ${result.tripId.slice(0, 8)}…).`)
      setPendingKey(null); setQuote(null); onCreated()
    } catch (e) {
      if (e instanceof ApiError && isAmbiguousOutcome(e.code)) setHint(errorHint(e.code))
      else {
        setPendingKey(null)
        setError(e instanceof ApiError ? `${e.code}: ${e.message}` : 'Lỗi không xác định')
        setHint(e instanceof ApiError ? errorHint(e.code) : null)
      }
    } finally { setCreating(false) }
  }

  const mapPickup: TripPoint | null = quote ? { ...quote.pickup, address: quote.pickup.displayName } : null
  const mapDestination: TripPoint | null = quote ? { ...quote.destination, address: quote.destination.displayName } : null

  return (
    <Card title="Đặt chuyến mới">
      <Row gutter={[20, 20]}>
        <Col xs={24} lg={quote ? 10 : 24}>
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <AddressSearchField label="Điểm đón" selected={pickup} search={tripsApi.searchLocations}
              onSelect={selectPickup} onQueryChange={editPickup} />
            <AddressSearchField label="Điểm đến" selected={destination} search={tripsApi.searchLocations}
              onSelect={selectDestination} onQueryChange={editDestination} />
            <Button type="primary" onClick={() => void handleQuote()} loading={quoting}
              disabled={!pickup || !destination || quoting} block>
              Xem tuyến đường và giá
            </Button>
            {quote && <>
              <Descriptions bordered column={1} size="small">
                <Descriptions.Item label="Khoảng cách">{new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 1 }).format(quote.distanceM / 1000)} km</Descriptions.Item>
                <Descriptions.Item label="Thời gian dự kiến">{Math.max(1, Math.ceil(quote.durationS / 60))} phút</Descriptions.Item>
                <Descriptions.Item label="Giá dự kiến">{formatVnd(quote.fareVnd)}</Descriptions.Item>
              </Descriptions>
              <Button type="primary" size="large" block loading={creating} onClick={() => void handleCreate()}>
                Đặt chuyến
              </Button>
            </>}
            {error && <Alert type="error" message={error} description={hint ?? undefined} />}
            {!error && hint && <Alert type="warning" message={hint} />}
            <Typography.Text type="secondary">Kết quả địa chỉ giới hạn tại Việt Nam. Giá được máy chủ tính từ tuyến đường thực tế.</Typography.Text>
          </Space>
        </Col>
        {quote && mapPickup && mapDestination && <Col xs={24} lg={14}>
          <RouteMap pickup={mapPickup} destination={mapDestination} geometry={quote.routeGeometry} />
        </Col>}
      </Row>
    </Card>
  )
}
