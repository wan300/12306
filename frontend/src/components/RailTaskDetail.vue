<template>
  <div class="task-detail-page" v-if="task">
    <el-page-header @back="$router.back()">
      <template #content>
        <span class="text-large font-600 mr-3">{{ task.name }}</span>
        <el-tag :type="getStatusType(task.status)">{{ getStatusText(task.status) }}</el-tag>
      </template>
      <template #extra>
        <el-button-group :class="{'mobile-btn-group': isMobile}">
          <el-button
            v-if="task.status === 'pending' || task.status === 'paused'"
            type="success"
            @click="handleStart"
          >
            启动任务
          </el-button>
          <el-button
            v-if="task.status === 'running'"
            type="warning"
            @click="handleStop"
          >
            暂停任务
          </el-button>
          <el-button
            v-if="task.status === 'running'"
            type="danger"
            @click="handleCancel"
          >
            取消任务
          </el-button>
        </el-button-group>
      </template>
    </el-page-header>

    <el-row :gutter="20" class="task-detail-row">
      <el-col :xs="24" :sm="24" :md="12" class="task-detail-col">
        <el-card class="full-height-card">
          <template #header>任务信息</template>
          <div class="scroll-container">
            <el-descriptions :column="isMobile ? 1 : 2" border>
              <el-descriptions-item label="行程">
                {{ task.from_station }} → {{ task.to_station }}
              </el-descriptions-item>
              <el-descriptions-item label="日期">
                {{ task.train_date }}
              </el-descriptions-item>
              <el-descriptions-item label="车次类型">
                {{ task.train_types || '不限' }}
              </el-descriptions-item>
              <el-descriptions-item label="席别">
                {{ formatSeatTypes(task.seat_types) }}
              </el-descriptions-item>
              <el-descriptions-item label="指定车次">
                {{ task.train_codes || '不限' }}
              </el-descriptions-item>
              <el-descriptions-item label="时间段">
                {{ task.start_time_range || '全天' }}
              </el-descriptions-item>
              <el-descriptions-item label="刷票间隔">
                {{ task.query_interval }} 秒
              </el-descriptions-item>
              <el-descriptions-item label="重试次数">
                {{ task.retry_count }} / {{ task.max_retry_count }}
              </el-descriptions-item>
              <el-descriptions-item label="自动提交" :span="isMobile ? 1 : 2">
                <el-tag :type="task.auto_submit ? 'success' : 'info'">
                  {{ task.auto_submit ? '是' : '否' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="创建时间">
                {{ formatTime(task.created_at) }}
              </el-descriptions-item>
              <el-descriptions-item label="开始时间">
                {{ formatTime(task.started_at) }}
              </el-descriptions-item>
            </el-descriptions>

            <div class="section-divider">乘车人</div>

            <el-table :data="passengers" stripe border size="small">
              <el-table-column prop="passenger_name" label="姓名" min-width="80" />
              <el-table-column prop="passenger_id_no" label="证件号" min-width="160">
                <template #default="{ row }">
                  {{ maskIdNo(row.passenger_id_no) }}
                </template>
              </el-table-column>
              <el-table-column prop="mobile_no" label="手机号" min-width="110" />
            </el-table>

            <div v-if="task.status === 'success'" class="status-alert success-alert">
               <el-icon class="status-icon"><CircleCheckFilled /></el-icon>
               <div class="status-content">
                 <div class="status-title">抢票成功！</div>
                 <div class="status-desc">订单号: {{ task.order_id }}</div>
               </div>
               <div class="status-action">
                 <el-button type="primary" size="small" @click="goToPayment">去支付</el-button>
               </div>
            </div>

            <div v-if="task.status === 'failed'" class="status-alert failed-alert">
               <el-icon class="status-icon"><CircleCloseFilled /></el-icon>
               <div class="status-content">
                 <div class="status-title">任务失败</div>
                 <div class="status-desc">{{ task.result_message }}</div>
               </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :sm="24" :md="12" :class="{'mt-4': isMobile}" class="task-detail-col">
        <el-card class="full-height-card">
          <template #header>
            <div class="card-header">
              <span>运行日志</span>
              <el-button text type="primary" @click="refreshLogs">
                <el-icon><Refresh /></el-icon>
                刷新
              </el-button>
            </div>
          </template>

          <div class="log-container">
            <el-timeline>
              <el-timeline-item
                v-for="log in taskStore.taskLogs"
                :key="log.id"
                :type="getLogType(log.level)"
                :timestamp="formatTime(log.created_at)"
                placement="top"
              >
                <div style="white-space: pre-wrap; word-break: break-all; line-height: 1.5;">{{ log.message }}</div>
              </el-timeline-item>
            </el-timeline>

            <el-empty v-if="taskStore.taskLogs.length === 0" description="暂无日志" />
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>

  <el-empty v-else description="任务不存在" />
  <!-- Force Rebuild -->
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useTaskStore } from '../stores/task'

const route = useRoute()
const router = useRouter()
const taskStore = useTaskStore()
const isMobile = ref(window.innerWidth < 768)

const checkScreenSize = () => {
  isMobile.value = window.innerWidth < 768
}

const task = computed(() => taskStore.currentTask)

const passengers = computed(() => {
  if (!task.value?.passengers) return []
  try {
    return JSON.parse(task.value.passengers)
  } catch {
    return []
  }
})

let refreshTimer = null

const getStatusType = (status) => {
  const types = {
    pending: 'info',
    running: 'warning',
    paused: '',
    success: 'success',
    failed: 'danger',
    cancelled: 'info'
  }
  return types[status] || 'info'
}

const getStatusText = (status) => {
  const texts = {
    pending: '待运行',
    running: '运行中',
    paused: '已暂停',
    success: '成功',
    failed: '失败',
    cancelled: '已取消'
  }
  return texts[status] || status
}

const getLogType = (level) => {
  const types = {
    info: 'primary',
    success: 'success',
    warning: 'warning',
    error: 'danger'
  }
  return types[level] || 'primary'
}

const formatSeatTypes = (types) => {
  if (!types) return ''
  const map = {
    'O': '二等座',
    'M': '一等座',
    '9': '商务座',
    '3': '硬卧',
    '4': '软卧',
    '1': '硬座'
  }
  return types.split(',').map(t => map[t] || t).join(', ')
}

const formatTime = (time) => {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}

const maskIdNo = (idNo) => {
  if (!idNo || idNo.length < 8) return idNo
  return idNo.slice(0, 4) + '****' + idNo.slice(-4)
}

const handleStart = async () => {
  try {
    await taskStore.startTask(task.value.id)
    ElMessage.success('任务已启动')
    await taskStore.getTask(task.value.id)
  } catch (error) {
    ElMessage.error(error.message)
  }
}

const handleStop = async () => {
  try {
    await taskStore.stopTask(task.value.id)
    ElMessage.success('任务已暂停')
    await taskStore.getTask(task.value.id)
  } catch (error) {
    ElMessage.error(error.message)
  }
}

const handleCancel = async () => {
  try {
    await ElMessageBox.confirm('确定要取消该任务吗？', '确认', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await taskStore.cancelTask(task.value.id)
    ElMessage.success('任务已取消')
    await taskStore.getTask(task.value.id)
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(error.message)
    }
  }
}

const goToPayment = () => {
  window.open('https://kyfw.12306.cn/otn/view/train_order.html', '_blank')
}

const refreshLogs = async () => {
  if (task.value) {
    await taskStore.fetchTaskLogs(task.value.id)
  }
}

onMounted(async () => {
  checkScreenSize()
  window.addEventListener('resize', checkScreenSize)

  const taskId = parseInt(route.params.id)
  await taskStore.getTask(taskId)
  await taskStore.fetchTaskLogs(taskId)

  startLogPolling(taskId)
})

const startLogPolling = (taskId) => {
  stopLogPolling()
  refreshTimer = setInterval(async () => {
    if (task.value?.status === 'running') {
       await taskStore.fetchTaskLogs(taskId)
       await taskStore.getTask(taskId)
    } else {
       await taskStore.getTask(taskId)
    }
  }, 3000)
}

const stopLogPolling = () => {
    if (refreshTimer) {
        clearInterval(refreshTimer)
        refreshTimer = null
    }
}

onUnmounted(() => {
  stopLogPolling()
  window.removeEventListener('resize', checkScreenSize)
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.status-alert {
  margin-top: 20px;
  padding: 15px 20px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  gap: 15px;
}

.success-alert {
  background-color: #f0f9eb;
  color: #67c23a;
  border: 1px solid #e1f3d8;
}

.failed-alert {
  background-color: #fef0f0;
  color: #f56c6c;
  border: 1px solid #fde2e2;
}

.status-icon {
  font-size: 24px;
}

.status-content {
  flex: 1;
}

.status-title {
  font-weight: bold;
  font-size: 16px;
  margin-bottom: 4px;
}

.status-desc {
  font-size: 14px;
  opacity: 0.9;
}

.task-detail-row {
  margin-top: 20px;
}

.log-container {
  max-height: 600px;
  overflow-y: auto;
  padding-right: 10px;
}

.mt-4 {
    margin-top: 16px;
}

.mb-4 {
    margin-bottom: 16px;
}

@media (min-width: 992px) {
  .task-detail-row {
    height: calc(100vh - 145px);
    display: flex;
    align-items: stretch;
    flex-wrap: wrap;
  }

  .task-detail-col {
    height: 100%;
    display: flex;
    flex-direction: column;
  }

  .full-height-card {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .full-height-card :deep(.el-card__body) {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    padding-bottom: 0;
  }

  .log-container,
  .scroll-container {
    flex: 1;
    overflow-y: auto;
    max-height: none;
    padding-right: 10px;
    padding-bottom: 20px;
  }
}

.section-divider {
  margin: 20px 0 10px;
  font-weight: bold;
  font-size: 15px;
  color: #303133;
  padding-left: 10px;
  border-left: 3px solid #409eff;
  line-height: 1;
}

@media (max-width: 768px) {
    .mobile-btn-group {
        display: flex;
    }
    .mobile-btn-group .el-button {
        padding: 8px 12px;
    }
}
</style>
