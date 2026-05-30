<template>
  <div class="create-task-page">
    <el-card>
      <template #header>
        <span>{{ isEditMode ? '编辑抢票任务' : '创建抢票任务' }}</span>
      </template>
      
      <el-form
        class="task-form"
        ref="formRef"
        :model="form" 
        :rules="rules" 
        :label-width="isMobile ? 'auto' : '100px'"
        :label-position="isMobile ? 'top' : 'right'"
      >
        <el-divider content-position="left">基本信息</el-divider>
        
        <el-form-item label="任务名称" prop="name">
          <el-input v-model="form.name" placeholder="如：春运回家票" />
        </el-form-item>
        
        <el-row :gutter="20">
          <el-col :xs="12" :sm="12">
            <el-form-item label="出发站" prop="from_station">
              <el-select 
                v-model="form.from_station" 
                filterable 
                remote
                :remote-method="searchFromStation"
                :loading="loadingFrom"
                placeholder="输入站名搜索"
                style="width: 100%;"
              >
                <el-option 
                  v-for="s in fromStations" 
                  :key="s.code" 
                  :label="s.name" 
                  :value="s.name"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :xs="12" :sm="12">
            <el-form-item label="到达站" prop="to_station">
              <el-select 
                v-model="form.to_station" 
                filterable 
                remote
                :remote-method="searchToStation"
                :loading="loadingTo"
                placeholder="输入站名搜索"
                style="width: 100%;"
              >
                <el-option 
                  v-for="s in toStations" 
                  :key="s.code" 
                  :label="s.name" 
                  :value="s.name"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        
        <el-row :gutter="20">
          <el-col :xs="24" :sm="12">
            <el-form-item label="出发日期" prop="train_date">
              <el-date-picker
                v-model="form.train_date"
                type="date"
                placeholder="选择日期"
                format="YYYY-MM-DD"
                value-format="YYYY-MM-DD"
                :disabled-date="disabledDate"
                style="width: 100%;"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12">
            <el-form-item label="出发时间段">
              <div class="time-range">
                <el-time-select
                  v-model="form.start_time_min"
                  placeholder="最早"
                  start="00:00"
                  step="00:30"
                  end="23:30"
                />
                <span>至</span>
                <el-time-select
                  v-model="form.start_time_max"
                  placeholder="最晚"
                  start="00:00"
                  step="00:30"
                  end="23:30"
                />
              </div>
            </el-form-item>
          </el-col>
        </el-row>
        
        <el-divider content-position="left">筛选条件</el-divider>
        
        <el-form-item label="车次类型">
          <el-checkbox-group v-model="form.train_types" class="checkbox-grid">
            <el-checkbox label="G">高铁 G</el-checkbox>
            <el-checkbox label="D">动车 D</el-checkbox>
            <el-checkbox label="C">城际 C</el-checkbox>
            <el-checkbox label="Z">直达 Z</el-checkbox>
            <el-checkbox label="T">特快 T</el-checkbox>
            <el-checkbox label="K">快速 K</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        
        <el-form-item label="席别优先级" prop="seat_types">
          <div class="seat-priority-container">
            <el-checkbox-group v-model="form.seat_types" class="checkbox-grid">
               <el-checkbox v-for="(label, key) in seatTypeMap" :key="key" :label="key">{{ label }}</el-checkbox>
            </el-checkbox-group>

            <div v-if="form.seat_types.length > 0" class="seat-ordering">
               <div class="form-tip">已选席别 (拖拽标签调整优先级顺序，越靠前优先级越高):</div>
               <div class="draggable-tags">
                  <el-tag
                    v-for="(type, index) in form.seat_types"
                    :key="type"
                    closable
                    @close="removeSeatType(type)"
                    effect="dark"
                    class="seat-tag"
                    draggable="true"
                    @dragstart="handleDragStart(index)"
                    @drop.prevent="handleDrop(index)"
                    @dragover.prevent
                    @dragenter.prevent
                  >
                    {{ seatTypeMap[type] }}
                  </el-tag>
               </div>
            </div>
          </div>
        </el-form-item>
        
        <el-form-item label="指定车次">
          <div style="display: flex; gap: 8px; width: 100%;">
            <el-select 
              v-model="form.train_codes" 
              multiple 
              filterable 
              allow-create
              placeholder="可输入车次号，如 G101"
              style="flex: 1;"
            >
              <el-option
                  v-for="code in availableTrainCodes"
                  :key="code"
                  :label="code"
                  :value="code"
               />
            </el-select>
            <el-button :icon="Search" circle @click="queryTrainCodes" :loading="loadingTrains" title="查询符合条件的车次" />
          </div>
          <div class="form-tip">留空表示不限车次。点击搜索按钮可根据出发到达站和日期获取车次。</div>
        </el-form-item>
        
        <el-divider content-position="left">乘车人</el-divider>
        
        <el-form-item label="乘车人" prop="passengers">
          <el-table :data="form.passengers" border style="width: 100%; margin-bottom: 15px;">
            <el-table-column prop="passenger_name" label="姓名" min-width="100" align="center" />
            <el-table-column prop="passenger_id_no" label="身份证号" min-width="180" align="center" show-overflow-tooltip />
            <el-table-column prop="passenger_type" label="票种" min-width="130" align="center">
              <template #default="{ row }">
                <el-select v-model="row.passenger_type" size="small" style="width: 100px;">
                  <el-option label="成人票" value="1" />
                  <el-option label="儿童票" value="2" />
                  <el-option label="学生票" value="3" />
                  <el-option label="残军票" value="4" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column prop="mobile_no" label="手机号" min-width="130" align="center" />
            <el-table-column label="操作" min-width="70" align="center">
              <template #default="{ $index }">
                <el-button type="danger" link :icon="Delete" @click="removePassenger($index)" />
              </template>
            </el-table-column>
          </el-table>
          <el-button type="success" plain class="add-passenger-btn" @click="openPassengerDialog">
            <el-icon><Plus /></el-icon>
            添加乘车人
          </el-button>
        </el-form-item>
        
        <el-divider content-position="left">任务配置</el-divider>
        
        <el-row :gutter="20">
          <el-col :xs="24" :sm="8">
            <el-form-item label="刷票间隔">
              <div class="inline-control">
                <el-input-number v-model="form.query_interval" :min="3" :max="60" />
                <span>秒</span>
              </div>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="最大重试">
              <div class="retry-wrapper">
                <el-input-number 
                  v-if="!isInfiniteRetry" 
                  v-model="form.max_retry_count" 
                  :min="1" 
                  :max="10000" 
                  class="retry-input"
                />
                <span v-else class="infinite-text">无限循环</span>
                <el-checkbox v-model="isInfiniteRetry" @change="handleInfiniteChange" label="无限" />
              </div>
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="自动提交">
              <el-switch v-model="form.auto_submit" />
            </el-form-item>
          </el-col>
        </el-row>
        
        <el-form-item>
          <el-button type="primary" @click="handleSubmit" :loading="submitting">
            {{ isEditMode ? '保存修改' : '创建任务' }}
          </el-button>
          <el-button @click="$router.back()">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-dialog
      v-model="passengerDialogVisible"
      title="选择联系人"
      :width="isMobile ? '92%' : '640px'"
    >
      <el-table 
        :data="contactPassengers" 
        v-loading="loadingPassengers"
        @selection-change="val => selectedContacts = val"
        height="400"
      >
        <el-table-column type="selection" width="55" />
        <el-table-column property="passenger_name" label="姓名" min-width="100" align="center" />
        <el-table-column property="passenger_id_no" label="证件号" min-width="180" align="center" show-overflow-tooltip />
        <el-table-column property="passenger_type" label="类型" min-width="80" align="center">
            <template #default="{ row }">
                <el-tag v-if="row.passenger_type === '1'" size="small">成人</el-tag>
                <el-tag v-else-if="row.passenger_type === '2'" size="small" type="success">儿童</el-tag>
                <el-tag v-else-if="row.passenger_type === '3'" size="small" type="warning">学生</el-tag>
                <el-tag v-else size="small" type="info">其他</el-tag>
            </template>
        </el-table-column>
         <el-table-column property="mobile_no" label="手机号" min-width="120" align="center" />
      </el-table>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="passengerDialogVisible = false">取消</el-button>
          <el-button type="primary" @click="confirmAddPassengers">
            添加选中
          </el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, watch, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Delete, Plus, Search } from '@element-plus/icons-vue'
import { useTaskStore } from '../stores/task'
import { useUserStore } from '../stores/user'
import api from '../api'

const router = useRouter()
const route = useRoute()
const taskStore = useTaskStore()
const userStore = useUserStore()

const formRef = ref(null)
const submitting = ref(false)
const loadingFrom = ref(false)
const loadingTo = ref(false)
const loadingTrains = ref(false) // Query train loading
const fromStations = ref([])
const toStations = ref([])
const availableTrainCodes = ref([]) // Result from query

// Passenger Dialog
const passengerDialogVisible = ref(false)
const loadingPassengers = ref(false)
const contactPassengers = ref([]) // From API
const selectedContacts = ref([]) // Selected in dialog

// Infinite Retry
const isInfiniteRetry = ref(false)
const isMobile = ref(false)

const form = reactive({
  name: '',
  from_station: '',
  to_station: '',
  train_date: '',
  train_types: ['G', 'D'],
  seat_types: ['O', 'M'],
  train_codes: [],
  start_time_min: '',
  start_time_max: '',
  passengers: [],
  query_interval: 5,
  max_retry_count: 100,
  auto_submit: true
})

const isEditMode = computed(() => !!route.params.id)

const checkScreenSize = () => {
  isMobile.value = window.innerWidth < 768
}

const seatTypeMap = {
  '9': '商务座',
  'M': '一等座',
  'O': '二等座',
  '4': '软卧',
  '3': '硬卧',
  '1': '硬座'
}

const dragIndex = ref(-1)

const handleDragStart = (index) => {
  dragIndex.value = index
}

const handleDrop = (index) => {
  if (dragIndex.value !== -1 && dragIndex.value !== index) {
    const item = form.seat_types[dragIndex.value]
    form.seat_types.splice(dragIndex.value, 1)
    form.seat_types.splice(index, 0, item)
  }
  dragIndex.value = -1
}

const removeSeatType = (type) => {
    const index = form.seat_types.indexOf(type)
    if (index !== -1) {
        form.seat_types.splice(index, 1)
    }
}

watch(() => form.train_codes, (newCodes) => {
  if (!newCodes || newCodes.length === 0) return
  
  const typeSet = new Set(form.train_types)
  let changed = false
  
  newCodes.forEach(code => {
    if (!code) return
    const firstChar = code.charAt(0).toUpperCase()
    if (['G', 'D', 'C', 'Z', 'T', 'K'].includes(firstChar)) {
      if (!typeSet.has(firstChar)) {
        typeSet.add(firstChar)
        changed = true
      }
    }
  })
  
  if (changed) {
    form.train_types = Array.from(typeSet)
  }
}, { deep: true })

const rules = {
  name: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
  from_station: [{ required: true, message: '请选择出发站', trigger: 'change' }],
  to_station: [{ required: true, message: '请选择到达站', trigger: 'change' }],
  train_date: [{ required: true, message: '请选择出发日期', trigger: 'change' }],
  seat_types: [{ required: true, message: '请选择至少一个席别', trigger: 'change' }],
  passengers: [
    {
      validator: (rule, value, callback) => {
        if (value.length === 0) {
          callback(new Error('请添加至少一个乘车人'))
        } else if (value.some(p => !p.passenger_name || !p.passenger_id_no)) {
          callback(new Error('请填写完整乘车人信息'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

const disabledDate = (time) => {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const maxDate = new Date(today)
  maxDate.setDate(maxDate.getDate() + 15)
  return time.getTime() < today.getTime() || time.getTime() > maxDate.getTime()
}

const searchFromStation = async (query) => {
  if (!query) return
  loadingFrom.value = true
  try {
    const res = await api.searchStations(query)
    fromStations.value = res.stations || []
  } catch (error) {
    console.error(error)
  } finally {
    loadingFrom.value = false
  }
}

const searchToStation = async (query) => {
  if (!query) return
  loadingTo.value = true
  try {
    const res = await api.searchStations(query)
    toStations.value = res.stations || []
  } catch (error) {
    console.error(error)
  } finally {
    loadingTo.value = false
  }
}

const removePassenger = (index) => {
  form.passengers.splice(index, 1)
}

const queryTrainCodes = async () => {
  if (!form.from_station || !form.to_station || !form.train_date) {
    ElMessage.warning('请先填写出发站、到达站和出发日期')
    return
  }
  
  loadingTrains.value = true
  availableTrainCodes.value = []
  try {
    const params = {
      from_station: form.from_station,
      to_station: form.to_station,
      train_date: form.train_date
    }
    // Filter by start time
    if (form.start_time_min) params.start_time_min = form.start_time_min
    if (form.start_time_max) params.start_time_max = form.start_time_max
    if (form.train_types && form.train_types.length > 0) params.train_types = form.train_types.join(',')
    
    const res = await api.queryTickets(params)
    if (res.data) {
       availableTrainCodes.value = res.data.map(t => t.train_code)
       ElMessage.success(`查询到 ${availableTrainCodes.value.length} 个符合条件的车次`)
    }
  } catch (error) {
    ElMessage.error('查询车次失败: ' + error.message)
  } finally {
    loadingTrains.value = false
  }
}

const openPassengerDialog = async () => {
    if (!userStore.currentUser) {
        ElMessage.warning('请先登录')
        return
    }
    passengerDialogVisible.value = true
    if (contactPassengers.value.length === 0) {
        await fetchContacts()
    }
}

const fetchContacts = async () => {
    loadingPassengers.value = true
    try {
        const res = await api.getPassengers()
        if (res.success) {
            contactPassengers.value = res.data
        } else {
            ElMessage.warning(res.message || '获取联系人失败')
        }
    } catch (error) {
        ElMessage.error('获取联系人失败: ' + error.message)
    } finally {
        loadingPassengers.value = false
    }
}

const confirmAddPassengers = () => {
    selectedContacts.value.forEach(contact => {
        // Simple duplicate check by ID
        const exists = form.passengers.some(p => p.passenger_id_no === contact.passenger_id_no)
        
        if (!exists) {
            form.passengers.push({ ...contact })
        }
    })
    passengerDialogVisible.value = false
}

const handleInfiniteChange = (val) => {
    if (val) {
        form.max_retry_count = -1
    } else {
        form.max_retry_count = 100
    }
}

const handleSubmit = async () => {
  if (!userStore.currentUser) {
    ElMessage.warning('请先选择账号')
    return
  }
  
  if (!userStore.isLoggedIn) {
    ElMessage.warning('当前账号未登录 12306，请先登录')
    return
  }
  
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  
  submitting.value = true
  try {
    const taskData = {
      name: form.name,
      from_station: form.from_station,
      to_station: form.to_station,
      train_date: form.train_date,
      train_types: form.train_types,
      seat_types: form.seat_types,
      passengers: form.passengers,
      query_interval: form.query_interval,
      max_retry_count: form.max_retry_count,
      auto_submit: form.auto_submit,
      train_codes: form.train_codes.length > 0 ? form.train_codes : [],
      start_time_range: form.start_time_min && form.start_time_max 
        ? `${form.start_time_min}-${form.start_time_max}` 
        : null
    }
    
    if (isEditMode.value) {
      await taskStore.updateTask(route.params.id, taskData)
      ElMessage.success('任务更新成功')
    } else {
      await taskStore.createTask(taskData)
      ElMessage.success('任务创建成功')
    }
    router.push('/tasks')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  // Fill form from query params (when creating from query results)
  if (!isEditMode.value) {
    if (route.query.from) {
      form.from_station = route.query.from
      fromStations.value = [{ name: route.query.from, code: 'QUERY' }]
    }
    if (route.query.to) {
      form.to_station = route.query.to
      toStations.value = [{ name: route.query.to, code: 'QUERY' }]
    }
    if (route.query.date) {
      form.train_date = route.query.date
    }
    if (route.query.codes) {
      const codes = route.query.codes.split(',').filter(c => c)
      if (codes.length > 0) {
        form.train_codes = codes
        // Also populate available options so they display nicely if they rely on it (though allow-create handles values)
        availableTrainCodes.value = codes 
      }
    }
    
    // Auto-generate a task name
    if (form.from_station && form.to_station) {
      form.name = `${form.train_date} ${form.from_station}-${form.to_station} 抢票`
    }
  }

  if (isEditMode.value) {
    const taskId = route.params.id
    try {
      const task = await taskStore.getTask(taskId)
      if (task) {
        form.name = task.name
        form.from_station = task.from_station
        fromStations.value = [{ name: task.from_station, code: task.from_station }] 
        form.to_station = task.to_station
        toStations.value = [{ name: task.to_station, code: task.to_station }] 
        form.train_date = task.train_date
        
        if (task.train_codes) form.train_codes = task.train_codes.split(',')
        if (task.train_types) form.train_types = task.train_types.split(',')
        if (task.seat_types) form.seat_types = task.seat_types.split(',')
        
        if (task.start_time_range) {
          const [min, max] = task.start_time_range.split('-')
          form.start_time_min = min
          form.start_time_max = max
        }
        
        if (task.passengers) {
            try {
              form.passengers = JSON.parse(task.passengers)
            } catch (e) {
              console.error('Json parse passengers failed', e)
              form.passengers = []
            }
        }
        
        form.query_interval = task.query_interval
        form.max_retry_count = task.max_retry_count
        form.auto_submit = task.auto_submit
        
        isInfiniteRetry.value = form.max_retry_count === -1
      }
    } catch (e) {
      ElMessage.error('加载任务失败')
    }
  }
  checkScreenSize()
  window.addEventListener('resize', checkScreenSize)
})

onUnmounted(() => {
  window.removeEventListener('resize', checkScreenSize)
})
</script>

<style scoped>
.task-form {
  max-width: 900px;
}

.time-range,
.inline-control,
.retry-wrapper {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.time-range .el-select {
  flex: 1;
}

.add-passenger-btn {
  width: 100%;
  border-style: dashed;
}

.form-tip {
  color: #909399;
  font-size: 12px;
  margin-top: 4px;
}

.seat-priority-container {
  width: 100%;
}

.retry-input {
  flex: 1;
}

.infinite-text {
  color: #409EFF;
  flex: 1;
  text-align: center;
}

.draggable-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
  padding: 8px;
  background-color: #f5f7fa;
  border-radius: 4px;
}

.seat-tag {
  cursor: move;
}

.seat-tag:hover {
  opacity: 0.8;
}

@media (max-width: 768px) {
  .checkbox-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
  }

  .checkbox-grid .el-checkbox {
    margin-right: 0;
  }

  .time-range {
    gap: 6px;
  }

  .retry-wrapper {
    flex-wrap: nowrap;
  }

  .retry-input {
    width: auto !important;
  }

  .infinite-text {
    flex: none;
    margin-bottom: 0;
    white-space: nowrap;
  }

  .retry-wrapper .el-checkbox {
    margin-left: 4px;
    margin-right: 0;
  }
}
</style>
