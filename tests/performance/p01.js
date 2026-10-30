// T33 — P01 (spec §13): N hành khách đồng thời, mỗi người lặp ROUNDS chuyến hoàn chỉnh qua Nginx.
// Chạy qua scripts/load-p01.sh. Tách rõ (spec §13):
//   - create_202_ms: thời gian POST /api/trips nhận 202 — KHÔNG phải toàn bộ thời gian đặt xe;
//   - create_to_offer_ms: từ lúc gửi POST tới khi trip DRIVER_OFFERED (đã giữ được tài xế);
//   - create_to_accepted_ms: tới khi ACCEPTED (assignment hiệu lực; tài xế giả lập nhận ngay).
// Phân loại kết quả: NO_DRIVER và 409 nghiệp vụ KHÔNG phải lỗi kỹ thuật; lỗi kỹ thuật = 5xx, mất kết nối,
// timeout, 4xx ngoài dự kiến, trip kẹt quá hạn. Poll mỗi POLL_MS nên độ trễ assignment có sai số tới POLL_MS.
import http from 'k6/http'
import { sleep } from 'k6'
import exec from 'k6/execution'
import { Counter, Rate, Trend } from 'k6/metrics'

const BASE = __ENV.BASE_URL || 'http://nginx:8080'
const PASSWORD = __ENV.SEED_PASSWORD
const VUS = Number(__ENV.VUS || 10)
const ROUNDS = Number(__ENV.ROUNDS || 5)
const DRIVERS = Number(__ENV.DRIVERS || VUS)
const DATASET = __ENV.DATASET || 'enough'
// Thời gian tài xế bị giữ giữa start và complete (mô phỏng chuyến đi). 0 = đo năng lực hệ thống;
// > 0 ở dataset thiếu tài xế: không giữ thì tài xế rảnh gần như tức thì và không có contention thật.
const HOLD_S = Number(__ENV.HOLD_S || 0)
const NAME = __ENV.RUN_NAME || `p01-${DATASET}-${VUS}`
const POLL_MS = 500
const HEARTBEAT_S = 5
const TRIP_DEADLINE_MS = 180_000 // lớn hơn deadline matching 120 s: quá hạn này mới là kẹt thật
const PAYMENT_DEADLINE_MS = 60_000

const PICKUP = { latitude: 21.0345, longitude: 105.8409 }
const DESTINATION = { latitude: 21.026, longitude: 105.857 }

const create202 = new Trend('create_202_ms', true)
const createToOffer = new Trend('create_to_offer_ms', true)
const createToAccepted = new Trend('create_to_accepted_ms', true)
const createToNoDriver = new Trend('create_to_no_driver_ms', true)
const acceptMs = new Trend('accept_ms', true)
const completeToPayment = new Trend('complete_to_payment_ms', true)
const tripsCreated = new Counter('trips_created')
const tripsCompleted = new Counter('trips_completed')
const tripsNoDriver = new Counter('trips_no_driver')
const offersLost = new Counter('offers_lost')
const businessRejections = new Counter('business_rejections')
const technicalErrors = new Counter('technical_errors')
const technicalErrorRate = new Rate('technical_error_rate')
const heartbeatFailures = new Counter('heartbeat_failures')
// Mốc kết thúc mỗi chuyến tính từ lúc setup xong: max = độ dài cửa sổ đo (không gồm setup, để tính throughput).
const tripEndOffset = new Trend('trip_end_offset_ms', true)

export const options = {
  setupTimeout: '10m',
  summaryTrendStats: ['min', 'med', 'p(95)', 'max', 'avg'],
  scenarios: {
    passengers: { executor: 'per-vu-iterations', vus: VUS, iterations: ROUNDS, maxDuration: '30m', exec: 'passengerFlow' },
    heartbeat: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '31m', exec: 'heartbeat' }
  }
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => Math.floor(Math.random() * 16).toString(16))
}

function headers(token, extra = {}) {
  return { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...extra }
}

function setupCall(method, path, token, body) {
  return http.request(method, `${BASE}${path}`, body ? JSON.stringify(body) : null, { headers: headers(token), responseCallback: http.expectedStatuses({ min: 200, max: 499 }) })
}

/** Lấy token; tài khoản chưa có thì đăng ký. Nhịp 250 ms: Nginx giới hạn /api/auth 5–10 r/s theo IP. */
function loginOrRegister(email, fullName, role) {
  let res = setupCall('POST', '/api/auth/login', null, { email, password: PASSWORD })
  sleep(0.25)
  if (res.status === 401) {
    const reg = setupCall('POST', '/api/auth/register', null, { email, password: PASSWORD, fullName, role })
    sleep(0.25)
    if (reg.status !== 201) exec.test.abort(`register ${email}: HTTP ${reg.status} ${reg.body}`)
    res = setupCall('POST', '/api/auth/login', null, { email, password: PASSWORD })
    sleep(0.25)
  }
  if (res.status !== 200) exec.test.abort(`login ${email}: HTTP ${res.status} ${res.body}`)
  return res.json('accessToken')
}

function driverLocation(i) {
  return { latitude: PICKUP.latitude + 0.0002 * (i % 20), longitude: PICKUP.longitude + 0.0002 * (i % 7) }
}

/** Chỉ đưa DRIVERS tài xế lên AVAILABLE; tài xế của lượt trước không có heartbeat nên tự rớt khỏi matching sau 15 s. */
export function setup() {
  if (!PASSWORD) exec.test.abort('thiếu SEED_PASSWORD')
  // Hành khách trước: hủy chuyến dở của lượt trước để giải phóng tài xế, rồi mới đưa tài xế lên AVAILABLE.
  // VUS+1: id VU của kịch bản passengers không liên tục vì VU heartbeat chen giữa.
  const passengers = []
  for (let i = 0; i <= VUS; i++) {
    const token = loginOrRegister(`load-passenger-${i}@demo.local`, `Load Passenger ${i}`, 'PASSENGER')
    const trips = setupCall('GET', '/api/trips?page=0&size=5', token)
    for (const t of trips.json('content') || []) {
      if (['SEARCHING', 'DRIVER_OFFERED', 'CANCELLING'].includes(t.state)) setupCall('POST', `/api/trips/${t.tripId}/cancel`, token)
      else if (['ACCEPTED', 'IN_PROGRESS'].includes(t.state)) exec.test.abort(`load-passenger-${i} còn chuyến ${t.tripId} ở ${t.state}`)
    }
    passengers.push({ token, index: i })
  }
  sleep(2)
  const drivers = []
  for (let i = 0; i < DRIVERS; i++) {
    const token = loginOrRegister(`load-driver-${i}@demo.local`, `Load Driver ${i}`, 'DRIVER')
    const profile = setupCall('POST', '/api/drivers/me/profile', token, {
      displayName: `Load Driver ${i}`,
      vehicle: { plateNumber: `LOAD-${i}`, vehicleType: 'MOTORBIKE' }
    })
    if (profile.status !== 200 && profile.status !== 201) exec.test.abort(`profile driver ${i}: HTTP ${profile.status} ${profile.body}`)
    const avail = setupCall('PUT', '/api/drivers/me/availability', token, { status: 'AVAILABLE' })
    if (avail.status !== 200) exec.test.abort(`availability driver ${i}: HTTP ${avail.status} ${avail.body} (chuyến kẹt từ lượt trước?)`)
    const loc = setupCall('PUT', '/api/drivers/me/location', token, driverLocation(i))
    if (loc.status !== 200) exec.test.abort(`location driver ${i}: HTTP ${loc.status} ${loc.body}`)
    drivers.push({ token, driverId: profile.json('driverId'), index: i })
  }
  return { drivers, passengers, startedAt: Date.now() }
}

/** Giữ last_seen_at < 15 s cho tài xế của lượt đo tới khi mọi chuyến xong. */
export function heartbeat(data) {
  const total = VUS * ROUNDS
  while (exec.instance.iterationsCompleted + exec.instance.iterationsInterrupted < total) {
    const reqs = data.drivers.map((d) => ({
      method: 'PUT',
      url: `${BASE}/api/drivers/me/location`,
      body: JSON.stringify(driverLocation(d.index)),
      params: { headers: headers(d.token), tags: { name: 'heartbeat' }, responseCallback: http.expectedStatuses(200) }
    }))
    for (const r of http.batch(reqs)) {
      if (r.status !== 200) heartbeatFailures.add(1)
    }
    sleep(HEARTBEAT_S)
  }
}

/** Gọi API đo được: 2xx đúng kỳ vọng; 409 là từ chối nghiệp vụ; còn lại là lỗi kỹ thuật. */
function call(op, method, path, token, body, record, extra = {}) {
  const res = http.request(method, `${BASE}${path}`, body ? JSON.stringify(body) : null, {
    headers: headers(token, extra),
    tags: { name: op },
    timeout: '15s',
    responseCallback: http.expectedStatuses({ min: 200, max: 299 }, 409)
  })
  const business = res.status === 409
  const technical = !business && (res.status < 200 || res.status >= 300)
  technicalErrorRate.add(technical)
  if (business) businessRejections.add(1, { op })
  if (technical) {
    technicalErrors.add(1, { op })
    record.errors.push({ op, status: res.status, error: res.error || null })
  }
  return { res, ok: !business && !technical, business }
}

export function passengerFlow(data) {
  const passenger = data.passengers[exec.vu.idInTest - 1]
  const driverByProfile = {}
  for (const d of data.drivers) driverByProfile[d.driverId] = d
  const record = { vu: passenger.index, round: exec.vu.iterationInScenario, errors: [], offersLost: 0 }
  const finish = () => {
    record.endOffsetMs = Date.now() - data.startedAt
    tripEndOffset.add(record.endOffsetMs)
    console.log(JSON.stringify(record))
  }

  const t0 = Date.now()
  const created = call('create', 'POST', '/api/trips', passenger.token, { pickup: PICKUP, destination: DESTINATION }, record, { 'Idempotency-Key': uuid() })
  record.createStatus = created.res.status
  if (!created.ok) {
    record.outcome = created.business ? 'CREATE_REJECTED' : 'CREATE_FAILED'
    finish()
    sleep(1)
    return
  }
  create202.add(created.res.timings.duration)
  record.create202Ms = Math.round(created.res.timings.duration)
  tripsCreated.add(1)
  const tripId = created.res.json('tripId')
  record.tripId = tripId

  let accepted = null
  let offerSeen = false
  const deadline = t0 + TRIP_DEADLINE_MS
  while (!accepted && Date.now() < deadline) {
    const got = call('get_trip', 'GET', `/api/trips/${tripId}`, passenger.token, null, record)
    if (!got.ok) {
      sleep(POLL_MS / 1000)
      continue
    }
    const trip = got.res.json()
    record.attempt = trip.matchingAttempt
    if (trip.state === 'NO_DRIVER') {
      createToNoDriver.add(Date.now() - t0)
      tripsNoDriver.add(1)
      record.outcome = 'NO_DRIVER'
      record.noDriverMs = Date.now() - t0
      finish()
      return
    }
    if (trip.state === 'CANCELLED') {
      record.errors.push({ op: 'unexpected_state', state: trip.state })
      technicalErrors.add(1, { op: 'unexpected_state' })
      record.outcome = 'UNEXPECTED_CANCELLED'
      finish()
      return
    }
    if (trip.state === 'DRIVER_OFFERED') {
      if (!offerSeen) {
        offerSeen = true
        record.offerMs = Date.now() - t0
        createToOffer.add(record.offerMs)
      }
      const driver = driverByProfile[trip.driverId]
      if (!driver) {
        record.errors.push({ op: 'unknown_driver', driverId: trip.driverId })
        technicalErrors.add(1, { op: 'unknown_driver' })
        sleep(POLL_MS / 1000)
        continue
      }
      const acceptCall = call('accept', 'POST', `/api/trips/${tripId}/accept`, driver.token, { attempt: trip.matchingAttempt }, record)
      acceptMs.add(acceptCall.res.timings.duration)
      if (acceptCall.ok && acceptCall.res.json('state') === 'ACCEPTED') {
        accepted = driver
        break
      }
      if (acceptCall.business) {
        offersLost.add(1)
        record.offersLost += 1
        offerSeen = false
      }
    }
    sleep(POLL_MS / 1000)
  }
  if (!accepted) {
    technicalErrors.add(1, { op: 'trip_stuck' })
    record.errors.push({ op: 'trip_stuck' })
    record.outcome = 'STUCK'
    finish()
    return
  }
  record.acceptedMs = Date.now() - t0
  createToAccepted.add(record.acceptedMs)

  const started = call('start', 'POST', `/api/trips/${tripId}/start`, accepted.token, null, record)
  if (started.ok && HOLD_S > 0) sleep(HOLD_S)
  const completed = started.ok && call('complete', 'POST', `/api/trips/${tripId}/complete`, accepted.token, null, record)
  if (!completed || !completed.ok) {
    record.outcome = 'LIFECYCLE_FAILED'
    finish()
    return
  }
  const tComplete = Date.now()
  let paymentStatus = null
  while (Date.now() < tComplete + PAYMENT_DEADLINE_MS) {
    const got = call('get_trip', 'GET', `/api/trips/${tripId}`, passenger.token, null, record)
    if (got.ok && ['SUCCEEDED', 'FAILED'].includes(got.res.json('paymentStatus'))) {
      paymentStatus = got.res.json('paymentStatus')
      break
    }
    sleep(POLL_MS / 1000)
  }
  record.paymentStatus = paymentStatus
  if (!paymentStatus) {
    technicalErrors.add(1, { op: 'payment_stuck' })
    record.errors.push({ op: 'payment_stuck' })
    record.outcome = 'PAYMENT_STUCK'
  } else {
    record.paymentMs = Date.now() - tComplete
    completeToPayment.add(record.paymentMs)
    tripsCompleted.add(1)
    record.outcome = 'COMPLETED'
  }
  record.totalMs = Date.now() - t0
  finish()
}

function trend(data, name) {
  const v = (data.metrics[name] || {}).values || {}
  const ms = (x) => (x === undefined ? '–' : Math.round(x).toString())
  return { med: v.med, p95: v['p(95)'], max: v.max, text: `${ms(v.med)} / ${ms(v['p(95)'])}` }
}

function count(data, name) {
  return ((data.metrics[name] || {}).values || {}).count || 0
}

export function handleSummary(data) {
  const created = count(data, 'trips_created')
  const noDriver = count(data, 'trips_no_driver')
  const completed = count(data, 'trips_completed')
  const technical = count(data, 'technical_errors')
  const seconds = (trend(data, 'trip_end_offset_ms').max || 0) / 1000 || 1
  const rate = ((data.metrics.technical_error_rate || {}).values || {}).rate || 0
  const row = [
    DATASET, VUS, DRIVERS, HOLD_S, created, completed, noDriver,
    `${created ? ((noDriver / created) * 100).toFixed(1) : '0.0'}%`,
    trend(data, 'create_202_ms').text,
    trend(data, 'create_to_offer_ms').text,
    trend(data, 'create_to_accepted_ms').text,
    trend(data, 'create_to_no_driver_ms').text,
    (created / seconds).toFixed(2),
    (completed / seconds).toFixed(2),
    technical,
    `${(rate * 100).toFixed(2)}%`,
    count(data, 'offers_lost'),
    count(data, 'business_rejections'),
    count(data, 'heartbeat_failures')
  ]
  return {
    stdout: `\n${NAME}: ${row.join(' | ')}\n`,
    [`/results/${NAME}.summary.json`]: JSON.stringify(data, null, 2),
    [`/results/${NAME}.row.md`]: `| ${row.join(' | ')} |\n`
  }
}
