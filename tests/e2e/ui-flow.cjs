// E2E UI thật (headless Chrome) qua Nginx :8080: luồng tài xế + hành khách, gồm các kịch bản lỗi của rà soát T27/T28/T29.
// Chạy từ tests/e2e (CI: .github/workflows/ci.yml):
//   npm ci
//   SEED_PASSWORD=<từ .env> node ui-flow.cjs <đường dẫn Chrome/Chromium> <thư mục ảnh chụp>
// Không có Chrome: npx @puppeteer/browsers install chrome-headless-shell@stable --path ./browsers
// Yêu cầu: stack Docker Compose đang chạy, đã seed và không có trip dở (scripts/reset-demo.sh --confirm).
const puppeteer = require('puppeteer-core')

const [chromePath, shotDir] = process.argv.slice(2)
const BASE = process.env.BASE_URL || 'http://localhost:8080'
const PW = process.env.SEED_PASSWORD
const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`)
}

async function api(method, path, token, body, extra = {}) {
  const r = await fetch(BASE + path, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...extra
    },
    body: body ? JSON.stringify(body) : undefined
  })
  const text = await r.text()
  return { status: r.status, body: text ? JSON.parse(text) : null }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
async function apiLogin(email) {
  await sleep(300)
  const r = await api('POST', '/api/auth/login', null, { email, password: PW })
  if (r.status !== 200) throw new Error(`login ${email}: ${r.status}`)
  return r.body.accessToken
}

async function waitText(page, text, timeout = 30000) {
  await page.waitForFunction((t) => document.body.innerText.includes(t), { timeout }, text)
}
async function hasText(page, text) {
  return page.evaluate((t) => document.body.innerText.includes(t), text)
}
async function clickButton(page, label) {
  const done = await page.evaluate((l) => {
    const b = [...document.querySelectorAll('button')].find((x) => x.innerText.trim() === l && !x.disabled)
    if (b) b.click()
    return !!b
  }, label)
  if (!done) throw new Error(`no enabled button "${label}"`)
}
async function uiLogin(page, email) {
  await page.goto(BASE + '/login', { waitUntil: 'networkidle2' })
  await sleep(300)
  await page.type('#email', email)
  await page.type('#password', PW)
  await page.evaluate(() => document.querySelector('button[type=submit]').click())
  await page.waitForFunction(() => !location.pathname.startsWith('/login'), { timeout: 15000 })
}
async function newPage(browser) {
  const ctx = await browser.createBrowserContext()
  const page = await ctx.newPage()
  await page.setViewport({ width: 1200, height: 1000 })
  page.on('pageerror', (e) => check('không có lỗi JS trên trang', false, e.message))
  return page
}

async function main() {
  const browser = await puppeteer.launch({ executablePath: chromePath, headless: chromePath.includes('headless-shell') ? 'shell' : true, args: ['--no-sandbox'] })
  try {
    const d1 = await apiLogin('driver-1@demo.local')
    const d2 = await apiLogin('driver-2@demo.local')
    const d3 = await apiLogin('driver-3@demo.local')
    const p1 = await apiLogin('passenger-1@demo.local')
    for (const t of [d2, d3]) await api('PUT', '/api/drivers/me/availability', t, { status: 'OFFLINE' })
    await api('PUT', '/api/drivers/me/availability', d1, { status: 'OFFLINE' })
    await api('PUT', '/api/drivers/me/location', d1, { latitude: 21.0348, longitude: 105.8415 })

    // --- Lỗi 3: mở trang không được đổi trạng thái tài xế ---
    const driverPage = await newPage(browser)
    await uiLogin(driverPage, 'driver-1@demo.local')
    await waitText(driverPage, 'Nghỉ (offline)')
    await sleep(4000)
    let me = await api('GET', '/api/drivers/me', d1)
    check('tài xế OFFLINE mở trang (phiên mới): vẫn OFFLINE', me.body.status === 'OFFLINE', `status=${me.body.status}`)
    await driverPage.reload({ waitUntil: 'networkidle2' })
    await waitText(driverPage, 'Nghỉ (offline)')
    me = await api('GET', '/api/drivers/me', d1)
    check('tải lại trang: vẫn OFFLINE', me.body.status === 'OFFLINE')
    check('hiện hồ sơ thật từ server', await hasText(driverPage, me.body.vehicle.plateNumber))

    // --- Vị trí không bị ghi đè khi tải lại ---
    const seen0 = me.body
    await clickButton(driverPage, 'Đang hoạt động')
    await waitText(driverPage, 'Chưa có offer')
    await driverPage.reload({ waitUntil: 'networkidle2' })
    await waitText(driverPage, 'Chưa có offer')
    await sleep(1500)
    me = await api('GET', '/api/drivers/me', d1)
    check('tải lại khi AVAILABLE: vị trí server không bị ghi đè', me.body.latitude === seen0.latitude && me.body.longitude === seen0.longitude, `${me.body.latitude},${me.body.longitude}`)
    check('tải lại khi AVAILABLE: heartbeat chạy lại', new Date(me.body.lastSeenAt) > new Date(seen0.lastSeenAt))

    // --- Passenger đặt chuyến qua form ---
    const passPage = await newPage(browser)
    await uiLogin(passPage, 'passenger-1@demo.local')
    await waitText(passPage, 'Đặt chuyến mới')
    const inputs = await passPage.$$('input.ant-input-number-input')
    const coords = ['21.0345', '105.8409', '21.026', '105.857']
    for (let i = 0; i < 4; i++) await inputs[i].type(coords[i])
    await clickButton(passPage, 'Tính giá')
    await waitText(passPage, 'Khoảng cách')
    await clickButton(passPage, 'Đặt chuyến')
    await waitText(passPage, 'đang tìm tài xế')
    await passPage.screenshot({ path: `${shotDir}/passenger.png` })
    check('passenger đặt chuyến qua UI', true)

    // --- Lỗi 2: tải lại khi có offer, từ chối, rồi nhận chuyến mới ---
    await waitText(driverPage, 'Nhận chuyến', 40000)
    await driverPage.reload({ waitUntil: 'networkidle2' })
    await waitText(driverPage, 'Nhận chuyến')
    check('tải lại khi có offer: offer hiện lại từ server', true)
    await driverPage.screenshot({ path: `${shotDir}/driver-offer.png` })
    await clickButton(driverPage, 'Từ chối')
    await waitText(driverPage, 'Chưa có offer', 20000)
    check('từ chối xong: không còn panel chuyến cũ', !(await hasText(driverPage, 'Không tìm thấy chuyến này')))

    const list = await api('GET', '/api/trips?page=0&size=5', p1)
    const t1 = list.body.content[0].tripId
    await api('POST', `/api/trips/${t1}/cancel`, p1)
    for (let i = 0; i < 20; i++) {
      const s = await api('GET', `/api/trips/${t1}`, p1)
      if (s.body.state === 'CANCELLED') break
      await sleep(1000)
    }

    const created = await api('POST', '/api/trips', p1, {
      pickup: { latitude: 21.0345, longitude: 105.8409 },
      destination: { latitude: 21.026, longitude: 105.857 }
    }, { 'Idempotency-Key': crypto.randomUUID() })
    const t2 = created.body.tripId
    await waitText(driverPage, 'Nhận chuyến', 40000)
    await clickButton(driverPage, 'Nhận chuyến')
    await waitText(driverPage, 'Bắt đầu chuyến', 20000)
    check('nhận chuyến mới sau khi từ chối: hiện đúng nút Bắt đầu', true)
    await driverPage.screenshot({ path: `${shotDir}/driver-busy.png` })

    // --- Lỗi 1: trang chi tiết tự cập nhật thanh toán sau COMPLETED ---
    await passPage.goto(`${BASE}/passenger/trips/${t2}`, { waitUntil: 'networkidle2' })
    await waitText(passPage, 'Tài xế đã nhận chuyến')
    await clickButton(driverPage, 'Bắt đầu chuyến')
    await waitText(driverPage, 'Hoàn thành chuyến', 15000)
    await clickButton(driverPage, 'Hoàn thành chuyến')
    await waitText(passPage, 'Hoàn thành', 15000)
    await waitText(passPage, 'Thanh toán thành công', 30000)
    check('trang chi tiết tự chuyển sang "Thanh toán thành công" không cần tải lại', true)
    await passPage.screenshot({ path: `${shotDir}/passenger-detail.png` })

    await waitText(driverPage, 'Chưa có offer', 30000)
    me = await api('GET', '/api/drivers/me', d1)
    check('hết chuyến: tài xế về AVAILABLE theo server', me.body.status === 'AVAILABLE')

    // --- Màn hình demo (T29) ---
    await passPage.goto(`${BASE}/demo`, { waitUntil: 'networkidle2' })
    await waitText(passPage, 'Demo overview')
    await waitText(passPage, t2)
    check('/demo render và có chuyến của phiên', true)
    await passPage.screenshot({ path: `${shotDir}/demo.png` })

    // --- Token hết hạn giữa chừng → về trang đăng nhập ---
    await driverPage.evaluate(() => {
      const raw = sessionStorage.getItem('ridehailing.session')
      const s = JSON.parse(raw)
      s.token = 'expired.token.value'
      sessionStorage.setItem('ridehailing.session', JSON.stringify(s))
    })
    await driverPage.waitForFunction(() => location.pathname === '/login', { timeout: 10000 })
    check('token bị từ chối khi đang polling: về trang đăng nhập', true)
  } finally {
    await browser.close()
  }
  const failed = results.filter((r) => !r.ok)
  console.log(`\n${results.length - failed.length}/${results.length} PASS`)
  process.exit(failed.length ? 1 : 0)
}

main().catch((e) => {
  console.error('E2E ERROR:', e.message)
  process.exit(1)
})
