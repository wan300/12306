const http = require('node:http')
const crypto = require('node:crypto')
const { pageScript } = require('./flight-page.cjs')
const { cookieDetails } = require('./flight-session.cjs')

const HOMES = { ctrip: 'https://flights.ctrip.com/online/channel/domestic', qunar: 'https://touch.qunar.com/h5/flight/' }
const CTRIP_LOGIN = 'https://passport.ctrip.com/user/login?backurl=https%3A%2F%2Fwww.ctrip.com%2F&responsemethod=GET'
const LOGIN_URLS = { ctrip: CTRIP_LOGIN, qunar: 'https://user.qunar.com/passport/interLogin.jsp?ret=https%3A%2F%2Fwww.qunar.com%2F' }
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))

class FlightBrowserError extends Error {
  constructor(code, message) { super(message); this.code = code }
}

function allowedUrl(provider, address) {
  try {
    const url = new URL(address)
    return url.protocol === 'https:' && (provider === 'ctrip'
      ? url.hostname === 'ctrip.com' || url.hostname.endsWith('.ctrip.com')
      : url.hostname === 'qunar.com' || url.hostname.endsWith('.qunar.com'))
  } catch { return false }
}

function searchUrl(query) {
  const { provider, origin, destination, departure_date } = query
  if (!HOMES[provider] || !/^[A-Z]{3}$/.test(origin) || !/^[A-Z]{3}$/.test(destination) || !/^\d{4}-\d{2}-\d{2}$/.test(departure_date)) {
    throw new FlightBrowserError('INVALID_QUERY', '行程参数无效')
  }
  if (provider === 'ctrip') {
    return `https://flights.ctrip.com/online/list/oneway-${origin.toLowerCase()}-${destination.toLowerCase()}?` +
      new URLSearchParams({ depdate: departure_date, cabin: 'y', adult: '1', child: '0', infant: '0' })
  }
  return 'https://touch.qunar.com/ncs/page/flightlist?' + new URLSearchParams({
    depCity: query.origin_name, arrCity: query.destination_name, goDate: departure_date,
    child: '0', baby: '0', cabinType: '0', from: 'touch_index_search',
  })
}

function bookingFingerprint(payload) {
  return JSON.stringify([payload.origin, payload.destination, payload.departure_date, payload.flight_number, [...payload.passengers].sort(), payload.offer_key || null])
}

function extractOrderId(value, depth = 0) {
  if (!value || typeof value !== 'object' || depth > 7) return null
  for (const [key, child] of Object.entries(value)) {
    if (/^(orderids?|orderno|order_id|order_number)$/i.test(key)) {
      const identifier = Array.isArray(child) ? (child.length === 1 ? (child[0]?.mainNo || child[0]) : null) : child
      if (typeof identifier === 'number' && !Number.isSafeInteger(identifier)) return null
      if (/^(?:\d{6,30}|[A-Z0-9-]{8,40})$/.test(String(identifier))) return String(identifier)
    }
  }
  for (const child of Object.values(value)) {
    const found = extractOrderId(child, depth + 1)
    if (found) return found
  }
  return null
}

function parseOrderResponse(body) {
  try { return JSON.parse(body) }
  catch {
    const jsonp = body.match(/^\s*[\w$.]+\(([\s\S]*)\);?\s*$/)
    if (!jsonp) return null
    try { return JSON.parse(jsonp[1]) } catch { return null }
  }
}

function confirmedOrderId(provider, response) {
  if (!response || typeof response !== 'object') return null
  if (provider === 'qunar') {
    if (response.bstatus?.code === 0 && response.data && !response.data.goOnMsg && !response.data.submitData?.goOnMsg) return extractOrderId(response.data)
    if (response.status === 200) return extractOrderId(response)
    return null
  }
  if (response.Error || response.error || response.success === false) return null
  return extractOrderId(response.Data)
}

class FlightBrowser {
  constructor({ BrowserWindow, sessionStore, onNotify }) {
    this.BrowserWindow = BrowserWindow
    this.sessionStore = sessionStore
    this.onNotify = onNotify
    this.windows = new Map()
    this.queues = new Map()
    this.cache = new Map()
    this.prepared = new Map()
    this.offerContexts = new Map()
    this.orderResults = new Map()
    this.submissionKeys = new Set()
    this.loginWindows = new Map()
  }

  window(provider) {
    if (!HOMES[provider]) throw new FlightBrowserError('INVALID_PROVIDER', '不支持的平台')
    let win = this.windows.get(provider)
    if (win && !win.isDestroyed()) return win
    win = new this.BrowserWindow({
      width: 1280, height: 900, title: provider === 'ctrip' ? '携程 · 个人机票' : '去哪儿 · 个人机票', show: false,
      webPreferences: { partition: `persist:personal-flight-${provider}`, nodeIntegration: false, contextIsolation: true, sandbox: true, backgroundThrottling: false },
    })
    this.windows.set(provider, win)
    win.webContents.session.setPermissionRequestHandler((_webContents, _permission, callback) => callback(false))
    win.webContents.on('will-navigate', (event, url) => { if (!allowedUrl(provider, url)) event.preventDefault() })
    win.webContents.on('will-redirect', (event, url) => { if (!allowedUrl(provider, url)) event.preventDefault() })
    win.webContents.setWindowOpenHandler(({ url }) => {
      if (allowedUrl(provider, url)) void win.loadURL(url).catch(() => {})
      return { action: 'deny' }
    })
    // Observe only the response to the one order attempt. No headers or cookies
    // are collected, and raw responses are neither logged nor persisted.
    try {
      const debug = win.webContents.debugger
      debug.attach('1.3')
      void debug.sendCommand('Network.enable').catch(() => {})
      const requests = new Set()
      debug.on('message', async (_event, method, params) => {
        if (method === 'Network.responseReceived' && this.orderResults.has(provider) &&
          allowedUrl(provider, params.response.url) && /\/(?:book\/saveorder|order\/checkbookingresult|tts\/createOrder)(?:\?|$)/i.test(params.response.url) &&
          params.response.status >= 200 && params.response.status < 300) requests.add(params.requestId)
        if (method !== 'Network.loadingFinished' || !requests.delete(params.requestId)) return
        try {
          const { body, base64Encoded } = await debug.sendCommand('Network.getResponseBody', { requestId: params.requestId })
          const data = parseOrderResponse(base64Encoded ? Buffer.from(body, 'base64').toString('utf8') : body)
          const orderId = confirmedOrderId(provider, data)
          if (orderId) this.orderResults.set(provider, orderId)
        } catch { /* A missing confirmation is handled as unknown, never retried. */ }
      })
    } catch { /* UI remains usable; automatic submission needs response observation. */ }
    win.on('closed', () => {
      if (this.windows.get(provider) !== win) return
      this.windows.delete(provider)
      const cached = this.cache.get(provider)
      if (cached) cached.result = null
      this.prepared.delete(provider)
      this.offerContexts.delete(provider)
    })
    return win
  }

  async read(provider, action, payload) {
    const win = this.window(provider)
    if (!allowedUrl(provider, win.webContents.getURL())) throw new FlightBrowserError('PROVIDER_PAGE_REQUIRED', '请先打开平台页面')
    const result = await win.webContents.executeJavaScript(pageScript(action, { ...payload, provider }), true)
    if (result?.error_code) {
      if (result.error_code === 'LOGIN_REQUIRED' && this.sessionStore) {
        this.sessionStore.authentication.set(provider, false)
        await this.sessionStore.save(provider)
      }
      throw new FlightBrowserError(result.error_code, result.message)
    }
    return result
  }

  async loginQr(provider) {
    const previous = this.loginWindows.get(provider)
    if (previous) this.finishLogin(previous, 'expired')
    const win = new this.BrowserWindow({
      show: false, width: 1280, height: 900, paintWhenInitiallyHidden: true,
      webPreferences: { partition: `flight-login-${provider}-${crypto.randomUUID()}`, sandbox: true, contextIsolation: true, backgroundThrottling: false },
    })
    const contents = win.webContents
    contents.setWindowOpenHandler(() => ({ action: 'deny' }))
    for (const event of ['will-navigate', 'will-redirect']) {
      contents.on(event, (e, url) => { if (!allowedUrl(provider, url)) e.preventDefault() })
    }
    const login = { win, status: 'pending', isolated: true }
    this.loginWindows.set(provider, login)
    try {
      await win.loadURL(LOGIN_URLS[provider])
      const cookies = contents.session.cookies
      login.baseline = new Map((await cookies.get({ url: HOMES[provider] })).map(c => [`${c.domain}|${c.path}|${c.name}`, c.value]))
      let switched = false
      for (let i = 0; i < 24; i++) {
        const result = await contents.executeJavaScript(`(async () => {
          const svg = document.querySelector('.qrcode_login .qrcode-box svg')
          if (svg?.querySelector('path')) {
            const source = new XMLSerializer().serializeToString(svg)
            const image = new Image()
            image.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(source)
            await image.decode()
            const output = document.createElement('canvas')
            output.width = output.height = 320
            const context = output.getContext('2d')
            context.fillStyle = '#fff'
            context.fillRect(0, 0, 320, 320)
            context.drawImage(image, 0, 0, 320, 320)
            return { image: output.toDataURL('image/png') }
          }
          const canvas = document.querySelector('.qrcode_login .qrcode-box canvas')
          if (canvas && canvas.width >= 90 && canvas.height === canvas.width) {
            const { data } = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height)
            let dark = 0
            let light = 0
            for (let i = 0; i < data.length; i += 4) {
              if (data[i + 3] < 200) continue
              if (data[i] < 100 && data[i + 1] < 100 && data[i + 2] < 100) dark++
              if (data[i] > 220 && data[i + 1] > 220 && data[i + 2] > 220) light++
            }
            if (dark > 100 && light > 100) return { image: canvas.toDataURL('image/png') }
          }
          const qr = document.querySelector('.qr-show')
          const source = qr && getComputedStyle(qr).backgroundImage
          if (source?.includes('/qcode/show?')) return { url: source.slice(5, -2) }
          if (${switched}) return null
          const trigger = document.querySelector('.qn_right .port-toggler:not(.qrcode)') ||
            [...document.querySelectorAll('button,a,span,div,p,label')].reverse().find(el =>
              /^(扫码登录|二维码登录)$/.test(el.innerText?.trim()) && el.getClientRects().length &&
              getComputedStyle(el).visibility === 'visible')
          if (!trigger) return null
          trigger.click()
          return { switched: true }
        })()`)
        switched ||= Boolean(result?.switched)
        let image = result?.image
        if (provider === 'qunar' && result?.url) {
          const url = new URL(result.url, contents.getURL())
          if (allowedUrl(provider, url.href) && url.pathname === '/qcode/show') {
            const response = await contents.session.fetch(url.href, { credentials: 'include' })
            const bytes = Buffer.from(await response.arrayBuffer())
            const type = bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
              ? 'image/png' : response.headers.get('content-type')?.split(';')[0]
            if (response.ok && /^image\/(png|jpeg|gif|webp)$/.test(type)) {
              image = `data:${type};base64,${bytes.toString('base64')}`
            }
          }
        }
        if (image) {
          login.expires = Date.now() + 120000
          login.timer = setTimeout(() => {
            if (login.status === 'pending') this.finishLogin(login, 'expired')
          }, 125000)
          return { status: 'pending', image, expires_at: login.expires }
        }
        await delay(500)
      }
      throw new FlightBrowserError('LOGIN_QR_UNAVAILABLE', '平台未返回登录二维码，请刷新重试')
    } catch (error) {
      this.loginWindows.delete(provider)
      if (!win.isDestroyed()) win.destroy()
      throw error
    }
  }

  finishLogin(login, status) {
    clearTimeout(login.timer)
    login.status = status
    login.baseline = null
    if (!login.win.isDestroyed()) login.win.destroy()
  }

  async loginStatus(provider) {
    const login = this.loginWindows.get(provider)
    if (!login) return { status: 'idle' }
    if (login.status !== 'pending' || !login.expires) return { status: login.status }
    const { win } = login
    if (win.isDestroyed()) return { status: 'failed' }
    const url = new URL(win.webContents.getURL())
    const cookies = win.webContents.session.cookies
    const expected = new URL(LOGIN_URLS[provider]).searchParams.get(provider === 'ctrip' ? 'backurl' : 'ret')
    if (url.origin === new URL(expected).origin && url.pathname === new URL(expected).pathname) {
      const current = await cookies.get({ url: HOMES[provider] })
      if (current.some(c => login.baseline.get(`${c.domain}|${c.path}|${c.name}`) !== c.value)) {
        if (login.isolated) {
          login.status = 'confirmed'
          return { status: 'confirmed' }
        }
        await cookies.flushStore()
        await this.sessionStore?.save(provider)
        this.cache.delete(provider)
        this.prepared.delete(provider)
        this.offerContexts.delete(provider)
        this.finishLogin(login, 'authenticated')
      }
    }
    if (login.status === 'pending' && Date.now() >= login.expires) this.finishLogin(login, 'expired')
    return { status: login.status }
  }

  async wait(provider, action, payload, predicate, timeout = 40000) {
    const started = Date.now()
    while (Date.now() - started < timeout) {
      const result = await this.read(provider, action, payload)
      if (predicate(result)) return result
      await delay(700)
    }
    throw new FlightBrowserError('PAGE_CHANGED', '航班信息读取超时，请重新查询')
  }

  async sessionStatus(provider) {
    const cookies = await this.window(provider).webContents.session.cookies.get({ url: HOMES[provider] })
    const names = new Set(cookies.filter(c => !c.expirationDate || c.expirationDate > Date.now() / 1000).map(c => c.name.toLowerCase()))
    const known = this.sessionStore?.authentication.get(provider)
    const authenticated = known === false ? false : provider === 'ctrip' ? names.has('cticket') : names.has('_s') && names.has('_t')
    return { provider, status: authenticated ? 'authenticated' : 'logged_out' }
  }

  async commitLogin(provider) {
    const login = this.loginWindows.get(provider)
    if (login?.status !== 'confirmed') throw new FlightBrowserError('LOGIN_REQUIRED', '请先扫码确认登录')
    const win = this.window(provider)
    const jar = win.webContents.session.cookies
    const previous = (await jar.get({})).filter(c => cookieDetails(provider, c))
    const incoming = (await login.win.webContents.session.cookies.get({})).filter(c => cookieDetails(provider, c))
    const wasAuthenticated = this.sessionStore?.authentication.get(provider)
    const replace = async cookies => {
      for (const current of (await jar.get({})).filter(c => cookieDetails(provider, c))) {
        await jar.remove(cookieDetails(provider, current).url, current.name)
      }
      for (const cookie of cookies) await jar.set(cookieDetails(provider, cookie))
    }
    await win.loadURL('about:blank')
    this.cache.delete(provider)
    this.prepared.delete(provider)
    this.offerContexts.delete(provider)
    try {
      await replace(incoming)
      this.sessionStore?.authentication.set(provider, true)
      await jar.flushStore()
      await this.sessionStore?.save(provider)
    } catch (error) {
      await replace(previous)
      this.sessionStore?.authentication.set(provider, wasAuthenticated)
      await this.sessionStore?.save(provider)
      throw error
    }
    this.finishLogin(login, 'authenticated')
    return { status: 'authenticated' }
  }

  async search(payload) {
    const provider = payload.provider
    const url = searchUrl(payload)
    const cached = this.cache.get(provider)
    if (cached && Date.now() - cached.time < 60000) {
      const win = this.windows.get(provider)
      if (cached.url === url && cached.result && !this.prepared.has(provider) && win && !win.isDestroyed() && win.webContents.getURL() === (cached.pageUrl || url)) return { ...cached.result, cached: true }
      throw new FlightBrowserError('RATE_LIMITED', '距离上次查询不足一分钟，请稍后再查')
    }
    const win = this.window(provider)
    this.prepared.delete(provider)
    this.offerContexts.delete(provider)
    this.cache.set(provider, { url, time: Date.now(), result: null })
    try {
      await win.loadURL(url)
    } catch (error) {
      if (error.code !== 'ERR_ABORTED' && error.errno !== -3) throw error
    }
    const result = await this.wait(provider, 'search', payload, value => value.flights?.length > 0 || value.empty)
    const response = { ...result, queried_at: new Date().toISOString(), cached: false }
    this.cache.set(provider, { url, pageUrl: win.webContents.getURL(), time: Date.now(), result: response })
    return response
  }

  async offers(payload) {
    const provider = payload.provider
    const key = JSON.stringify([searchUrl(payload), payload.flight_number])
    const context = this.offerContexts.get(provider)
    if (context?.key === key && Date.now() - context.time < 120000 &&
      (await this.read(provider, 'status', payload)).phase === 'offers') {
      return this.read(provider, 'offers', payload)
    }
    await this.search(payload)
    await this.read(provider, 'open_flight', payload)
    await this.wait(provider, 'status', payload, result => result.phase === 'offers')
    const expanded = await this.read(provider, 'expand_offers', payload)
    if (expanded.expanded) await delay(900)
    const result = await this.read(provider, 'offers', payload)
    this.offerContexts.set(provider, { key, time: Date.now() })
    return result
  }

  async prepare(payload) {
    const provider = payload.provider
    const fingerprint = bookingFingerprint(payload)
    const existing = this.prepared.get(provider)
    if (!existing || existing.fingerprint !== fingerprint || Date.now() - existing.time > 120000) {
      if (existing) {
        this.prepared.delete(provider)
        const cached = this.cache.get(provider)
        if (cached) cached.result = null
      }
      await this.offers(payload)
      const chosen = await this.read(provider, 'open_offer', payload)
      await this.wait(provider, 'status', payload, result => result.phase === 'checkout')
      this.prepared.set(provider, { fingerprint, time: Date.now(), offer: chosen.offer })
    }
    await this.read(provider, 'select_passengers', payload)
    await delay(900)
    const checkout = await this.read(provider, 'checkout', payload)
    return { ...checkout, offer: this.prepared.get(provider).offer }
  }

  async submit(payload) {
    const provider = payload.provider
    const context = this.prepared.get(provider)
    const key = JSON.stringify([provider, payload.origin, payload.destination, payload.departure_date, payload.flight_number, [...payload.passengers].sort()])
    if (!context || context.fingerprint !== bookingFingerprint(payload) || Date.now() - context.time > 120000) {
      throw new FlightBrowserError('QUOTE_EXPIRED', '预订行程不一致或报价已过期，请重新核对')
    }
    if (this.submissionKeys.has(key)) throw new FlightBrowserError('ORDER_REVIEW_REQUIRED', '此行程已尝试下单，请先在平台核对订单')
    const current = await this.read(provider, 'checkout', payload)
    if (!current.total_verified || current.total !== payload.verified_total || Number(current.total) > Number(payload.max_total) ||
      current.flight_number !== payload.flight_number || current.departure_date !== payload.departure_date || !current.route_verified ||
      !current.adult_only || !current.contact_ready || current.restricted ||
      (!payload.allow_service_packages && current.service_package) ||
      JSON.stringify([...current.selected_passengers].sort()) !== JSON.stringify([...payload.passengers].sort())) {
      throw new FlightBrowserError('CHECKOUT_CHANGED', '提交前价格或乘机信息发生变化，已停止提交')
    }
    if (!this.window(provider).webContents.debugger.isAttached()) {
      throw new FlightBrowserError('ORDER_OBSERVER_REQUIRED', '无法确认订单结果，请在平台手动完成预订')
    }
    this.submissionKeys.add(key)
    this.orderResults.set(provider, null)
    await this.read(provider, 'submit', payload)
    const until = Date.now() + 35000
    while (Date.now() < until) {
      const orderId = this.orderResults.get(provider)
      if (orderId) {
        this.orderResults.delete(provider)
        return { status: 'pending_payment', order_id: orderId }
      }
      await delay(500)
    }
    this.orderResults.delete(provider)
    throw new FlightBrowserError('ORDER_UNKNOWN', '提交结果不明确，请在平台核对订单，禁止重试')
  }

  command(action, payload) {
    if (action === 'desktop_notify') {
      this.onNotify?.(String(payload.title || ''), String(payload.content || ''))
      return Promise.resolve({ delivered: !!this.onNotify })
    }
    if (action === 'status') return this.status()
    const provider = payload.provider
    if (!HOMES[provider]) return Promise.reject(new FlightBrowserError('INVALID_PROVIDER', '不支持的平台'))
    const prior = this.queues.get(provider) || Promise.resolve()
    const job = prior.catch(() => {}).then(async () => {
      if (action === 'login_qr') return this.loginQr(provider)
      if (action === 'login_status') return this.loginStatus(provider)
      if (action === 'login_commit') return this.commitLogin(provider)
      if (action === 'session_status') return this.sessionStatus(provider)
      if (action === 'open') {
        const win = this.window(provider)
        win.show()
        const current = win.webContents.getURL()
        // An explicit open action returns an unprepared search/error page to
        // the ordinary homepage so the owner can enter the normal login flow.
        if (!current) await win.loadURL(HOMES[provider])
        else if (!this.prepared.has(provider) && !/checkout|\/tts\/book|order|pay|login/i.test(new URL(current).pathname)) await win.loadURL(HOMES[provider])
        return { opened: true }
      }
      if (action === 'search') return this.search(payload)
      if (action === 'offers') return this.offers(payload)
      if (action === 'prepare') return this.prepare(payload)
      if (action === 'submit') return this.submit(payload)
      throw new FlightBrowserError('INVALID_ACTION', '不支持的操作')
    })
    this.queues.set(provider, job)
    return job
  }

  async status() {
    const providers = await Promise.all(Object.entries(HOMES).map(async ([provider, url]) => {
      const win = this.windows.get(provider)
      if (!win || win.isDestroyed()) return { provider, connected: false, cookie_count: 0 }
      const cookies = await win.webContents.session.cookies.get({ url })
      return { provider, connected: true, cookie_count: cookies.length }
    }))
    return { providers }
  }

  close() {
    for (const win of this.windows.values()) if (!win.isDestroyed()) win.destroy()
    for (const login of this.loginWindows.values()) this.finishLogin(login, 'expired')
  }
}

async function startFlightBrowserServer(controller, token) {
  const server = http.createServer((req, res) => {
    const respond = (status, data) => { res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(data)) }
    const received = Buffer.from(String(req.headers['x-flight-token'] || ''))
    const expected = Buffer.from(token)
    if (received.length !== expected.length || !crypto.timingSafeEqual(received, expected)) return respond(401, { success: false, error_code: 'UNAUTHORIZED', message: '桌面会话无效' })
    if (req.method !== 'POST' || req.url !== '/command') return respond(404, { success: false })
    let body = ''
    req.on('data', chunk => { body += chunk; if (body.length > 65536) req.destroy() })
    req.on('end', async () => {
      try {
        const { action, payload } = JSON.parse(body)
        const data = await controller.command(action, payload || {})
        respond(200, { success: true, data })
      } catch (error) {
        respond(409, { success: false, error_code: error instanceof FlightBrowserError ? error.code : 'BROWSER_UNAVAILABLE', message: error instanceof FlightBrowserError ? error.message : '平台连接暂时不可用，请重试' })
      }
    })
  })
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
  return { url: `http://127.0.0.1:${server.address().port}`, close: () => server.close() }
}

module.exports = { FlightBrowser, FlightBrowserError, startFlightBrowserServer, allowedUrl, searchUrl, extractOrderId, parseOrderResponse, confirmedOrderId, bookingFingerprint }
