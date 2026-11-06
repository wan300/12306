const test = require('node:test')
const assert = require('node:assert/strict')
const { FlightBrowser } = require('../flight-browser.cjs')

function fixture(failSave = false) {
  const cookie = value => ({ domain: '.ctrip.com', name: 'cticket', path: '/', secure: true, value })
  let current = [cookie('previous-session')], destroyed = false, saves = 0
  const jar = {
    get: async () => current,
    remove: async (_url, name) => { current = current.filter(c => c.name !== name) },
    set: async c => { current.push(c) },
    flushStore: async () => {},
  }
  const store = { authentication: new Map([['ctrip', true], ['qunar', true]]), save: async () => {
    if (failSave && ++saves === 1) throw new Error('disk unavailable')
  } }
  const controller = new FlightBrowser({ BrowserWindow: null, sessionStore: store })
  controller.window = () => ({ webContents: { session: { cookies: jar } }, loadURL: async () => {} })
  controller.loginWindows.set('ctrip', {
    status: 'pending', isolated: true, baseline: new Map(), expires: Date.now() + 10000,
    win: { isDestroyed: () => destroyed, destroy: () => { destroyed = true }, webContents: {
      getURL: () => 'https://www.ctrip.com/', session: { cookies: { get: async () => [cookie('new-session')] } },
    } },
  })
  controller.prepared.set('ctrip', { total: '800' })
  controller.prepared.set('qunar', { total: '700' })
  return { controller, store, current: () => current, destroyed: () => destroyed }
}

test('confirmed QR keeps the previous session until platform tasks have been paused and commit requested', async () => {
  const f = fixture()
  assert.deepEqual(await f.controller.loginStatus('ctrip'), { status: 'confirmed' })
  assert.equal(f.current()[0].value, 'previous-session')
  assert.equal(f.destroyed(), false)
  assert.deepEqual(await f.controller.commitLogin('ctrip'), { status: 'authenticated' })
  assert.equal(f.current()[0].value, 'new-session')
  assert.equal(f.destroyed(), true)
  assert.equal(f.controller.prepared.has('ctrip'), false)
  assert.equal(f.controller.prepared.has('qunar'), true)
  assert.equal(f.store.authentication.get('qunar'), true)
})

test('failed persistence restores the previous session and leaves the other platform intact', async () => {
  const f = fixture(true)
  await f.controller.loginStatus('ctrip')
  await assert.rejects(f.controller.commitLogin('ctrip'), /disk unavailable/)
  assert.equal(f.current()[0].value, 'previous-session')
  assert.equal(f.store.authentication.get('ctrip'), true)
  assert.equal(f.store.authentication.get('qunar'), true)
})
