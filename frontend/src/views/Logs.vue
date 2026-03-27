<template>
  <div class="logs-page">
    <el-card class="logs-card">
      <template #header>
        <div class="card-header">
          <div class="header-title">
            <span>后端终端日志</span>
            <el-tag size="small" :type="statusTagType">{{ statusText }}</el-tag>
          </div>
          <div class="header-actions">
            <el-checkbox-group v-model="enabledStreams" size="small">
              <el-checkbox-button label="stdout" />
              <el-checkbox-button label="stderr" />
            </el-checkbox-group>
            <el-button size="small" @click="toggleAutoScroll">
              {{ autoScroll ? '暂停跟随' : '恢复跟随' }}
            </el-button>
            <el-button size="small" @click="reconnectNow">重新连接</el-button>
            <el-button size="small" type="danger" plain @click="clearLogs">清屏</el-button>
          </div>
        </div>
      </template>

      <div class="meta-row">
        <span>总计 {{ logs.length }} 条，当前显示 {{ filteredLogs.length }} 条</span>
        <span>每 2 秒自动重连，支持实时追加</span>
      </div>

      <div ref="logContainer" class="log-container">
        <div v-if="filteredLogs.length === 0" class="empty-text">暂无日志输出</div>
        <div
          v-for="item in filteredLogs"
          :key="item.seq"
          class="log-line"
          :class="item.stream"
        >
          <span class="log-time">[{{ formatTime(item.timestamp) }}]</span>
          <span class="log-stream">[{{ item.stream }}]</span>
          <span class="log-text">{{ item.text }}</span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import api from '../api'

const logs = ref([])
const enabledStreams = ref(['stdout', 'stderr'])
const autoScroll = ref(true)
const logContainer = ref(null)

const connectionState = ref('connecting')
const maxLogs = 2000

let ws = null
let reconnectTimer = null
let shouldReconnect = true

const filteredLogs = computed(() => {
  return logs.value.filter(item => enabledStreams.value.includes(item.stream))
})

const statusText = computed(() => {
  if (connectionState.value === 'connected') return '已连接'
  if (connectionState.value === 'reconnecting') return '重连中'
  return '未连接'
})

const statusTagType = computed(() => {
  if (connectionState.value === 'connected') return 'success'
  if (connectionState.value === 'reconnecting') return 'warning'
  return 'info'
})

const normalizeLog = (raw) => {
  return {
    seq: raw.seq ?? Date.now() + Math.random(),
    stream: raw.stream === 'stderr' ? 'stderr' : 'stdout',
    text: String(raw.text ?? ''),
    timestamp: raw.timestamp || new Date().toISOString(),
    source: raw.source || 'backend'
  }
}

const appendLogs = async (items) => {
  if (!Array.isArray(items) || items.length === 0) return

  const normalized = items
    .map(normalizeLog)
    .filter(item => item.text.trim() !== '')

  if (normalized.length === 0) return

  logs.value.push(...normalized)

  if (logs.value.length > maxLogs) {
    logs.value.splice(0, logs.value.length - maxLogs)
  }

  if (autoScroll.value) {
    await scrollToBottom()
  }
}

const handleWsMessage = async (rawData) => {
  let payload
  try {
    payload = JSON.parse(rawData)
  } catch {
    return
  }

  if (payload.type === 'snapshot' && Array.isArray(payload.logs)) {
    logs.value = []
    await appendLogs(payload.logs)
    return
  }

  if (payload.type === 'log' && payload.log) {
    await appendLogs([payload.log])
  }
}

const connectWs = () => {
  clearReconnectTimer()

  if (connectionState.value !== 'connected') {
    connectionState.value = logs.value.length > 0 ? 'reconnecting' : 'connecting'
  }

  ws = new WebSocket(api.getTerminalLogsWsUrl())

  ws.onopen = () => {
    connectionState.value = 'connected'
  }

  ws.onmessage = (event) => {
    handleWsMessage(event.data)
  }

  ws.onerror = () => {
    connectionState.value = 'reconnecting'
  }

  ws.onclose = () => {
    ws = null
    if (!shouldReconnect) {
      connectionState.value = 'disconnected'
      return
    }

    connectionState.value = 'reconnecting'
    scheduleReconnect()
  }
}

const scheduleReconnect = () => {
  clearReconnectTimer()
  reconnectTimer = setTimeout(() => {
    connectWs()
  }, 2000)
}

const clearReconnectTimer = () => {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
}

const reconnectNow = () => {
  shouldReconnect = false
  clearReconnectTimer()

  if (ws) {
    ws.close()
    ws = null
  }

  shouldReconnect = true
  connectWs()
}

const clearLogs = () => {
  logs.value = []
}

const toggleAutoScroll = () => {
  autoScroll.value = !autoScroll.value
  if (autoScroll.value) {
    scrollToBottom()
  }
}

const scrollToBottom = async () => {
  await nextTick()
  const el = logContainer.value
  if (!el) return
  el.scrollTop = el.scrollHeight
}

const formatTime = (value) => {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return date.toLocaleString('zh-CN', { hour12: false })
}

watch(filteredLogs, async () => {
  if (autoScroll.value) {
    await scrollToBottom()
  }
})

onMounted(() => {
  shouldReconnect = true
  connectWs()
})

onUnmounted(() => {
  shouldReconnect = false
  clearReconnectTimer()
  if (ws) {
    ws.close()
    ws = null
  }
})
</script>

<style scoped>
.logs-page {
  height: calc(100vh - 120px);
}

.logs-card {
  height: 100%;
}

:deep(.logs-card .el-card__body) {
  height: calc(100% - 56px);
  display: flex;
  flex-direction: column;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.meta-row {
  font-size: 12px;
  color: #606266;
  margin-bottom: 10px;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.log-container {
  flex: 1;
  overflow-y: auto;
  background: #111827;
  color: #e5e7eb;
  border-radius: 8px;
  padding: 12px;
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
}

.empty-text {
  color: #9ca3af;
}

.log-line {
  white-space: pre-wrap;
  word-break: break-word;
  margin-bottom: 6px;
}

.log-line.stderr .log-text {
  color: #fca5a5;
}

.log-line.stdout .log-text {
  color: #d1d5db;
}

.log-time {
  color: #93c5fd;
  margin-right: 8px;
}

.log-stream {
  color: #fcd34d;
  margin-right: 8px;
}

@media (max-width: 1024px) {
  .logs-page {
    height: auto;
  }

  .logs-card {
    min-height: 70vh;
  }

  .card-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .header-actions {
    width: 100%;
    justify-content: flex-start;
  }
}
</style>
