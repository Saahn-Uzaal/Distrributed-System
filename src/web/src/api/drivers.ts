/**
 * T28 — client cho /api/drivers (contract §3.2).
 */
import { http } from './client'
import type { DriverProfile, DriverState, DriverStatus, Offer, VehicleType } from '../drivers/types'

export const driversApi = {
  /** GET /api/drivers/me — chỉ đọc, không đổi heartbeat/state; chưa có profile → 404 NOT_FOUND. */
  getMe(signal?: AbortSignal): Promise<DriverState> {
    return http('/api/drivers/me', { signal })
  },

  /**
   * POST /api/drivers/me/profile — idempotent theo userId:
   * lần đầu 201 (tạo), gọi lại 200 trả profile đã tạo, KHÔNG cập nhật body mới.
   */
  createProfile(displayName: string, plateNumber: string, vehicleType: VehicleType): Promise<DriverProfile> {
    return http('/api/drivers/me/profile', {
      method: 'POST',
      body: { displayName, vehicle: { plateNumber, vehicleType } }
    })
  },

  /** PUT /api/drivers/me/availability — cập nhật heartbeat; RESERVED/BUSY → 409 STATE_CONFLICT {state, tripId}. */
  setAvailability(status: 'AVAILABLE' | 'OFFLINE'): Promise<{ driverId: string; status: DriverStatus; lastSeenAt: string }> {
    return http('/api/drivers/me/availability', { method: 'PUT', body: { status } })
  },

  /** PUT /api/drivers/me/location — cập nhật tọa độ + heartbeat; cho phép ở mọi state. */
  setLocation(latitude: number, longitude: number): Promise<{ driverId: string; lastSeenAt: string }> {
    return http('/api/drivers/me/location', { method: 'PUT', body: { latitude, longitude } })
  },

  /** GET /api/drivers/me/offer — literal null khi không có offer (chốt T17). */
  getOffer(signal?: AbortSignal): Promise<Offer | null> {
    return http('/api/drivers/me/offer', { signal })
  }
}
