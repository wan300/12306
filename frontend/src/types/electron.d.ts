// 类型声明：Electron API
// 为了让 TypeScript/IDE 能够识别 window.electronAPI

interface ElectronAPI {
  getVersion: () => Promise<string>
  getAppPath: () => Promise<string>
  platform: string
  isElectron: boolean
  getBackendUrl: () => string | null
  flights: {
    request: (method: string, route: string, body?: unknown) => Promise<{ success: boolean; data?: any; message?: string; error_code?: string }>
  }
  workspace: {
    request: (method: string, route: string, body?: unknown) => Promise<{ success: boolean; data?: any; message?: string }>
  }
}

declare global {
  interface Window {
    electronAPI?: ElectronAPI
  }
}

export {}
