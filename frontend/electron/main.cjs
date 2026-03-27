const { app, BrowserWindow, ipcMain } = require('electron')
const path = require('path')
const { spawn } = require('child_process')
const fs = require('fs')
const net = require('net')

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
    // 备用路径
    path.join(__dirname, '..', '..', 'backend', 'dist', '12306-backend', '12306-backend.exe'),
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
  return new Promise(async (resolve, reject) => {
    if (isDev) {
      // 开发模式：假设后端已经在运行
      console.log('[Backend] Development mode - assuming backend is running on port 8000')
      backendPort = 8000
      backendUrl = `http://127.0.0.1:${backendPort}/api/v1`
      process.env.BACKEND_URL = backendUrl
      resolve()
      return
    }
    
    const backendPath = getBackendPath()
    if (!backendPath) {
      reject(new Error('Backend executable not found'))
      return
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
      const output = data.toString()
      console.log('[Backend]', output)
      
      // 检测服务是否启动成功
      if (output.includes('服务启动成功') || output.includes('Uvicorn running')) {
        resolve()
      }
    })
    
    backendProcess.stderr.on('data', (data) => {
      console.error('[Backend Error]', data.toString())
    })
    
    backendProcess.on('error', (err) => {
      console.error('[Backend] Failed to start:', err)
      reject(err)
    })
    
    backendProcess.on('exit', (code) => {
      console.log('[Backend] Process exited with code:', code)
      backendProcess = null
    })
    
    // 设置超时，如果10秒内没有启动成功也继续
    setTimeout(() => {
      resolve()
    }, 10000)
  })
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
