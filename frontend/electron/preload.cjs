const { contextBridge, ipcRenderer } = require('electron')

// 提取后端 URL（通过 BrowserWindow additionalArguments 传入）
const getBackendUrlSync = () => {
  if (process.env.BACKEND_URL) {
    return process.env.BACKEND_URL
  }

  const arg = process.argv.find((item) => item.startsWith('--backend-url='))
  if (arg) {
    return arg.replace('--backend-url=', '')
  }
  return null
}

// 暴露安全的API给渲染进程
contextBridge.exposeInMainWorld('electronAPI', {
  // 获取应用版本
  getVersion: () => ipcRenderer.invoke('get-app-version'),
  
  // 获取应用路径
  getAppPath: () => ipcRenderer.invoke('get-app-path'),
  
  // 平台信息
  platform: process.platform,
  
  // 判断是否在Electron环境中
  isElectron: true,

  // 后端 URL（优先同步获取，兜底使用异步 IPC）
  getBackendUrl: () => getBackendUrlSync(),
})
