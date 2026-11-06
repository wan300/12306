const fs = require('node:fs/promises')
const path = require('node:path')

const PROVIDERS = new Set(['ctrip', 'qunar'])

function cookieDetails(provider, cookie) {
  const host = cookie.domain?.replace(/^\./, '')
  const root = provider === 'ctrip' ? 'ctrip.com' : 'qunar.com'
  if (!host || host !== root && !host.endsWith('.' + root)) return null
  if (cookie.expirationDate && cookie.expirationDate <= Date.now() / 1000) return null
  const details = {
    url: `${cookie.secure ? 'https' : 'http'}://${host}${cookie.path || '/'}`,
    name: cookie.name, value: cookie.value, path: cookie.path || '/',
    secure: cookie.secure, httpOnly: cookie.httpOnly, sameSite: cookie.sameSite || 'unspecified',
  }
  if (!cookie.hostOnly) details.domain = cookie.domain
  if (cookie.expirationDate) details.expirationDate = cookie.expirationDate
  return details
}

class FlightSessionStore {
  constructor({ directory, safeStorage }) {
    this.directory = directory
    this.safeStorage = safeStorage
    this.sessions = new Map()
    this.pending = new Map()
    this.timers = new Map()
    this.listeners = new Map()
    this.authentication = new Map()
  }

  file(provider) {
    if (!PROVIDERS.has(provider)) throw new Error('Invalid flight provider')
    return path.join(this.directory, provider + '.bin')
  }

  async restore(provider, session) {
    let encrypted
    try { encrypted = await fs.readFile(this.file(provider)) }
    catch (error) { if (error.code === 'ENOENT') return false; throw error }
    const stored = JSON.parse(this.safeStorage.decryptString(encrypted))
    if (stored.version !== 1 || stored.provider !== provider || !Array.isArray(stored.cookies)) {
      throw new Error('Invalid flight session')
    }
    if (typeof stored.authenticated === 'boolean') this.authentication.set(provider, stored.authenticated)
    const existing = await session.cookies.get({})
    const present = new Set(existing.map(c => [c.domain, c.path, c.name].join('|')))
    for (const cookie of stored.cookies) {
      const details = cookieDetails(provider, cookie)
      if (details && !present.has([cookie.domain, cookie.path, cookie.name].join('|'))) {
        await session.cookies.set(details)
      }
    }
    await session.cookies.flushStore()
    return true
  }

  watch(provider, session) {
    this.file(provider)
    this.sessions.set(provider, session)
    const changed = () => {
      clearTimeout(this.timers.get(provider))
      this.timers.set(provider, setTimeout(() => {
        this.save(provider).catch(() => console.error('[Flights] Session save failed:', provider))
      }, 250))
    }
    session.cookies.on('changed', changed)
    this.listeners.set(provider, changed)
  }

  save(provider) {
    const file = this.file(provider)
    const session = this.sessions.get(provider)
    if (!session) return Promise.resolve()
    clearTimeout(this.timers.get(provider))
    const previous = this.pending.get(provider) || Promise.resolve()
    const saving = previous.catch(() => {}).then(async () => {
      if (!this.safeStorage.isEncryptionAvailable()) throw new Error('Session encryption unavailable')
      const cookies = (await session.cookies.get({})).filter(c => cookieDetails(provider, c))
      const encrypted = this.safeStorage.encryptString(JSON.stringify({ version: 1, provider, cookies, authenticated: this.authentication.get(provider) }))
      await fs.mkdir(this.directory, { recursive: true })
      await fs.writeFile(file + '.tmp', encrypted, { mode: 0o600 })
      await fs.rename(file + '.tmp', file)
      await session.cookies.flushStore()
    })
    this.pending.set(provider, saving)
    return saving
  }

  async close() {
    for (const [provider, session] of this.sessions) {
      clearTimeout(this.timers.get(provider))
      session.cookies.removeListener('changed', this.listeners.get(provider))
    }
    await Promise.all([...this.sessions.keys()].map(provider => this.save(provider)))
  }
}

module.exports = { FlightSessionStore, cookieDetails }
