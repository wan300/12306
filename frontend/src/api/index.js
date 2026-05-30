import axios from 'axios'

const ACCESS_TOKEN_KEY = 'accessToken'

const getStoredAccessToken = () => {
  if (typeof window === 'undefined') return ''
  return localStorage.getItem(ACCESS_TOKEN_KEY) || ''
}

const setStoredAccessToken = (token) => {
  if (typeof window === 'undefined') return
  if (token) {
    localStorage.setItem(ACCESS_TOKEN_KEY, token)
  } else {
    localStorage.removeItem(ACCESS_TOKEN_KEY)
  }
}

// 根据环境确定API基础URL
// 开发模式使用Vite代理，生产模式直接访问后端
const getBaseURL = () => {
  // 检查是否在Electron生产环境中
  if (typeof window !== 'undefined' && window.electronAPI && !import.meta.env.DEV) {
    // Electron生产模式：使用主进程传递的后端地址，避免端口冲突导致 404
    const backendUrl = window.electronAPI.getBackendUrl?.()
    return backendUrl || 'http://127.0.0.1:8000/api/v1'
  }
  // 开发模式或Web模式：使用相对路径（Vite代理）
  return '/api/v1'
}

const normalizeWsBase = (url) => {
  if (!url) return ''
  if (url.startsWith('https://')) return url.replace('https://', 'wss://')
  if (url.startsWith('http://')) return url.replace('http://', 'ws://')
  if (url.startsWith('wss://') || url.startsWith('ws://')) return url
  return url
}

const getTerminalLogsWsUrl = () => {
  const token = getStoredAccessToken()

  if (typeof window !== 'undefined' && window.electronAPI && !import.meta.env.DEV) {
    const backendUrl = window.electronAPI.getBackendUrl?.() || 'http://127.0.0.1:8000/api/v1'
    const wsUrl = `${normalizeWsBase(backendUrl)}/logs/ws/terminal`
    return token ? `${wsUrl}?token=${encodeURIComponent(token)}` : wsUrl
  }

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const wsUrl = `${protocol}//${window.location.host}/api/v1/logs/ws/terminal`
  return token ? `${wsUrl}?token=${encodeURIComponent(token)}` : wsUrl
}

const request = axios.create({
  baseURL: getBaseURL(),
  timeout: 60000  // 增加到60秒，给后端更多处理时间
})

// 请求拦截器：自动注入访问令牌
request.interceptors.request.use((config) => {
  const token = getStoredAccessToken()
  if (token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器
request.interceptors.response.use(
  response => response.data,
  error => {
    if (error.response?.status === 401) {
      setStoredAccessToken('')
      if (typeof window !== 'undefined') {
        localStorage.removeItem('currentUserId')
        localStorage.removeItem('currentUser')
        if (window.location.hash !== '#/login') {
          window.location.hash = '#/login'
        }
      }
    }

    const message = error.response?.data?.detail || error.message || '请求失败'
    console.error('API Error:', message)
    return Promise.reject(new Error(message))
  }
)

export default {
  setAccessToken(token) {
    setStoredAccessToken(token)
  },

  clearAccessToken() {
    setStoredAccessToken('')
  },

  getAccessToken() {
    return getStoredAccessToken()
  },

  // ========== 用户相关 ==========

  getCurrentUser() {
    return request.get('/auth/me')
  },

  createLoginQRCode() {
    return request.post('/auth/login/qrcode')
  },

  checkLoginQRCodeStatus(challengeId) {
    return request.get(`/auth/login/qrcode/${challengeId}/status`)
  },
  
  logout() {
    return request.post('/auth/logout')
  },
  
  getPassengers() {
    return request.get('/users/me/passengers')
  },
  
  // ========== 查票相关 ==========
  queryTickets(params) {
    return request.get('/trains/query', { params })
  },
  
  searchStations(keyword) {
    return request.get('/trains/stations/search', { params: { keyword } })
  },
  
  // ========== 任务相关 ==========
  getTasks(params) {
    return request.get('/tasks', { params })
  },
  
  getTask(taskId) {
    return request.get(`/tasks/${taskId}`)
  },
  
  createTask(data) {
    return request.post('/tasks', data)
  },
  
  updateTask(taskId, data) {
    return request.put(`/tasks/${taskId}`, data)
  },
  
  deleteTask(taskId) {
    return request.delete(`/tasks/${taskId}`)
  },
  
  startTask(taskId) {
    return request.post(`/tasks/${taskId}/start`)
  },
  
  stopTask(taskId) {
    return request.post(`/tasks/${taskId}/stop`)
  },
  
  cancelTask(taskId) {
    return request.post(`/tasks/${taskId}/cancel`)
  },
  
  getTaskLogs(taskId, params) {
    return request.get(`/tasks/${taskId}/logs`, { params })
  },

  // ========== 通知配置 ==========
  getNotificationConfig() {
    return request.get('/config/notification')
  },

  updateNotificationConfig(data) {
    return request.post('/config/notification', data)
  },

  testNotification(data) {
    return request.post('/config/notification/test', data)
  },

  getTerminalLogsWsUrl
}
