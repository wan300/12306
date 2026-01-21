// 类型声明：Electron API
// 为了让 TypeScript/IDE 能够识别 window.electronAPI

interface ElectronAPI {
  getVersion: () => Promise<string>
  getAppPath: () => Promise<string>
  platform: string
  isElectron: boolean
}

declare global {
  interface Window {
    electronAPI?: ElectronAPI
  }
}

export {}
