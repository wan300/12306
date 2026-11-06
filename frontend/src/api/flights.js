import { desktopPayload } from './desktopPayload'

const call = async (method, route, body) => {
  if (!window.electronAPI?.flights) throw new Error('请在电脑桌面应用中使用个人机票功能')
  const result = await window.electronAPI.flights.request(method, `/flights/${route}`, desktopPayload(body))
  if (!result?.success) throw new Error(result?.message || '机票操作未完成')
  return result.data
}

export default {
  loginQr: provider => call("POST", `providers/${provider}/login/qr`),
  loginStatus: provider => call("GET", `providers/${provider}/login/status`),
  session: provider => call('GET', `providers/${provider}/session`),
  providers: () => call('GET', 'providers'),
  open: provider => call('POST', `providers/${provider}/open`),
  search: query => call('POST', 'search', query),
  offers: query => call('POST', 'offers', query),
  prepare: query => call('POST', 'prepare', query),
  order: booking => call('POST', 'orders', booking),
  monitors: () => call('GET', 'monitors'),
  get: id => call('GET', `monitors/${id}`),
  update: (id, config) => call('PATCH', `monitors/${id}`, config),
  create: config => call('POST', 'monitors', config),
  start: id => call('POST', `monitors/${id}/start`),
  pause: id => call('POST', `monitors/${id}/pause`),
  remove: id => call('DELETE', `monitors/${id}`),
}
