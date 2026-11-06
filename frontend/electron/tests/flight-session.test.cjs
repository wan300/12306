const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const os = require('node:os')
const path = require('node:path')
const crypto = require('node:crypto')
const { EventEmitter } = require('node:events')
const { FlightSessionStore } = require('../flight-session.cjs')

function encryption() {
  const key = crypto.randomBytes(32)
  return {
    isEncryptionAvailable: () => true,
    encryptString(value) {
      const iv = crypto.randomBytes(12)
      const cipher = crypto.createCipheriv('aes-256-gcm', key, iv)
      const bytes = Buffer.concat([cipher.update(value, 'utf8'), cipher.final()])
      return Buffer.concat([iv, cipher.getAuthTag(), bytes])
    },
    decryptString(bytes) {
      const cipher = crypto.createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12))
      cipher.setAuthTag(bytes.subarray(12, 28))
      return Buffer.concat([cipher.update(bytes.subarray(28)), cipher.final()]).toString('utf8')
    },
  }
}

function session(cookies = []) {
  const jar = new EventEmitter()
  jar.values = cookies
  jar.restored = []
  jar.get = async () => jar.values
  jar.set = async value => { jar.restored.push(value) }
  jar.flushStore = async () => {}
  return { cookies: jar }
}

test('both providers restore encrypted session cookies with their original scope and lifetime', async t => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'flight-session-test-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  const safeStorage = encryption()
  for (const provider of ['ctrip', 'qunar']) {
    const host = provider + '.com'
    const source = session([
      { domain: '.' + host, path: '/', name: 'auth', value: 'fixture-session-secret', secure: true, httpOnly: true, sameSite: 'no_restriction', session: true, hostOnly: false },
      { domain: 'user.' + host, path: '/account', name: 'host', value: 'host-secret', secure: true, hostOnly: true },
      { domain: '.' + host, path: '/', name: 'expired', value: 'old', expirationDate: Date.now() / 1000 - 10 },
      { domain: '.unrelated.invalid', path: '/', name: 'third-party', value: 'excluded' },
    ])
    const store = new FlightSessionStore({ directory, safeStorage })
    store.watch(provider, source)
    await store.close()
    const disk = await fs.readFile(path.join(directory, provider + '.bin'))
    assert.ok(!disk.includes('fixture-session-secret'))
    const restored = session()
    const restarted = new FlightSessionStore({ directory, safeStorage })
    assert.equal(await restarted.restore(provider, restored), true)
    assert.equal(restored.cookies.restored.length, 2)
    assert.equal(restored.cookies.restored[0].domain, '.' + host)
    assert.equal(restored.cookies.restored[0].httpOnly, true)
    assert.equal(restored.cookies.restored[0].sameSite, 'no_restriction')
    assert.equal(restored.cookies.restored[0].expirationDate, undefined)
    assert.equal(restored.cookies.restored[1].domain, undefined)
    assert.equal(restored.cookies.restored[1].url, 'https://user.' + host + '/account')
  }
})

test('cookie updates and logout replace the snapshot and do not resurrect removed authentication', async t => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'flight-session-test-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  const safeStorage = encryption()
  const source = session([{ domain: '.qunar.com', path: '/', name: 'auth', value: 'old' }])
  const store = new FlightSessionStore({ directory, safeStorage })
  store.watch('qunar', source)
  await store.save('qunar')
  source.cookies.values = []
  source.cookies.emit('changed')
  await store.close()
  const restarted = session()
  await new FlightSessionStore({ directory, safeStorage }).restore('qunar', restarted)
  assert.deepEqual(restarted.cookies.restored, [])
})

test('restore preserves newer browser cookies and rejects decryption with another key', async t => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'flight-session-test-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  const safeStorage = encryption()
  const source = session([{ domain: '.ctrip.com', path: '/', name: 'auth', value: 'old' }])
  const store = new FlightSessionStore({ directory, safeStorage })
  store.watch('ctrip', source)
  await store.close()
  const current = session([{ domain: '.ctrip.com', path: '/', name: 'auth', value: 'new' }])
  await new FlightSessionStore({ directory, safeStorage }).restore('ctrip', current)
  assert.deepEqual(current.cookies.restored, [])
  await assert.rejects(new FlightSessionStore({ directory, safeStorage: encryption() }).restore('ctrip', session()))
})

test('flight sorting uses numeric prices and departure times without changing selection data', async () => {
  const { sortFlights } = await import('../../src/utils/flight-sort.js')
  const flights = [
    { flight_number: 'B', departure_time: '12:00', base_price: '1000.00' },
    { flight_number: 'A', departure_time: '07:30', base_price: '90.00' },
    { flight_number: 'C', departure_time: '09:00', base_price: '500.00' },
    { flight_number: 'D', departure_time: null, base_price: null },
  ]
  const ids = order => sortFlights(flights, order).map(row => row.flight_number).join('')
  assert.equal(ids('time_asc'), 'ACBD')
  assert.equal(ids('time_desc'), 'BCAD')
  assert.equal(ids('price_asc'), 'ACBD')
  assert.equal(ids('price_desc'), 'BCAD')
  assert.equal(flights.map(row => row.flight_number).join(''), 'BACD')
})
