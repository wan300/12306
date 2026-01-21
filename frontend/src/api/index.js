import axios from 'axios'

// 根据环境确定API基础URL
// 开发模式使用Vite代理，生产模式直接访问后端
const getBaseURL = () => {
  // 检查是否在Electron生产环境中
  if (typeof window !== 'undefined' && window.electronAPI && !import.meta.env.DEV) {
    // Electron生产模式：直接访问后端服务
    return 'http://localhost:8000/api/v1'
  }
  // 开发模式或Web模式：使用相对路径（Vite代理）
  return '/api/v1'
}

const request = axios.create({
  baseURL: getBaseURL(),
  timeout: 60000  // 增加到60秒，给后端更多处理时间
})

// 响应拦截器
request.interceptors.response.use(
  response => response.data,
  error => {
    const message = error.response?.data?.detail || error.message || '请求失败'
    console.error('API Error:', message)
    return Promise.reject(new Error(message))
  }
)

export default {
  // ========== 用户相关 ==========
  createUser(data) {
    return request.post('/auth/users', data)
  },
  
  getUsers() {
    return request.get('/auth/users')
  },
  
  getUser(userId) {
    return request.get(`/auth/users/${userId}`)
  },
  
  checkLoginStatus(userId) {
    return request.get(`/auth/status/${userId}`)
  },
  
  getQRCode(userId) {
    return request.post(`/auth/qrcode/${userId}`)
  },
  
  checkQRStatus(userId, uuid) {
    return request.get(`/auth/qrcode/${userId}/status`, { params: { uuid } })
  },
  
  logout(userId) {
    return request.post(`/auth/logout/${userId}`)
  },
  
  deleteUser(userId) {
    return request.delete(`/auth/users/${userId}`)
  },
  
  getPassengers(userId) {
    return request.get(`/users/${userId}/passengers`)
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
  
  createTask(data, userId) {
    return request.post('/tasks', data, { params: { user_id: userId } })
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
  }
}
