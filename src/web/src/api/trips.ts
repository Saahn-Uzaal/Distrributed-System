/**
 * T27 — client cho /api/trips + /api/notifications (contract §3.3/§3.4).
 * Mọi gọi qua same-origin (Nginx :8080 production, Vite proxy dev).
 */
import { http } from './client'
import type {
  CancelResult,
  Coord,
  CreateTripResult,
  Notification,
  Page,
  QuoteResult,
  Trip,
  TripState
} from '../trips/types'

export const tripsApi = {
  /** POST /api/trips/quote — không tạo trip, không side effect. */
  quote(pickup: Coord, destination: Coord, signal?: AbortSignal): Promise<QuoteResult> {
    return http('/api/trips/quote', { method: 'POST', body: { pickup, destination }, signal })
  },

  /**
   * POST /api/trips — bắt buộc header Idempotency-Key (UUID).
   * Cùng key + cùng payload → replay response gốc (202, không tạo trip mới) — contract §3.3.
   */
  createTrip(key: string, pickup: Coord, destination: Coord, signal?: AbortSignal): Promise<CreateTripResult> {
    return http('/api/trips', {
      method: 'POST',
      body: { pickup, destination },
      extraHeaders: { 'Idempotency-Key': key },
      signal
    })
  },

  listTrips(page = 0, size = 20, signal?: AbortSignal): Promise<Page<Trip>> {
    return http(`/api/trips?page=${page}&size=${size}`, { signal })
  },

  getTrip(tripId: string, signal?: AbortSignal): Promise<Trip> {
    return http(`/api/trips/${tripId}`, { signal })
  },

  /** POST /api/trips/{id}/cancel (chỉ owner passenger) — 200 CANCELLED hoặc 202 CANCELLING. */
  cancelTrip(tripId: string, signal?: AbortSignal): Promise<CancelResult> {
    return http(`/api/trips/${tripId}/cancel`, { method: 'POST', signal })
  },

  /** POST /api/trips/{id}/accept (chỉ offered driver) — body {attempt}. */
  acceptTrip(tripId: string, attempt: number, signal?: AbortSignal): Promise<{ tripId: string; state: TripState }> {
    return http(`/api/trips/${tripId}/accept`, { method: 'POST', body: { attempt }, signal })
  },

  /** POST /api/trips/{id}/reject (chỉ offered driver) — body {attempt}. */
  rejectTrip(tripId: string, attempt: number, signal?: AbortSignal): Promise<{ tripId: string; state: TripState }> {
    return http(`/api/trips/${tripId}/reject`, { method: 'POST', body: { attempt }, signal })
  },

  /** POST /api/trips/{id}/start (chỉ assigned driver, state ACCEPTED). */
  startTrip(tripId: string, signal?: AbortSignal): Promise<{ tripId: string; state: TripState }> {
    return http(`/api/trips/${tripId}/start`, { method: 'POST', signal })
  },

  /** POST /api/trips/{id}/complete (chỉ assigned driver, state IN_PROGRESS). */
  completeTrip(tripId: string, signal?: AbortSignal): Promise<{ tripId: string; state: TripState }> {
    return http(`/api/trips/${tripId}/complete`, { method: 'POST', signal })
  },

  /** GET /api/notifications — chỉ thông báo của caller (contract §3.4). */
  listNotifications(page = 0, size = 20, signal?: AbortSignal): Promise<Page<Notification>> {
    return http(`/api/notifications?page=${page}&size=${size}`, { signal })
  }
}
