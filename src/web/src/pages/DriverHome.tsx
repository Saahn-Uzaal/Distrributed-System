/**
 * T28 — màn hình tài xế: hồ sơ, online/location/heartbeat, offer accept/reject, start/complete.
 *
 * SERVER là nguồn sự thật: mọi state hiển thị đọc từ GET /api/drivers/me (poll 2 s, không có side effect).
 * Mở trang hay tải lại KHÔNG đổi trạng thái tài xế; client không cache state.
 * Heartbeat + polling đều có cleanup khi unmount / đổi trạng thái.
 */
import { useEffect, useRef, useState } from 'react'
import type { ReactElement } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Descriptions, Form, Input, InputNumber, Select, Space, Spin, Tag, Typography, message } from 'antd'
import { clearSession } from '../auth/session'
import { bumpAuthGeneration } from '../auth/generation'
import { useMe } from '../hooks/useMe'
import { usePolling } from '../hooks/usePolling'
import { useUnauthorizedRedirect } from '../hooks/useUnauthorizedRedirect'
import { ApiError } from '../api/client'
import { driversApi } from '../api/drivers'
import type { VehicleType } from '../drivers/types'
import { driverStatusLabel, validLatitude, validLongitude } from '../drivers/types'
import { formatDateTime } from '../trips/types'
import StaleDataAlert from '../ui/StaleDataAlert'
import BusyTripPanel from './driver/BusyTripPanel'
import OfferPanel from './driver/OfferPanel'

const HEARTBEAT_INTERVAL_MS = 10_000 // matching cần last_seen_at < 15 s (default)
const STATE_POLL_MS = 2_000 // spec §3

function apiErrorText(e: unknown): string {
  return e instanceof ApiError ? `${e.code}: ${e.message}` : 'Lỗi không xác định.'
}

export default function DriverHome(): ReactElement {
  const navigate = useNavigate()
  // R03 fix: chỉ tin dữ liệu /me từ server; không fallback sang session cache cũ.
  const { user, loading: meLoading, error: meError, retry } = useMe()

  useEffect(() => {
    if (!meLoading && !user && meError === 'AUTH') navigate('/login', { replace: true })
  }, [meLoading, user, meError, navigate])

  const { data: driver, error: stateError, loading: stateLoading, refresh } = usePolling(
    (signal) => driversApi.getMe(signal),
    { intervalMs: STATE_POLL_MS, stopOnErrorCodes: ['NOT_FOUND'] }
  )
  useUnauthorizedRedirect(stateError)

  const status = driver?.status ?? null
  const savedLat = driver?.latitude ?? null
  const savedLng = driver?.longitude ?? null

  const [actionError, setActionError] = useState<string | null>(null)
  const [toggling, setToggling] = useState(false)
  const [creatingProfile, setCreatingProfile] = useState(false)
  const [heartbeatFailed, setHeartbeatFailed] = useState(false)

  // Ô nhập tọa độ là bản nháp: chỉ gửi khi bấm "Cập nhật vị trí". Giá trị đầu lấy từ server.
  const [draftLat, setDraftLat] = useState<number | null>(null)
  const [draftLng, setDraftLng] = useState<number | null>(null)
  const draftLoaded = useRef(false)
  useEffect(() => {
    if (draftLoaded.current || savedLat === null || savedLng === null) return
    draftLoaded.current = true
    setDraftLat(savedLat)
    setDraftLng(savedLng)
  }, [savedLat, savedLng])

  /** Heartbeat khi AVAILABLE: gửi lại tọa độ ĐÃ LƯU trên server (không bao giờ gửi bản nháp đang gõ). */
  const hasSavedLocation = savedLat !== null && savedLng !== null
  const savedLocation = useRef({ lat: savedLat, lng: savedLng })
  savedLocation.current = { lat: savedLat, lng: savedLng }
  useEffect(() => {
    if (status !== 'AVAILABLE' || !hasSavedLocation) {
      setHeartbeatFailed(false)
      return
    }
    let cancelled = false
    async function beat(): Promise<void> {
      const { lat, lng } = savedLocation.current
      if (lat === null || lng === null) return
      try {
        await driversApi.setLocation(lat, lng)
        if (!cancelled) setHeartbeatFailed(false)
      } catch {
        if (!cancelled) setHeartbeatFailed(true) // báo trên UI, chu kỳ sau thử lại
      }
    }
    void beat() // gửi ngay: vừa về AVAILABLE thì last_seen_at có thể đã cũ hơn ngưỡng matching
    const id = setInterval(() => void beat(), HEARTBEAT_INTERVAL_MS)
    return () => {
      cancelled = true
      clearInterval(id)
    }
  }, [status, hasSavedLocation])

  function logout(): void {
    bumpAuthGeneration()
    clearSession()
    navigate('/login')
  }

  async function handleCreateProfile(values: { displayName: string; plateNumber: string; vehicleType: VehicleType }): Promise<void> {
    setCreatingProfile(true)
    setActionError(null)
    try {
      await driversApi.createProfile(values.displayName, values.plateNumber, values.vehicleType)
      message.success('Đã tạo hồ sơ tài xế.')
      refresh()
    } catch (e) {
      setActionError(apiErrorText(e))
    } finally {
      setCreatingProfile(false)
    }
  }

  async function handleSetAvailability(next: 'AVAILABLE' | 'OFFLINE'): Promise<void> {
    setToggling(true)
    setActionError(null)
    try {
      await driversApi.setAvailability(next)
    } catch (e) {
      setActionError(apiErrorText(e))
    } finally {
      setToggling(false)
      refresh()
    }
  }

  const draftValid = validLatitude(draftLat) && validLongitude(draftLng)

  async function handleSetLocation(): Promise<void> {
    if (!draftValid) return
    setActionError(null)
    try {
      await driversApi.setLocation(draftLat as number, draftLng as number)
      message.success('Đã cập nhật vị trí.')
      refresh()
    } catch (e) {
      setActionError(apiErrorText(e))
    }
  }

  const busy = status === 'RESERVED' || status === 'BUSY'
  const noProfile = !driver && stateError?.code === 'NOT_FOUND'

  return (
    <Card style={{ maxWidth: 1080, margin: '24px auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <Space>
          <Typography.Title level={3} style={{ margin: 0 }}>
            Tài xế
          </Typography.Title>
          <Tag color="green">DRIVER</Tag>
        </Space>
        {user && (
          <Typography.Text type="secondary">
            {user.fullName} ({user.email})
          </Typography.Text>
        )}
      </div>

      {meLoading ? (
        <Spin />
      ) : !user && meError === 'NETWORK' ? (
        <Alert type="warning" message="Không thể xác thực phiên." description={<Button onClick={retry}>Thử lại</Button>} />
      ) : noProfile ? (
        <Card size="small" title="Tạo hồ sơ tài xế">
          {actionError && <Alert style={{ marginBottom: 12 }} type="error" message={actionError} />}
          <Form layout="vertical" onFinish={handleCreateProfile}>
            <Form.Item label="Tên hiển thị" name="displayName" rules={[{ required: true, min: 2, max: 60 }]}>
              <Input placeholder="Driver A" />
            </Form.Item>
            <Form.Item label="Biển số xe" name="plateNumber" rules={[{ required: true, min: 3, max: 20 }]}>
              <Input placeholder="51A-123.45" />
            </Form.Item>
            <Form.Item label="Loại xe" name="vehicleType" rules={[{ required: true }]}>
              <Select
                options={[
                  { value: 'MOTORBIKE', label: 'Xe máy' },
                  { value: 'CAR', label: 'Ô tô' }
                ]}
              />
            </Form.Item>
            <Button htmlType="submit" type="primary" loading={creatingProfile}>
              Tạo hồ sơ
            </Button>
          </Form>
        </Card>
      ) : !driver ? (
        stateLoading ? (
          <Spin tip="Đang đọc trạng thái từ server..." />
        ) : (
          <Alert
            type="error"
            message={stateError ? `${stateError.code}: ${stateError.message}` : 'Không đọc được trạng thái tài xế.'}
            description={<Button onClick={refresh}>Thử lại</Button>}
          />
        )
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {actionError && <Alert type="error" message={actionError} />}
          <StaleDataAlert error={stateError} />
          {heartbeatFailed && (
            <Alert type="warning" message="Không gửi được heartbeat — bạn có thể không nhận được chuyến cho tới khi kết nối lại." />
          )}

          {/* Trạng thái + online/offline + vị trí */}
          <Card size="small" title="Trạng thái & vị trí">
            <Space wrap style={{ marginBottom: 12 }}>
              <Tag color={driver.status === 'AVAILABLE' ? 'green' : driver.status === 'OFFLINE' ? 'default' : 'orange'}>
                {driverStatusLabel(driver.status)}
              </Tag>
              {driver.lastSeenAt && (
                <Typography.Text type="secondary">Heartbeat cuối: {formatDateTime(driver.lastSeenAt)}</Typography.Text>
              )}
            </Space>
            <Descriptions column={1} size="small" style={{ marginBottom: 12 }}>
              <Descriptions.Item label="Tên hiển thị">{driver.displayName}</Descriptions.Item>
              <Descriptions.Item label="Biển số">{driver.vehicle.plateNumber}</Descriptions.Item>
              <Descriptions.Item label="Loại xe">
                {driver.vehicle.vehicleType === 'MOTORBIKE' ? 'Xe máy' : 'Ô tô'}
              </Descriptions.Item>
              <Descriptions.Item label="Vị trí đã lưu">
                {hasSavedLocation ? `${savedLat}, ${savedLng}` : 'Chưa có'}
              </Descriptions.Item>
            </Descriptions>
            {!hasSavedLocation && (
              <Alert
                style={{ marginBottom: 12 }}
                type="warning"
                message="Chưa có vị trí — cập nhật vị trí để hệ thống có thể đề nghị chuyến cho bạn."
              />
            )}
            <Space.Compact block style={{ marginBottom: 12 }}>
              <InputNumber addonBefore="Lat" placeholder="21.0345" step={0.0001} value={draftLat} onChange={(v) => setDraftLat(v ?? null)} style={{ width: '50%' }} />
              <InputNumber addonBefore="Lng" placeholder="105.8409" step={0.0001} value={draftLng} onChange={(v) => setDraftLng(v ?? null)} style={{ width: '50%' }} />
            </Space.Compact>
            <Space wrap>
              <Button disabled={!draftValid} onClick={handleSetLocation}>
                Cập nhật vị trí
              </Button>
              <Button type="primary" loading={toggling} disabled={busy || status === 'AVAILABLE'} onClick={() => handleSetAvailability('AVAILABLE')}>
                Đang hoạt động
              </Button>
              <Button danger loading={toggling} disabled={busy || status === 'OFFLINE'} onClick={() => handleSetAvailability('OFFLINE')}>
                Nghỉ
              </Button>
            </Space>
            {busy && (
              <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
                Không đổi trạng thái khi đang có offer/chuyến — server giữ assignment cho đến khi chuyến kết thúc.
              </Typography.Text>
            )}
          </Card>

          {status === 'RESERVED' && <OfferPanel onChanged={refresh} />}
          {status === 'AVAILABLE' && (
            <Alert type="info" message="Chưa có offer — hệ thống sẽ tự động đề nghị khi có chuyến phù hợp với vị trí của bạn." />
          )}

          {/* key: mỗi chuyến một instance — polling của chuyến trước không dính sang chuyến sau. */}
          {status === 'BUSY' && driver.tripId && <BusyTripPanel key={driver.tripId} tripId={driver.tripId} />}
        </div>
      )}

      <div style={{ marginTop: 16, textAlign: 'right' }}>
        <Button onClick={logout}>Đăng xuất</Button>
      </div>
    </Card>
  )
}
