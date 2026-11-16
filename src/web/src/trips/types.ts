/**
 * T27 — domain types + pure helpers cho UI passenger/driver.
 * Nguồn sự thật: docs/api-contracts.md §3.3/§3.4, docs/state-machines.md §1/§5.
 */

export interface Coord {
  latitude: number
  longitude: number
}

/** Trip states theo contract §3.3 + state-machines.md §1 (CANCELLING là transient). */
export type TripState =
  | 'SEARCHING'
  | 'DRIVER_OFFERED'
  | 'CANCELLING'
  | 'ACCEPTED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'NO_DRIVER'

/** Payment status theo state-machines.md §5. */
export type PaymentStatus = 'NOT_REQUESTED' | 'PENDING' | 'SUCCEEDED' | 'FAILED'

export interface Trip {
  tripId: string
  state: TripState
  pickup: Coord
  destination: Coord
  distanceM: number
  fareVnd: number
  paymentStatus: PaymentStatus
  /** N27 — MVP chỉ hiển thị ID driver, không tên/số xe (trip-service không đọc Driver DB). */
  driverId: string | null
  matchingAttempt?: number
  createdAt: string
  updatedAt?: string
}

/** Envelope phân trang contract §1.3. */
export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface QuoteResult {
  distanceM: number
  fareVnd: number
}

export interface CreateTripResult {
  tripId: string
  state: TripState
  matchingAttempt: number
  createdAt: string
}

export interface CancelResult {
  tripId: string
  state: TripState
}

/** Notification types theo contract §3.4. */
export type NotificationType =
  | 'TRIP_OFFERED'
  | 'OFFER_RECEIVED'
  | 'TRIP_ACCEPTED'
  | 'TRIP_CANCELLED'
  | 'NO_DRIVER'
  | 'TRIP_COMPLETED'
  | 'PAYMENT_SUCCEEDED'
  | 'PAYMENT_FAILED'

export interface Notification {
  notificationId: string
  tripId: string
  type: NotificationType
  message: string
  /** MVP không có endpoint mark-as-read (chốt §3.4) — null = chưa đọc. */
  readAt: string | null
  createdAt: string
}

/** Một dòng lịch sử chuyến (GET /api/trips/{id}/events, contract §3.3). */
export interface TripEvent {
  type: string
  version: number
  actorId: string | null
  summary: string
  createdAt: string
}

/** Terminal states của trip; COMPLETED vẫn có thể đang chờ payment — xem isTripSettled. */
export const TERMINAL_TRIP_STATES: readonly TripState[] = ['COMPLETED', 'CANCELLED', 'NO_DRIVER']

export function isTerminalTrip(state: TripState): boolean {
  return TERMINAL_TRIP_STATES.includes(state)
}

/** Không còn gì thay đổi: trip đã terminal và thanh toán (nếu có) đã có kết quả — polling dừng tại đây. */
export function isTripSettled(trip: Pick<Trip, 'state' | 'paymentStatus'>): boolean {
  return isTerminalTrip(trip.state) && trip.paymentStatus !== 'PENDING'
}

/**
 * Passenger được cancel ở các state này (chốt T20, contract §3.3):
 * SEARCHING/DRIVER_OFFERED → hủy; CANCELLING → gọi lại để tiếp tục reconcile.
 */
export const CANCELABLE_TRIP_STATES: readonly TripState[] = ['SEARCHING', 'DRIVER_OFFERED', 'CANCELLING']

export function canCancelTrip(state: TripState): boolean {
  return CANCELABLE_TRIP_STATES.includes(state)
}

/** Driver actions (T28, contract §3.3 start/complete). */
export function canStartTrip(state: TripState): boolean {
  return state === 'ACCEPTED'
}

export function canCompleteTrip(state: TripState): boolean {
  return state === 'IN_PROGRESS'
}

const TRIP_STATE_LABELS: Record<TripState, string> = {
  SEARCHING: 'Đang tìm tài xế',
  DRIVER_OFFERED: 'Đang chờ tài xế xác nhận',
  CANCELLING: 'Đang hủy chuyến',
  ACCEPTED: 'Tài xế đã nhận chuyến',
  IN_PROGRESS: 'Đang di chuyển',
  COMPLETED: 'Hoàn thành',
  CANCELLED: 'Đã hủy',
  NO_DRIVER: 'Không có tài xế'
}

export function tripStateLabel(state: TripState): string {
  return TRIP_STATE_LABELS[state]
}

/** Màu Tag antd theo state. */
const TRIP_STATE_COLORS: Record<TripState, string> = {
  SEARCHING: 'orange',
  DRIVER_OFFERED: 'blue',
  CANCELLING: 'gold',
  ACCEPTED: 'green',
  IN_PROGRESS: 'cyan',
  COMPLETED: 'green',
  CANCELLED: 'default',
  NO_DRIVER: 'red'
}

export function tripStateColor(state: TripState): string {
  return TRIP_STATE_COLORS[state]
}

const PAYMENT_STATUS_LABELS: Record<PaymentStatus, string> = {
  NOT_REQUESTED: 'Chưa thanh toán',
  PENDING: 'Đang xử lý thanh toán',
  SUCCEEDED: 'Thanh toán thành công',
  FAILED: 'Thanh toán thất bại'
}

export function paymentStatusLabel(status: PaymentStatus): string {
  return PAYMENT_STATUS_LABELS[status]
}

const NOTIFICATION_TYPE_COLORS: Record<NotificationType, string> = {
  TRIP_OFFERED: 'blue',
  OFFER_RECEIVED: 'geekblue',
  TRIP_ACCEPTED: 'green',
  TRIP_CANCELLED: 'red',
  NO_DRIVER: 'volcano',
  TRIP_COMPLETED: 'cyan',
  PAYMENT_SUCCEEDED: 'green',
  PAYMENT_FAILED: 'red'
}

export function notificationTypeColor(type: NotificationType): string {
  return NOTIFICATION_TYPE_COLORS[type]
}

/** Định dạng VND (locale vi-VN, không số thập phân). */
export function formatVnd(value: number): string {
  return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 }).format(
    value
  )
}

/** Định dạng ISO-8601 → chuỗi đọc được (giữ nguyên nếu parse lỗi). */
export function formatDateTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('vi-VN')
}
