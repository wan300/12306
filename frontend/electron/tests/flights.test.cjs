const test = require('node:test')
const assert = require('node:assert/strict')
const { JSDOM } = require('jsdom')
const { flightPage } = require('../flight-page.cjs')
const { FlightBrowser, searchUrl, allowedUrl, extractOrderId, parseOrderResponse, confirmedOrderId, startFlightBrowserServer, bookingFingerprint } = require('../flight-browser.cjs')

function page(html, provider, action, options = {}) {
  const url = provider === 'qunar' ? 'https://m.flight.qunar.com/flight/tts/book?' + new URLSearchParams({ bookingParamKey: 'f_domestic_uniform_booking_MU5138_test', searchParam: encodeURIComponent(JSON.stringify({ st: '2026-11-15', sc: '北京', dc: '上海' })) }) : 'https://flights.ctrip.com/itinerary/checkout/passenger'
  const dom = new JSDOM(html, { url, runScripts: 'outside-only' })
  Object.defineProperty(dom.window.HTMLElement.prototype, 'innerText', { get() { return this.textContent } })
  dom.window.HTMLElement.prototype.getClientRects = () => [{ width: 10, height: 10 }]
  const result = dom.window.eval(`(${flightPage.toString()})(${JSON.stringify(action)}, ${JSON.stringify({ provider, ...options })})`)
  dom.window.close()
  return JSON.parse(JSON.stringify(result))
}

const payload = {
  provider: 'qunar', origin: 'BJS', destination: 'SHA', origin_name: '北京', destination_name: '上海',
  departure_date: '2026-11-15', flight_number: 'MU5138', passengers: ['测试旅客'], max_total: '600.00', verified_total: '570.00',
}
const booking = `
  <header class="m-header"><div class="title">北京 上海</div></header><div class="m-flight">11-15周日</div>
  <div class="m-ticketinfo">机建 ¥50 燃油 ¥90 成人 ¥430</div>
  <div class="outer-passenger-item"><span class="passenger-name">测试旅客</span><span class="ticket-type">成人</span><div class="passenger-checkbox"><i class="chexked"></i></div></div>
  <div class="outer-passenger-item"><span class="passenger-name">其他旅客</span><span class="ticket-type">成人</span><div class="passenger-checkbox"><i></i></div></div>
  <input placeholder="接收航班短信 必填" value="13800000000">
  <div><span id="js-pay-money-amount">570</span></div><div aria-label="submit">提交订单</div>`

test('Qunar checkout reads selected names and total, never identity or phone values', () => {
  const result = page(booking, 'qunar', 'checkout', payload)
  assert.equal(result.total, '570.00')
  assert.equal(result.total_verified, true)
  assert.equal(result.route_verified, true)
  assert.deepEqual(result.selected_passengers, ['测试旅客'])
  assert.equal(result.contact_ready, true)
  assert.equal(result.adult_only, true)
  assert.ok(!JSON.stringify(result).includes('13800000000'))
})

test('checkout cannot verify a different date or a missing passenger selection', () => {
  assert.equal(page(booking, 'qunar', 'checkout', { ...payload, departure_date: '2026-11-16' }).departure_date, null)
  assert.deepEqual(page(booking.replace('class="chexked"', 'class=""'), 'qunar', 'checkout', payload).selected_passengers, [])
  assert.equal(page(booking.replace('燃油', '未知费用'), 'qunar', 'checkout', payload).total_verified, false)
})

test('paid extras and restricted products are explicitly identified', () => {
  assert.equal(page(booking + '<div class="m-tips-bar">金牌服务包</div>', 'qunar', 'checkout', payload).service_package, true)
  assert.equal(page(booking + '<div class="m-tips-bar">青年专享</div>', 'qunar', 'checkout', payload).restricted, true)
  assert.equal(page(booking.replace('成人</span>', '儿童</span>'), 'qunar', 'checkout', payload).adult_only, false)
})

test('checkout uses only the explicit grand total and ignores optional or agreement prices', () => {
  assert.equal(page(booking + '<div>推荐保险¥20 家庭出行服务包协议</div>', 'qunar', 'checkout', payload).total, '570.00')
  assert.equal(page(booking + '<div>家庭出行服务包协议</div>', 'qunar', 'checkout', payload).restricted, false)
  assert.equal(page(booking.replace('id="js-pay-money-amount"', 'id="unrecognized-total"'), 'qunar', 'checkout', payload).total_verified, false)
  assert.equal(page(booking.replace('北京 上海', '上海 北京'), 'qunar', 'checkout', payload).route_verified, false)
})

test('Ctrip checkout requires saved names to match the actual form, adults and contact', () => {
  const html = `<label class="psg-common__item"><input type="checkbox" checked><div class="psg-common__item-text">测试旅客</div></label>
    <input class="J_inputName" id="p_name_0" value="测试旅客"><input id="p_birthday_0" value="2000-01-01">
    <input id="I_contact_phone" value="13800000000"><div class="sidebar"><div class="flight-info">11-15周日 北京 上海 MU5138</div>
    <div>成人 ¥450 机建 ¥50 燃油 ¥90</div><span id="J_totalPrice"><dfn>¥</dfn>590</span></div>`
  const result = page(html, 'ctrip', 'checkout', { ...payload, provider: 'ctrip' })
  assert.equal(result.total, '590.00')
  assert.equal(result.adult_only, true)
  assert.equal(result.contact_ready, true)
  assert.deepEqual(result.selected_passengers, ['测试旅客'])
  assert.equal(page(html.replace('2000-01-01', '2020-01-01'), 'ctrip', 'checkout', payload).adult_only, false)
  assert.deepEqual(page(html.replace('value="测试旅客"', 'value="错误旅客"'), 'ctrip', 'checkout', payload).selected_passengers, [])
})

test('login and challenge pages pause instead of returning fake quotes', () => {
  assert.equal(page('<input placeholder="请输入手机号码">', 'qunar', 'search').error_code, 'LOGIN_REQUIRED')
  assert.equal(page('<div>请拖动滑块</div>', 'ctrip', 'search').error_code, 'VERIFICATION_REQUIRED')
  assert.equal(page('<div>whaleguard block</div>', 'ctrip', 'search').error_code, 'PROVIDER_BLOCKED')
  assert.equal(page('<div>您终于回来了 航班可能有变，为您重新查询</div>', 'ctrip', 'search').error_code, 'QUOTE_STALE')
})

test('Ctrip excludes live age/student restrictions and distinguishes selection from direct booking', () => {
  const row = (label, price, button) => `<div class="seat-row">退改¥23起 托运行李20KG 经济舱 ${label} ¥${price} <button>${button}</button></div>`
  const html = row('限16-25岁学生预订', 428, '选购') + row('限16-23岁乘客预订', 428, '选购') + row('', 450, '选购') + row('', 450, '预订')
  const offers = page(html, 'ctrip', 'offers', payload).offers
  assert.equal(offers[0].restricted, true)
  assert.equal(offers[1].restricted, true)
  assert.equal(offers[2].direct_booking, false)
  assert.equal(page(html, 'ctrip', 'open_offer', payload).offer.base_price, '450.00')
  assert.equal(page(row('', 450, '选购'), 'ctrip', 'open_offer', payload).error_code, 'PRODUCT_SELECTION_REQUIRED')
})

test('Ctrip reads flight numbers beside nested aircraft labels', () => {
  const html = '<div class="flight-item"><span class="plane-No">MF8561 <span>波音737(中)</span></span>\n<div>08:00</div>\n<div>10:10<span> </span></div>\n<div>已减¥5</div>\n<div>普通会员可享</div>\n<div>¥565起</div><button>订票</button></div>'
  const result = page(html, 'ctrip', 'search')
  assert.equal(result.flights.length, 1)
  assert.equal(result.flights[0].flight_number, 'MF8561')
  assert.equal(result.flights[0].base_price, '565.00')
})

test('Ctrip clicks the booking button inside its action wrapper', () => {
  const html = '<div class="seat-row">经济舱 退改 行李 ¥620<div class="seat-action"><button onclick="document.body.dataset.booked=1">预订</button></div></div>'
  const dom = new JSDOM(html, { runScripts: 'dangerously' })
  Object.defineProperty(dom.window.HTMLElement.prototype, 'innerText', { get() { return this.textContent } })
  dom.window.HTMLElement.prototype.getClientRects = () => [{ width: 10, height: 10 }]
  dom.window.eval(`(${flightPage.toString()})('open_offer', {provider:'ctrip', passengers:['测试旅客'], max_total:800})`)
  assert.equal(dom.window.document.body.dataset.booked, '1')
  dom.window.close()
})

test('selected cabin must match the current product instead of switching to a cheaper one', () => {
  const row = price => `<div class="seat-row">经济舱 退改 行李 ¥${price}<button>预订</button></div>`
  const html = row(620) + row(700)
  const offers = page(html, 'ctrip', 'offers').offers
  assert.equal(page(html, 'ctrip', 'open_offer', { ...payload, offer_key: offers[1].offer_key, max_total: 800 }).offer.base_price, '700.00')
  assert.equal(page(row(620), 'ctrip', 'open_offer', { ...payload, offer_key: offers[1].offer_key, max_total: 800 }).error_code, 'OFFER_CHANGED')
})

test('preparing a booking reuses the displayed cabin list and keeps the provider window hidden', async () => {
  const controller = new FlightBrowser({ BrowserWindow: null })
  const query = { ...payload, provider: 'ctrip' }
  controller.window = () => { throw new Error('must not display a provider window') }
  controller.search = async () => { throw new Error('must reuse displayed cabins') }
  controller.offerContexts.set('ctrip', { key: JSON.stringify([searchUrl(query), query.flight_number]), time: Date.now() })
  controller.read = async (_provider, action) => {
    if (action === 'status') return { phase: 'offers' }
    if (action === 'offers') return { offers: [] }
    if (action === 'open_offer') return { offer: { base_price: '450.00' } }
    if (action === 'checkout') return { total: '570.00', selected_passengers: query.passengers }
    return {}
  }
  controller.wait = async () => ({ phase: 'checkout' })
  const result = await controller.prepare(query)
  assert.equal(result.total, '570.00')
  assert.equal(result.offer.base_price, '450.00')
})

test('flight rows retain price semantics and ignore discount amounts', () => {
  const html = '<div class="flight-item"><span>东方航空 MU5138</span>\n<p>07:20 北京大兴</p>\n<p>09:15 虹桥</p>\n<p>已减¥51</p>\n<p>¥450起</p><button>订票</button></div>'
  const result = page(html, 'ctrip', 'search')
  assert.equal(result.flights.length, 1)
  assert.equal(result.flights[0].base_price, '450.00')
  assert.equal(result.flights[0].price_kind, 'indicative_excluding_taxes')
})

test('plain offer selection excludes age restrictions and service packages', () => {
  const html = '<div>青年专享 经济舱 免费行李 退改22元起 ¥414 <button>预订</button></div>' +
    '<div>经济舱 免费行李 退改22元起 ¥430 +¥48出行保障 <button>预订</button></div>'
  assert.equal(page(html, 'qunar', 'open_offer', payload).error_code, 'NO_ELIGIBLE_OFFER')
})

test('offer selection considers tags outside the price block and chooses a plain product', () => {
  const html = '<ul><li><div>+¥48出行保障</div><div class="content">经济舱 行李20KG 退改22元起 ¥430 <div class="btn yudin">预订</div></div></li>' +
    '<li><div class="content">经济舱 行李20KG 退改23元起 ¥450 <div class="btn yudin">预订</div></div></li></ul>'
  assert.equal(page(html, 'qunar', 'open_offer', payload).clicked, true)
  assert.equal(page(html, 'qunar', 'open_offer', { ...payload, max_total: '400' }).error_code, 'OVER_BUDGET')
})

test('a confirmed empty list is different from an unrecognized layout', () => {
  assert.equal(page('<div>暂无符合条件的航班</div>', 'qunar', 'search').empty, true)
  assert.equal(page('<div>没有查询到符合条件的航班</div>', 'qunar', 'search').empty, true)
  assert.equal(page('<div>页面正在加载</div>', 'qunar', 'search').empty, false)
})

test('ordinary consumer URLs are fixed and provider navigation cannot escape its domain', () => {
  assert.equal(new URL(searchUrl(payload)).hostname, 'touch.qunar.com')
  assert.equal(new URL(searchUrl(payload)).searchParams.get('depCity'), '北京')
  assert.equal(new URL(searchUrl(payload)).searchParams.get('goDate'), payload.departure_date)
  assert.match(searchUrl({ ...payload, provider: 'ctrip' }), /oneway-bjs-sha/)
  assert.equal(allowedUrl('ctrip', 'https://flights.ctrip.com/online/'), true)
  assert.equal(allowedUrl('ctrip', 'https://ctrip.com.attacker.invalid/'), false)
  assert.equal(allowedUrl('qunar', 'http://touch.qunar.com/'), false)
  assert.throws(() => searchUrl({ ...payload, origin: '../../' }))
})

test('order confirmation requires a usable order identifier', () => {
  assert.equal(extractOrderId({ Data: { OrderID: '123456789012' } }), '123456789012')
  assert.equal(extractOrderId({ success: true }), null)
  assert.equal(extractOrderId({ orderId: 12345678901234567890 }), null)
})

test('scan login confirms the return page and persists the shared session without returning cookies', async () => {
  const controller = new FlightBrowser({ BrowserWindow: null })
  let url = 'https://passport.ctrip.com/user/login'
  let flushed = false
  let destroyed = false
  controller.loginWindows.set('ctrip', {
    status: 'pending', expires: Date.now() + 10000, baseline: new Map(),
    win: {
      isDestroyed: () => destroyed,
      destroy: () => { destroyed = true },
      webContents: {
        getURL: () => url,
        session: { cookies: {
          get: async () => [{ domain: '.ctrip.com', name: 'fixture', path: '/', value: 'fixture-value' }],
          flushStore: async () => { flushed = true },
        } },
      },
    },
  })
  assert.deepEqual(await controller.command('login_status', { provider: 'ctrip' }), { status: 'pending' })
  url = 'https://www.ctrip.com/'
  assert.deepEqual(await controller.command('login_status', { provider: 'ctrip' }), { status: 'authenticated' })
  assert.ok(flushed && destroyed)
})

test('Qunar QR images work when the image endpoint omits Content-Type', async () => {
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==', 'base64')
  class LoginWindow {
    constructor() {
      this.webContents = {
        setWindowOpenHandler() {},
        on() {},
        getURL: () => 'https://user.qunar.com/passport/interLogin.jsp',
        executeJavaScript: async () => ({ url: 'https://user.qunar.com/qcode/show?fixture=1' }),
        session: { cookies: { get: async () => [] }, fetch: async () => new Response(png) },
      }
    }
    async loadURL() {}
    isDestroyed() { return false }
    destroy() {}
  }
  const controller = new FlightBrowser({ BrowserWindow: LoginWindow })
  try {
    const result = await controller.loginQr('qunar')
    assert.equal(result.status, 'pending')
    assert.equal(result.image, 'data:image/png;base64,' + png.toString('base64'))
  } finally { controller.finishLogin(controller.loginWindows.get('qunar'), 'expired') }
})

test('order responses support JSON and JSONP without evaluating JavaScript', () => {
  assert.equal(extractOrderId(parseOrderResponse('callback({"Data":{"OrderID":"123456789"}});')), '123456789')
  assert.equal(extractOrderId(parseOrderResponse('{"orderNo":"123456789"}')), '123456789')
  assert.equal(parseOrderResponse('callback({"x":1}); dangerous()'), null)
})

test('provider success status is required even if a failure includes an order number', () => {
  assert.equal(confirmedOrderId('qunar', { bstatus: { code: 0 }, data: { orderDetailData: { orderInfo: { orderNo: [{ mainNo: '123456789' }] } } } }), '123456789')
  assert.equal(confirmedOrderId('qunar', { bstatus: { code: 9001 }, data: { orderNo: '123456789' } }), null)
  assert.equal(confirmedOrderId('qunar', { bstatus: { code: 0 }, data: { orderNo: '123456789', goOnMsg: 'needs review' } }), null)
  assert.equal(confirmedOrderId('ctrip', { Data: { OrderIDs: ['123456789'] } }), '123456789')
  assert.equal(confirmedOrderId('ctrip', { Error: { Code: 'B00010' }, Data: { OrderIDs: ['123456789'] } }), null)
})

test('browser operations are serialized per provider', async () => {
  const controller = new FlightBrowser({ BrowserWindow: null })
  let active = 0, maximum = 0
  controller.search = async () => {
    active++; maximum = Math.max(maximum, active)
    await new Promise(resolve => setTimeout(resolve, 10))
    active--; return { flights: [] }
  }
  await Promise.all([controller.command('search', payload), controller.command('search', payload)])
  assert.equal(maximum, 1)
})

test('closing or navigating away from a provider cannot reuse a quote or reset the minimum interval', async () => {
  const controller = new FlightBrowser({ BrowserWindow: null })
  controller.cache.set('qunar', { url: searchUrl(payload), time: Date.now(), result: { flights: [{ flight_number: 'MU5138' }] } })
  await assert.rejects(controller.search(payload), error => error.code === 'RATE_LIMITED')
  controller.windows.set('qunar', { isDestroyed: () => false, webContents: { getURL: () => searchUrl(payload) } })
  assert.equal((await controller.search(payload)).cached, true)
  controller.windows.get('qunar').webContents.getURL = () => 'https://m.flight.qunar.com/flight/tts/book'
  await assert.rejects(controller.search(payload), error => error.code === 'RATE_LIMITED')
})

test('submission rechecks total and refuses a changed quote before clicking', async () => {
  const controller = new FlightBrowser({ BrowserWindow: null })
  controller.prepared.set('qunar', { time: Date.now(), fingerprint: bookingFingerprint(payload) })
  controller.read = async () => ({ total: '650.00', total_verified: true })
  await assert.rejects(controller.submit(payload), error => error.code === 'CHECKOUT_CHANGED')
  assert.equal(controller.submissionKeys.size, 0)
})

test('Qunar reuses the list after its mobile redirect when opening cabins', async () => {
  const controller = new FlightBrowser({ BrowserWindow: null })
  let loads = 0
  controller.window = () => ({
    isDestroyed: () => false,
    loadURL: async () => { loads++ },
    webContents: { getURL: () => 'https://m.flight.qunar.com/flight/flightlist' },
  })
  controller.windows.set('qunar', controller.window())
  controller.wait = async () => ({ flights: [{ flight_number: 'MU5138' }] })
  await controller.search(payload)
  assert.equal((await controller.search(payload)).cached, true)
  assert.equal(loads, 1)
})

test('a verified submission is clicked once and needs an observed order identifier', async () => {
  const controller = new FlightBrowser({ BrowserWindow: null })
  controller.prepared.set('qunar', { time: Date.now(), fingerprint: bookingFingerprint(payload) })
  controller.window = () => ({ webContents: { debugger: { isAttached: () => true } } })
  const actions = []
  controller.read = async (_provider, action) => {
    actions.push(action)
    if (action === 'checkout') return { ...payload, total: '570.00', total_verified: true, route_verified: true, adult_only: true, contact_ready: true, selected_passengers: payload.passengers }
    controller.orderResults.set('qunar', '123456789')
    return { submitted: true }
  }
  assert.deepEqual(await controller.submit(payload), { status: 'pending_payment', order_id: '123456789' })
  await assert.rejects(controller.submit(payload), error => error.code === 'ORDER_REVIEW_REQUIRED')
  assert.deepEqual(actions, ['checkout', 'submit'])
})

test('a quote for a different itinerary cannot authorize submission', async () => {
  const controller = new FlightBrowser({ BrowserWindow: null })
  controller.prepared.set('qunar', { time: Date.now(), fingerprint: bookingFingerprint({ ...payload, flight_number: 'MU5139' }) })
  controller.read = async () => { throw new Error('must not read or submit a mismatched quote') }
  await assert.rejects(controller.submit(payload), error => error.code === 'QUOTE_EXPIRED')
})

test('Qunar list prices work when the currency sign is a CSS icon', () => {
  const result = page('<li class="list-row item"><div><p>07:20 北京大兴</p>\n<p>09:15 虹桥T2</p>\n<span>东航MU5138 空客332(宽)</span></div><div><p class="price-info"><span class="price-icon"></span><span>428</span></p></div></li>', 'qunar', 'search')
  assert.equal(result.flights[0].base_price, '428.00')
})

test('the browser bridge binds loopback and rejects requests without its token', async () => {
  const service = await startFlightBrowserServer({ command: async () => ({ ok: true }) }, 'test-local-capability')
  try {
    assert.equal((await fetch(service.url + '/command', { method: 'POST', body: '{}' })).status, 401)
    const response = await fetch(service.url + '/command', { method: 'POST', headers: { 'X-Flight-Token': 'test-local-capability' }, body: JSON.stringify({ action: 'status' }) })
    assert.deepEqual(await response.json(), { success: true, data: { ok: true } })
  } finally { service.close() }
})
