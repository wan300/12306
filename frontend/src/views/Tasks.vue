<template>
  <div class="tasks-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>任务列表</span>
          <div class="header-actions">
            <el-select v-model="filterStatus" placeholder="状态筛选" clearable style="width: 120px; margin-right: 12px;">
              <el-option label="全部" value="" />
              <el-option label="待运行" value="pending" />
              <el-option label="运行中" value="running" />
              <el-option label="已暂停" value="paused" />
              <el-option label="成功" value="success" />
              <el-option label="失败" value="failed" />
            </el-select>
            <el-button type="primary" @click="$router.push('/create-task')">
              <el-icon><Plus /></el-icon>
              创建任务
            </el-button>
          </div>
        </div>
      </template>
      
      <el-table :data="filteredTasks" stripe v-loading="taskStore.loading">
        <el-table-column prop="name" label="任务名称" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link type="primary" @click="$router.push(`/task/${row.id}`)">
              {{ row.name }}
            </el-link>
          </template>
        </el-table-column>
        <el-table-column label="行程" :min-width="isMobile ? 140 : 180" align="center">
          <template #default="{ row }">
            {{ row.from_station }} → {{ row.to_station }}
          </template>
        </el-table-column>
        <el-table-column v-if="!isMobile" prop="train_date" label="日期" min-width="110" align="center" />
        <el-table-column v-if="!isMobile" prop="seat_types" label="席别" min-width="100" align="center" />
        <el-table-column v-if="!isMobile" prop="retry_count" label="重试次数" min-width="90" align="center" />
        <el-table-column prop="status" label="状态" :min-width="isMobile ? 80 : 100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" :min-width="isMobile ? 180 : 230" :fixed="isMobile ? false : 'right'" align="center">
          <template #default="{ row }">
            <div class="action-buttons">
              <div class="action-group">
                <el-button
                  v-if="['pending', 'paused', 'failed', 'cancelled'].includes(row.status)"
                  type="primary"
                  size="small"
                  @click="$router.push(`/edit-task/${row.id}`)"
                >
                  编辑
                </el-button>
                <el-button
                  v-if="['pending', 'paused'].includes(row.status)"
                  type="success"
                  size="small"
                  :loading="processingTasks[row.id]"
                  @click="handleStart(row)"
                >
                  启动
                </el-button>
                <el-button
                  v-if="row.status === 'running'"
                  type="warning"
                  size="small"
                  :loading="processingTasks[row.id]"
                  @click="handleStop(row)"
                >
                  暂停
                </el-button>
                <el-button
                  v-if="row.status !== 'success' && row.status !== 'cancelled'"
                  type="danger"
                  size="small"
                  :loading="processingTasks[row.id]"
                  @click="handleCancel(row)"
                >
                  取消
                </el-button>
              </div>
              <el-button
                v-if="row.status !== 'running'"
                type="danger"
                size="small"
                link
                class="btn-delete"
                :loading="processingTasks[row.id]"
                @click="handleDelete(row)"
              >
                删除
              </el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无任务" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useTaskStore } from '../stores/task'

const taskStore = useTaskStore()

const filterStatus = ref('')
const processingTasks = ref({})
const isMobile = ref(false)

const checkScreenSize = () => {
  isMobile.value = window.innerWidth < 768
}

const filteredTasks = computed(() => {
  if (!filterStatus.value) return taskStore.tasks
  return taskStore.tasks.filter(t => t.status === filterStatus.value)
})

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

const handleStart = async (task) => {
  processingTasks.value[task.id] = true
  try {
    await taskStore.startTask(task.id)
    ElMessage.success('任务已启动')
    await taskStore.fetchTasks()
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    processingTasks.value[task.id] = false
  }
}

const handleStop = async (task) => {
  processingTasks.value[task.id] = true
  try {
    await taskStore.stopTask(task.id)
    ElMessage.success('任务已暂停')
    await taskStore.fetchTasks()
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    processingTasks.value[task.id] = false
  }
}

const handleCancel = async (task) => {
  try {
    await ElMessageBox.confirm('确定要取消该任务吗？', '确认')
    processingTasks.value[task.id] = true
    await taskStore.cancelTask(task.id)
    ElMessage.success('任务已取消')
    await taskStore.fetchTasks()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(error.message)
    }
  } finally {
    processingTasks.value[task.id] = false
  }
}

const handleDelete = async (task) => {
  try {
    await ElMessageBox.confirm('确定要删除该任务吗？此操作不可恢复。', '确认删除', { type: 'warning' })
    processingTasks.value[task.id] = true
    await taskStore.deleteTask(task.id)
    ElMessage.success('任务已删除')
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(error.message)
    }
  } finally {
     if (task) processingTasks.value[task.id] = false
  }
}

onMounted(async () => {
  checkScreenSize()
  window.addEventListener('resize', checkScreenSize)
  await taskStore.fetchTasks()
})

onUnmounted(() => {
  window.removeEventListener('resize', checkScreenSize)
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  align-items: center;
}

.action-buttons {
  display: flex;
  align-items: center;
  justify-content: center;
}

.action-group {
  display: flex;
}

.action-group .el-button {
  margin: 0;
  border-radius: 0;
  border: none;
  padding: 8px 15px;
}

.action-group .el-button:first-child {
  border-top-left-radius: 4px;
  border-bottom-left-radius: 4px;
}

.action-group .el-button:last-child {
  border-top-right-radius: 4px;
  border-bottom-right-radius: 4px;
}

.action-group .el-button:not(:first-child) {
  border-left: 1px solid rgba(255, 255, 255, 0.2);
}

.btn-delete {
  margin-left: 12px;
}

@media (max-width: 768px) {
  .card-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .header-actions {
    width: 100%;
    justify-content: space-between;
  }

  .header-actions .el-select {
    flex: 1;
    margin-right: 12px !important;
  }

  .action-group .el-button {
    padding: 6px 10px;
  }
}
</style>
