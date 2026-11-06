const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, Notification, session, safeStorage } = require('electron')
const crypto = require('crypto')
const { FlightBrowser, startFlightBrowserServer } = require('./flight-browser.cjs')
const { FlightSessionStore } = require('./flight-session.cjs')

// A detached desktop process can outlive the terminal reading its logs.
for (const stream of [process.stdout, process.stderr]) {
  stream?.on('error', (error) => {
    if (error.code !== 'EPIPE') throw error
  })
}
const path = require('path')
const { spawn } = require('child_process')
const fs = require('fs')
const net = require('net')
const http = require('http')

// 保存后端进程引用
let backendProcess = null
let backendPort = 8000
let backendUrl = null
let mainWindow = null
let flightBrowser = null
let flightSessionStore = null
let savingFlightSessions = false
let flightSessionsSaved = false
let flightServer = null
let tray = null
let quitting = false
const flightToken = crypto.randomBytes(32).toString('hex')

// 获取可用端口，优先尝试首选端口，失败则使用系统分配的随机端口
const findAvailablePort = (preferredPort) => {
  return new Promise((resolvePort) => {
    const server = net.createServer()

    server.once('error', () => {
      const fallback = net.createServer()
      fallback.listen(0, '127.0.0.1', () => {
        const freePort = fallback.address().port
        fallback.close(() => resolvePort(freePort))
      })
    })

    server.listen(preferredPort, '127.0.0.1', () => {
      const freePort = server.address().port
      server.close(() => resolvePort(freePort))
    })
  })
}

// 判断是否为开发环境
const isDev = process.env.NODE_ENV === 'development' || !app.isPackaged
const devOrigin = `http://localhost:${Number(process.env.FLIGHT_DEV_PORT) || 5173}`

const delay = (ms) => new Promise((resolveDelay) => setTimeout(resolveDelay, ms))

const probeBackend = (port, endpoint = '/health', timeout = 1200) => {
  return new Promise((resolveProbe) => {
    const req = http.get(
      {
        hostname: '127.0.0.1',
        port,
        path: endpoint,
        timeout,
      },
      (res) => {
        res.resume()
        resolveProbe(res.statusCode >= 200 && res.statusCode < 500)
      }
    )

    req.on('error', () => resolveProbe(false))
    req.on('timeout', () => {
      req.destroy()
      resolveProbe(false)
    })
  })
}

const waitForBackendReady = async (port, timeoutMs = 30000, intervalMs = 500) => {
  const startAt = Date.now()

  while (Date.now() - startAt < timeoutMs) {
    const ready = await probeBackend(port)
    if (ready) {
      return true
    }
    await delay(intervalMs)
  }

  return false
}

// 获取后端可执行文件路径
function getBackendPath() {
  if (isDev) {
    // 开发模式：使用Python运行
    return null
  }
  
  // 生产模式：查找打包的后端可执行文件
  const possiblePaths = [
    // Windows
    path.join(process.resourcesPath, 'backend', '12306-backend.exe'),
    path.join(app.getAppPath(), '..', 'backend', '12306-backend.exe'),
  ]
  
  for (const p of possiblePaths) {
    if (fs.existsSync(p)) {
      console.log('Found backend at:', p)
      return p
    }
  }
  
  console.error('Backend executable not found. Searched paths:', possiblePaths)
  return null
}

// 启动后端服务
async function startBackend() {
  // A managed process receives the per-launch local capability. An unrelated
  // manually started server must never inherit desktop account access.
  const backendPath = isDev ? (process.env.PYTHON_PATH || 'python') : getBackendPath()
  if (!backendPath) {
    throw new Error('Backend executable not found')
  }

  console.log('[Backend] Starting backend server...')

  backendPort = await findAvailablePort(8000)
  backendUrl = `http://127.0.0.1:${backendPort}/api/v1`
  process.env.BACKEND_URL = backendUrl
  console.log(`[Backend] Using port: ${backendPort}`)

  // 设置工作目录为后端目录
  const backendDir = isDev ? path.resolve(__dirname, '../../backend') : path.dirname(backendPath)
  const dataDir = process.env.APP_DATA_DIR || (isDev ? path.join(backendDir, 'data') : path.join(app.getPath('userData'), 'backend-data'))
  fs.mkdirSync(dataDir, { recursive: true })
  const bundledAssets = path.join(backendDir, 'data', 'assets')
  const localAssets = path.join(dataDir, 'assets')
  if (path.resolve(bundledAssets) !== path.resolve(localAssets) && fs.existsSync(bundledAssets)) {
    fs.mkdirSync(localAssets, { recursive: true })
    fs.cpSync(bundledAssets, localAssets, { recursive: true, force: false })
  }

  backendProcess = spawn(backendPath, isDev ? [path.join(backendDir, 'run_server.py')] : [], {
    cwd: backendDir,
    stdio: ['pipe', 'pipe', 'pipe'],
    windowsHide: true, // Windows下隐藏控制台窗口
    env: {
      ...process.env,
      APP_DATA_DIR: dataDir,
      DESKTOP_UI_ORIGIN: isDev ? devOrigin : '',
      BACKEND_PORT: backendPort.toString(),
      BACKEND_HOST: '127.0.0.1',
      DEBUG: 'false',
      PYTHONIOENCODING: 'utf-8',
      DESKTOP_FLIGHT_TOKEN: flightToken,
      FLIGHT_BROWSER_URL: flightServer.url,
    },
  })

  backendProcess.stdout.on('data', (data) => {
    console.log('[Backend]', data.toString())
  })

  backendProcess.stderr.on('data', (data) => {
    console.error('[Backend Error]', data.toString())
  })

  backendProcess.on('error', (err) => {
    console.error('[Backend] Failed to start:', err)
  })

  backendProcess.on('exit', (code) => {
    console.log('[Backend] Process exited with code:', code)
    backendProcess = null
  })

  const startupResult = await Promise.race([
    waitForBackendReady(backendPort, 30000, 500).then((ready) =>
      ready ? { type: 'ready' } : { type: 'timeout' }
    ),
    new Promise((resolveStartup) => {
      backendProcess.once('exit', (code) => resolveStartup({ type: 'exit', code }))
    }),
  ])

  if (startupResult.type === 'ready') {
    console.log(`[Backend] Ready at ${backendUrl}`)
    return
  }

  if (startupResult.type === 'exit') {
    throw new Error(`[Backend] Exited before ready, code: ${startupResult.code}`)
  }

  throw new Error('[Backend] Startup timeout after 30s')
}

// 停止后端服务
function stopBackend() {
  if (backendProcess) {
    console.log('[Backend] Stopping backend server...')
    
    if (process.platform === 'win32') {
      // Windows下需要强制终止进程树
      spawn('taskkill', ['/pid', backendProcess.pid, '/f', '/t'])
    } else {
      backendProcess.kill('SIGTERM')
    }
    
    backendProcess = null
  }
}

// 创建主窗口
function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 1024,
    minHeight: 700,
    title: '票务助手',
    icon: path.join(__dirname, 'icon.ico'),
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      backgroundThrottling: false,
      preload: path.join(__dirname, 'preload.cjs'),
      additionalArguments: backendUrl ? [`--backend-url=${backendUrl}`] : [],
    },
    show: false, // 先隐藏，等加载完成再显示
  })
  
  // 移除菜单栏（生产环境）
  if (!isDev) {
    mainWindow.setMenu(null)
  }
  
  // 加载页面
  if (isDev) {
    // 开发模式：加载Vite开发服务器
    mainWindow.loadURL(devOrigin + (process.argv.includes('--flights') ? '/#/flights' : ''))
    if (process.env.ELECTRON_DEBUG === '1') mainWindow.webContents.openDevTools()
  } else {
    // 生产模式：加载打包后的文件
    mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'), process.argv.includes('--flights') ? { hash: '/flights' } : {})
  }
  
  // 页面加载完成后显示窗口
  mainWindow.once('ready-to-show', () => {
    mainWindow.show()
  })
  
  mainWindow.on('closed', () => {
    mainWindow = null
  })
  mainWindow.on('close', event => {
    if (!quitting && tray) { event.preventDefault(); mainWindow.hide() }
  })
}

function createTray() {
  const icon = nativeImage.createFromDataURL('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=').resize({ width: 16, height: 16 })
  tray = new Tray(icon)
  tray.setToolTip('票务助手 · 机票与火车票')
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '打开助手', click: () => mainWindow?.show() },
    { type: 'separator' },
    { label: '退出并停止监控', click: () => { quitting = true; app.quit() } },
  ]))
  tray.on('double-click', () => mainWindow?.show())
}

// 应用准备就绪
app.whenReady().then(async () => {
  try {
    flightSessionStore = new FlightSessionStore({ directory: path.join(app.getPath('userData'), 'flight-sessions'), safeStorage })
    for (const provider of ['ctrip', 'qunar']) {
      const providerSession = session.fromPartition(`persist:personal-flight-${provider}`)
      try { await flightSessionStore.restore(provider, providerSession) }
      catch { console.error('[Flights] Session restore failed:', provider) }
      flightSessionStore.watch(provider, providerSession)
    }
    flightBrowser = new FlightBrowser({ BrowserWindow, sessionStore: flightSessionStore, onNotify: (title, body) => {
      if (!Notification.isSupported()) return
      const notice = new Notification({ title, body })
      notice.on('click', () => mainWindow?.show())
      notice.show()
    } })
    flightServer = await startFlightBrowserServer(flightBrowser, flightToken)
    // 先启动后端
    await startBackend()
    
    // 再创建窗口
    createWindow()
    createTray()
  } catch (error) {
    console.error('Failed to start application:', error)
    // 即使后端启动失败也尝试创建窗口（开发调试用）
    createWindow()
  }
  
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    }
  })
})

// 所有窗口关闭时
app.on('window-all-closed', () => {
  stopBackend()
  
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

// 应用退出前
app.on('before-quit', (event) => {
  quitting = true
  if (flightSessionStore && !flightSessionsSaved) {
    event.preventDefault()
    if (savingFlightSessions) return
    savingFlightSessions = true
    flightSessionStore.close().catch(() => console.error('[Flights] Session save failed on exit')).finally(() => {
      flightSessionsSaved = true
      app.quit()
    })
    return
  }
  flightBrowser?.close()
  flightServer?.close()
  tray?.destroy()
  stopBackend()
})

// IPC 通信处理
ipcMain.handle('get-app-version', () => {
  return app.getVersion()
})

ipcMain.handle('get-app-path', () => {
  return app.getAppPath()
})

ipcMain.handle('get-backend-url', () => {
  return backendUrl || `http://127.0.0.1:${backendPort}/api/v1`
})

function trustedFlightSender(event) {
  if (!mainWindow || event.sender.id !== mainWindow.webContents.id || event.senderFrame !== mainWindow.webContents.mainFrame) return false
  const senderUrl = event.senderFrame?.url || ''
  return isDev ? senderUrl.startsWith(devOrigin + '/') : senderUrl.startsWith('file://')
}

ipcMain.handle('flights-api', async (event, { method = 'GET', route, body } = {}) => {
  if (!trustedFlightSender(event) ||
      !['GET', 'POST', 'PATCH', 'DELETE'].includes(method) || typeof route !== 'string' ||
      !/^\/flights\/(?:providers(?:\/(?:ctrip|qunar)\/(?:open|session|login\/qr|login\/status))?|search|offers|prepare|orders|monitors(?:\/\d+(?:\/(?:start|pause))?)?)$/.test(route)) {
    return { success: false, error_code: 'INVALID_DESKTOP_REQUEST', message: '桌面请求无效' }
  }
  try {
    const response = await fetch(backendUrl + route, {
      method, headers: { 'Content-Type': 'application/json', 'X-Flight-Token': flightToken },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      signal: AbortSignal.timeout(180000),
    })
    const result = await response.json()
    if (response.status === 422) return { success: false, error_code: 'INVALID_FLIGHT_INPUT', message: '请检查行程、乘机人、日期和含税上限' }
    if (result.detail) return { success: false, error_code: result.detail.error_code || 'FLIGHT_REQUEST_FAILED', message: result.detail.message || '机票请求未完成' }
    return result
  } catch {
    return { success: false, error_code: 'DESKTOP_BACKEND_UNAVAILABLE', message: '桌面后端未连接，请重新打开应用' }
  }
})

ipcMain.handle('workspace-api', async (event, { method, route, body } = {}) => {
  if (!trustedFlightSender(event) || !['GET', 'POST'].includes(method) ||
    !/^\/config\/notification(?:\/test)?$/.test(route || '')) {
    return { success: false, message: '桌面请求无效' }
  }
  try {
    const response = await fetch(backendUrl + route, {
      method, headers: { 'Content-Type': 'application/json', 'X-Flight-Token': flightToken },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      signal: AbortSignal.timeout(180000),
    })
    const data = await response.json()
    return response.ok ? { success: true, data } : { success: false, message: typeof data.detail === 'string' ? data.detail : '通知请求未完成' }
  } catch { return { success: false, message: '桌面后端未连接' } }
})
