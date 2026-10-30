/**
 * T28 — domain types + pure helpers cho UI tài xế.
 * Nguồn sự thật: docs/api-contracts.md §3.2, docs/state-machines.md §4.
 */
import type { Coord } from '../trips/types'

export type DriverStatus = 'OFFLINE' | 'AVAILABLE' | 'RESERVED' | 'BUSY'
export type VehicleType = 'MOTORBIKE' | 'CAR'

export interface DriverProfile {
  driverId: string
  displayName: string
  vehicle: { plateNumber: string; vehicleType: VehicleType }
  status?: string
}

/** GET /api/drivers/me (contract §3.2) — state do server giữ; tripId có khi RESERVED/BUSY. */
export interface DriverState {
  driverId: string
  displayName: string
  vehicle: { plateNumber: string; vehicleType: VehicleType }
  status: DriverStatus
  latitude: number | null
  longitude: number | null
  lastSeenAt: string | null
  tripId: string | null
}

/** Offer hiện tại theo contract §3.2 (T17) — dữ liệu từ reservation local của driver-service. */
export interface Offer {
  tripId: string
  attempt: number
  pickup: Coord
  destination: Coord
  distanceM: number
  fareVnd: number
  /** ISO-8601 UTC — client đếm ngược theo giá trị này; server là nguồn quyết định khi accept. */
  expiresAt: string
}

const DRIVER_STATUS_LABELS: Record<DriverStatus, string> = {
  OFFLINE: 'Nghỉ (offline)',
  AVAILABLE: 'Đang hoạt động',
  RESERVED: 'Đang chờ phản hồi offer',
  BUSY: 'Đang phục vụ chuyến'
}

export function driverStatusLabel(status: DriverStatus): string {
  return DRIVER_STATUS_LABELS[status]
}

/** ms còn lại đến khi offer hết hạn (<=0 → đã hết hạn). Pure — test được. */
export function offerRemainingMs(expiresAtIso: string, nowMs: number): number {
  const t = Date.parse(expiresAtIso)
  if (Number.isNaN(t)) return 0
  return t - nowMs
}

export function validLatitude(v: number | null): boolean {
  return v !== null && Number.isFinite(v) && Math.abs(v) <= 90
}

export function validLongitude(v: number | null): boolean {
  return v !== null && Number.isFinite(v) && Math.abs(v) <= 180
}
