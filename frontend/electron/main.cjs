const { app, BrowserWindow, ipcMain } = require('electron')
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

// 获取可用端口，优先尝试首选端口，失败则使用系统分配的随机端口
const findAvailablePort = (preferredPort) => {
  return new Promise((resolvePort) => {
    const server = net.createServer()

    server.once('error', () => {
      const fallback = net.createServer()
      fallback.listen(0, () => {
        const freePort = fallback.address().port
        fallback.close(() => resolvePort(freePort))
      })
    })

    server.listen(preferredPort, () => {
      const freePort = server.address().port
      server.close(() => resolvePort(freePort))
    })
  })
}

// 判断是否为开发环境
const isDev = process.env.NODE_ENV === 'development' || !app.isPackaged

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
  if (isDev) {
    // 开发模式：默认后端端口 8000，并做轻量探活提示
    console.log('[Backend] Development mode - checking backend on port 8000')
    backendPort = 8000
    backendUrl = `http://127.0.0.1:${backendPort}/api/v1`
    process.env.BACKEND_URL = backendUrl

    const ready = await waitForBackendReady(backendPort, 5000, 500)
    if (!ready) {
      console.warn('[Backend] Backend is not ready on port 8000. Please start backend manually.')
    }
    return
  }

  const backendPath = getBackendPath()
  if (!backendPath) {
    throw new Error('Backend executable not found')
  }

  console.log('[Backend] Starting backend server...')

  backendPort = await findAvailablePort(8000)
  backendUrl = `http://127.0.0.1:${backendPort}/api/v1`
  process.env.BACKEND_URL = backendUrl
  console.log(`[Backend] Using port: ${backendPort}`)

  // 设置工作目录为后端目录
  const backendDir = path.dirname(backendPath)

  backendProcess = spawn(backendPath, [], {
    cwd: backendDir,
    stdio: ['pipe', 'pipe', 'pipe'],
    windowsHide: true, // Windows下隐藏控制台窗口
    env: {
      ...process.env,
      BACKEND_PORT: backendPort.toString(),
      BACKEND_HOST: '0.0.0.0',
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
    title: '12306 自动抢票助手',
    icon: path.join(__dirname, 'icon.ico'),
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
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
    mainWindow.loadURL('http://localhost:5173')
    mainWindow.webContents.openDevTools()
  } else {
    // 生产模式：加载打包后的文件
    mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
  }
  
  // 页面加载完成后显示窗口
  mainWindow.once('ready-to-show', () => {
    mainWindow.show()
  })
  
  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

// 应用准备就绪
app.whenReady().then(async () => {
  try {
    // 先启动后端
    await startBackend()
    
    // 再创建窗口
    createWindow()
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
app.on('before-quit', () => {
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
