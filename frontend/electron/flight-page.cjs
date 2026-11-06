/* Executed in a provider page. Keep every returned object free of cookies,
 * identity numbers, phone numbers and raw page HTML. */
function flightPage(action, options = {}) {
  const provider = options.provider
  const text = document.body?.innerText || ''
  const visible = el => !!el && !!(el.getClientRects().length) && getComputedStyle(el).visibility !== 'hidden'
  const clean = value => (value || '').replace(/\s+/g, ' ').trim()
  const flightPattern = /\b(?:[A-Z][A-Z0-9]|[0-9][A-Z])\d{3,4}\b/g
  const restrictedPattern = /青年专享|长者|学生(?:专享|预订)|军警|双人行|多人特惠|小团|家庭|亲子|限\s*\d+\s*人|年龄限制|新会员|\d{1,3}\s*(?:[-~至到－—]\s*\d{1,3})?\s*(?:周岁|岁)/
  const packagePattern = /服务包|出行保障|旅行套餐|保险套餐/
  const failure = (code, message) => ({ error_code: code, message })
  const login = [...document.querySelectorAll('input')].some(e => visible(e) && /请输入手机号码|登录密码|用户名\/邮箱/.test(e.placeholder))
  if (/whaleguard block/i.test(text)) return failure('PROVIDER_BLOCKED', '平台拒绝了当前桌面会话。请先核对登录会话，当前页面没有可操作的验证')
  const challenge = /请完成安全验证|请拖动滑块|请按顺序点击|访问过于频繁/i.test(text)
  if (challenge) return failure('VERIFICATION_REQUIRED', '请在平台窗口完成验证后重新启动任务')
  if (login) return failure('LOGIN_REQUIRED', '请在机票页扫码登录后重试')
  if (/您终于回来了/.test(text) && /为您重新查询/.test(text)) return failure('QUOTE_STALE', '平台提示报价已过期，请在平台确认重新查询后再试')

  function prices(value) {
    const found = []
    for (const m of value.matchAll(/[¥￥]\s*([\d,]+(?:\.\d{1,2})?)/g)) {
      const before = value.slice(Math.max(0, m.index - 5), m.index)
      if (/(?:退改|已减|[+＋])\s*$/.test(before)) continue
      const n = Number(m[1].replace(/,/g, ''))
      if (Number.isFinite(n) && n > 0) found.push(n)
    }
    return found
  }
  function flightRows() {
    const rows = []
    const seen = new Set()
    for (const leaf of document.querySelectorAll('span, div, p')) {
      if (!visible(leaf)) continue
      const ownText = [...leaf.childNodes].filter(node => node.nodeType === 3).map(node => node.textContent).join(' ')
      const number = ownText.match(flightPattern)?.[0]
      if (!number || seen.has(number)) continue
      let el = leaf
      for (let depth = 0; el && depth < 9; depth++, el = el.parentElement) {
        const value = el.innerText || ''
        const codes = [...new Set(value.match(flightPattern) || [])]
        const times = value.match(/\b\d{2}:\d{2}\b/g) || []
        const displayedPrice = provider === 'qunar' ? el.querySelector('.price-info') : null
        const values = displayedPrice ? prices('¥' + clean(displayedPrice.textContent)) : prices(value)
        if (codes.length === 1 && times.length >= 2 && values.length && value.length < 3500) {
          seen.add(number)
          rows.push({ el, flight_number: number, base_price: values[values.length - 1], times, value })
          break
        }
      }
    }
    return rows
  }
  function offerRows() {
    const offers = []
    const seen = new Set()
    for (const button of document.querySelectorAll('button, a, [role="button"], span, div')) {
      if (!visible(button) || !/^(预订|选购)$/.test(clean(button.innerText)) || button.childElementCount > 2) continue
      if (button.querySelector('button, a, [role="button"]')) continue
      let el = button
      let candidate = null
      for (let depth = 0; el && depth < 9; depth++, el = el.parentElement) {
        const value = el.innerText || ''
        if (!prices(value).length || !/经济舱|公务舱|商务舱|头等舱/.test(value) || !/退改|行李/.test(value)) continue
        const controls = [...el.querySelectorAll(provider === 'qunar' ? '.btn.yudin, button' : 'button')]
          .filter(e => /^(预订|选购)$/.test(clean(e.innerText)))
        if (value.length > 2200 || controls.length > 1) break
        const amounts = prices(value)
        candidate = { button, value, base_price: amounts[amounts.length - 1], restricted: restrictedPattern.test(value), service_package: packagePattern.test(value), direct_booking: clean(button.innerText) === '预订' }
        if (el.matches('.seat-row')) break
      }
      if (candidate && !seen.has(candidate.value)) { seen.add(candidate.value); offers.push(candidate) }
    }
    return offers
  }
  function passengers() {
    if (provider === 'qunar') {
      return [...document.querySelectorAll('.outer-passenger-item')].map(el => ({
        name: clean(el.querySelector('.passenger-name')?.textContent),
        selected: !!el.querySelector('.passenger-checkbox .chexked, .passenger-checkbox .checked'),
        adult: clean(el.querySelector('.ticket-type')?.textContent) === '成人',
        el,
      })).filter(p => p.name)
    }
    return [...document.querySelectorAll('.psg-common__item')].map(label => ({
      name: clean(label.querySelector('.psg-common__item-text')?.textContent),
      selected: !!label.querySelector('input[type="checkbox"]')?.checked,
      el: label,
    })).filter(p => p.name && p.name.length <= 60)
  }
  const isCheckout = /\/itinerary\/checkout\/(?:passenger|service)|\/flight\/tts\/book/.test(location.pathname)
  if (action === 'status') {
    return { connected: true, login_required: login, phase: isCheckout ? 'checkout' : offerRows().length ? 'offers' : 'search' }
  }
  if (action === 'search') {
    const rows = flightRows()
    return { empty: rows.length === 0 && /暂无航班|暂无符合条件的航班|没有查询到(?:符合条件的)?航班|未搜索到航班|没有找到.*航班/.test(text), flights: rows.map(r => ({
      provider, flight_number: r.flight_number, base_price: r.base_price.toFixed(2),
      departure_time: r.times[0], arrival_time: r.times[1], price_kind: 'indicative_excluding_taxes',
    })) }
  }
  if (action === 'offers') {
    return { offers: offerRows().map((offer, index) => ({
      index, base_price: offer.base_price.toFixed(2), restricted: offer.restricted,
      service_package: offer.service_package, economy: /经济舱/.test(offer.value), direct_booking: offer.direct_booking,
      offer_key: clean(offer.value), description: clean(offer.value).replace(/(?:预订|选购)$/, '').trim(),
    })) }
  }
  if (action === 'open_flight') {
    const row = flightRows().find(r => r.flight_number === options.flight_number)
    if (!row) return failure('FLIGHT_NOT_FOUND', '当前页面未找到指定航班')
    const book = [...row.el.querySelectorAll('button, a, [role="button"]')].find(e => /^订票/.test(clean(e.innerText)))
    ;(book || row.el).click()
    return { clicked: true }
  }
  if (action === 'open_offer') {
    const eligible = offerRows().filter(o => /经济舱/.test(o.value) && !o.restricted && (options.allow_service_packages || !o.service_package))
    const supported = eligible.filter(o => o.direct_booking && (!options.offer_key || clean(o.value) === options.offer_key))
    if (options.offer_key && !supported.length) return failure('OFFER_CHANGED', '所选舱位报价已更新，请重新获取舱位')
    if (eligible.length && !supported.length) return failure('PRODUCT_SELECTION_REQUIRED', '当前普通产品需要进一步选购，请在平台完成产品选择')
    const candidates = supported
      .filter(o => o.base_price * options.passengers.length <= Number(options.max_total))
      .sort((a, b) => a.base_price - b.base_price)
    if (!candidates.length) return eligible.length ? failure('OVER_BUDGET', '当前普通产品票面价已超过总预算，继续监控') : failure('NO_ELIGIBLE_OFFER', '没有可确认适用的普通产品，请在平台核对')
    candidates[0].button.click()
    return { clicked: true, offer: { base_price: candidates[0].base_price.toFixed(2), service_package: candidates[0].service_package, restricted: false } }
  }
  if (action === 'expand_offers') {
    const expand = [...document.querySelectorAll('button, span, div')].find(el => visible(el) && el.childElementCount <= 2 && /^展开查看所有产品/.test(clean(el.innerText)))
    expand?.click()
    return { expanded: !!expand }
  }
  if (action === 'select_passengers') {
    const entries = passengers()
    const expected = options.passengers || []
    if (!expected.every(name => entries.filter(p => p.name === name).length === 1)) {
      return failure('PASSENGERS_UNVERIFIED', '请先在平台保存并核对任务中的乘机人姓名')
    }
    for (const entry of entries) {
      if (entry.selected !== expected.includes(entry.name)) {
        const control = provider === 'qunar' ? entry.el.querySelector('.passenger-checkbox') : entry.el
        control?.click()
      }
    }
    return { selected: true }
  }
  if (action === 'checkout') {
    if (!isCheckout) return failure('CHECKOUT_REQUIRED', '当前未进入订单填写页')
    const entries = passengers()
    let selected = entries.filter(p => p.selected)
    if (provider === 'ctrip') {
      const forms = [...document.querySelectorAll('.J_inputName')].filter(visible)
      const names = forms.map(input => clean(input.value))
      if (JSON.stringify([...names].sort()) !== JSON.stringify(selected.map(p => p.name).sort())) selected = []
      else selected = forms.map(input => {
        const index = input.id.match(/^p_name_(\d+)$/)?.[1]
        const card = document.getElementById(`p_card_no_${index}`)?.value || ''
        const birthday = /^\d{17}[\dXx]$/.test(card) ? `${card.slice(6, 10)}-${card.slice(10, 12)}-${card.slice(12, 14)}` : document.getElementById(`p_birthday_${index}`)?.value
        const birth = /^\d{4}-\d{2}-\d{2}$/.test(birthday || '') ? new Date(birthday + 'T00:00:00Z') : null
        const departure = new Date(options.departure_date + 'T00:00:00Z')
        const cutoff = new Date(departure); cutoff.setUTCFullYear(cutoff.getUTCFullYear() - 12)
        return { name: clean(input.value), adult: !!birth && birth <= cutoff }
      })
    }
    const totalNode = document.querySelector(provider === 'qunar' ? '#js-pay-money-amount' : '#J_totalPrice')
    const totalText = clean(totalNode?.textContent).replace(/^[¥￥]\s*/, '').replace(/,/g, '')
    const total = /^\d+(?:\.\d{1,2})?$/.test(totalText) ? Number(totalText) : null
    const tripText = provider === 'qunar' ? clean(document.querySelector('.m-header .title')?.textContent) : document.querySelector('.sidebar .flight-info')?.innerText || ''
    const productText = provider === 'qunar' ? [...document.querySelectorAll('.m-ticketinfo, .m-tips-bar')].map(el => el.innerText).join(' ') : document.querySelector('.sidebar')?.innerText || ''
    const params = new URLSearchParams(location.search)
    const bookingFlight = provider === 'qunar' ? params.get('bookingParamKey')?.match(/(?:^|_)((?:[A-Z][A-Z0-9]|[0-9][A-Z])\d{3,4})(?=_|$)/)?.[1] : null
    let queryDate = null
    if (provider === 'qunar') {
      try { queryDate = JSON.parse(decodeURIComponent(params.get('searchParam') || '')).st } catch { /* Unknown date remains unverified. */ }
    }
    const mmdd = options.departure_date?.slice(5)
    const normalized = text.replace(/\s+/g, '')
    const dateFound = !!mmdd && (normalized.includes(mmdd) || normalized.includes(mmdd.replace('-', '月') + '日'))
    const phones = provider === 'ctrip' ? [...document.querySelectorAll('#I_contact_phone')].filter(visible) : [...document.querySelectorAll('input')].filter(e => visible(e) && /接收航班短信/.test(e.placeholder))
    return {
      flight_number: bookingFlight || tripText.match(flightPattern)?.[0] || null,
      departure_date: dateFound && (provider !== 'qunar' || queryDate === options.departure_date) ? options.departure_date : null,
      route_verified: !!options.origin_name && !!options.destination_name && tripText.indexOf(options.origin_name) >= 0 && tripText.indexOf(options.destination_name) > tripText.indexOf(options.origin_name),
      selected_passengers: selected.map(p => p.name), adult_only: selected.length > 0 && selected.every(p => p.adult),
      contact_ready: phones.length === 1 && /^(?:\+?\d[\d -]{5,19})$/.test(phones[0].value),
      total: Number.isFinite(total) ? total.toFixed(2) : null,
      total_verified: Number.isFinite(total) && total > 0 && /机建/.test(text) && /燃油/.test(text),
      currency: 'CNY', restricted: restrictedPattern.test(productText), service_package: packagePattern.test(productText),
    }
  }
  if (action === 'submit') {
    if (!isCheckout) return failure('CHECKOUT_REQUIRED', '当前未进入订单填写页')
    let button
    if (provider === 'qunar') {
      button = [...document.querySelectorAll('[aria-label], [data-testid], button, div')]
        .find(e => visible(e) && clean(e.innerText) === '提交订单' && e.childElementCount <= 2)
    } else {
      button = document.querySelector('#J_saveOrder')
    }
    if (!button || button.disabled || button.getAttribute('aria-disabled') === 'true') {
      return failure('SUBMIT_UNAVAILABLE', '当前无法明确识别提交按钮，请在平台窗口处理')
    }
    button.click()
    return { submitted: true }
  }
  return failure('UNSUPPORTED_ACTION', '不支持的页面操作')
}

function pageScript(action, options) {
  return `(${flightPage.toString()})(${JSON.stringify(action)}, ${JSON.stringify(options)})`
}

module.exports = { flightPage, pageScript }
