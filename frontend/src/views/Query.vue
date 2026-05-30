<template>
  <div class="query-page">
    <el-card>
      <template #header>
        <span>车票查询</span>
      </template>

      <el-form :inline="true" :model="queryForm" class="query-form">
        <el-form-item label="出发站" class="form-item-compact">
          <el-select
            v-model="queryForm.from_station"
            filterable
            remote
            :remote-method="searchFromStation"
            :loading="loadingFrom"
            placeholder="出发地"
            style="width: 130px;"
          >
            <el-option
              v-for="s in fromStations"
              :key="s.code"
              :label="s.name"
              :value="s.name"
            />
          </el-select>
        </el-form-item>

        <div class="swap-wrapper">
           <el-button :icon="Switch" circle @click="swapStations" class="swap-btn" />
        </div>

        <el-form-item label="到达站" class="form-item-compact">
          <el-select
            v-model="queryForm.to_station"
            filterable
            remote
            :remote-method="searchToStation"
            :loading="loadingTo"
            placeholder="目的地"
            style="width: 130px;"
          >
            <el-option
              v-for="s in toStations"
              :key="s.code"
              :label="s.name"
              :value="s.name"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="日期" class="form-item-compact">
          <el-date-picker
            v-model="queryForm.train_date"
            type="date"
            placeholder="选择日期"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
            style="width: 150px;"
            :disabled-date="disabledDate"
          />
        </el-form-item>

        <el-form-item label="车次类型">
          <el-select v-model="queryForm.train_types" placeholder="全部" clearable style="width: 110px;">
            <el-option label="高铁 G" value="G" />
            <el-option label="动车 D" value="D" />
            <el-option label="城际 C" value="C" />
            <el-option label="直达 Z" value="Z" />
            <el-option label="特快 T" value="T" />
            <el-option label="快速 K" value="K" />
          </el-select>
        </el-form-item>

        <el-form-item>
          <el-checkbox v-model="queryForm.only_has_ticket">只看有票</el-checkbox>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" @click="handleQuery" :loading="loading">
            <el-icon><Search /></el-icon>
            查询
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card style="margin-top: 16px;" v-if="trains.length > 0 || loading">
      <template #header>
        <div class="result-header">
          <div style="display: flex; gap: 10px; align-items: center;">
            <span>查询结果 ({{ trains.length }} 趟)</span>
            <el-tag v-if="queryForm.train_date">{{ queryForm.train_date }}</el-tag>
          </div>
          <el-button
            type="success"
            size="small"
            :icon="Ticket"
            :disabled="selectedTrains.length === 0"
            @click="handleCreateTask"
          >
            创建抢票任务 (选 {{ selectedTrains.length }})
          </el-button>
        </div>
      </template>

      <el-table :data="trains" stripe v-loading="loading" max-height="550" @selection-change="handleSelectionChange">
        <el-table-column type="selection" min-width="50" fixed="left" align="center" />
        <el-table-column prop="train_code" label="车次" min-width="100" fixed="left" align="center">
          <template #default="{ row }">
            <el-tag :style="getTrainTagStyle(row.train_code)" effect="plain" style="font-weight: bold;">
              {{ row.train_code }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="出发 - 到达" min-width="240" align="center">
          <template #default="{ row }">
            <div class="trip-info">
              <div class="station from">
                <span class="name">{{ row.from_station }}</span>
                <span class="time start">{{ row.start_time }}</span>
              </div>
              <div class="arrow">
                <span class="duration">{{ row.duration }}</span>
                <el-icon><Right /></el-icon>
                <span class="date-diff" v-if="false">TODO</span>
              </div>
              <div class="station to">
                <span class="name">{{ row.to_station }}</span>
                <span class="time arrive">{{ row.arrive_time }}</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="business_seat" label="商务" min-width="70" align="center">
          <template #default="{ row }">
            <div :class="['seat-info', getSeatClass(row.business_seat)]">{{ row.business_seat }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="first_seat" label="一等" min-width="70" align="center">
          <template #default="{ row }">
            <div :class="['seat-info', getSeatClass(row.first_seat)]">{{ row.first_seat }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="second_seat" label="二等" min-width="70" align="center">
          <template #default="{ row }">
            <div :class="['seat-info', getSeatClass(row.second_seat)]">{{ row.second_seat }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="soft_sleeper" label="软卧" min-width="70" align="center">
          <template #default="{ row }">
            <div :class="['seat-info', getSeatClass(row.soft_sleeper)]">{{ row.soft_sleeper }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="hard_sleeper" label="硬卧" min-width="70" align="center">
          <template #default="{ row }">
            <div :class="['seat-info', getSeatClass(row.hard_sleeper)]">{{ row.hard_sleeper }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="hard_seat" label="硬座" min-width="70" align="center">
          <template #default="{ row }">
            <div :class="['seat-info', getSeatClass(row.hard_seat)]">{{ row.hard_seat }}</div>
          </template>
        </el-table-column>
         <el-table-column prop="no_seat" label="无座" min-width="70" align="center">
          <template #default="{ row }">
            <div :class="['seat-info', getSeatClass(row.no_seat)]">{{ row.no_seat }}</div>
          </template>
        </el-table-column>

        <el-table-column label="操作" min-width="90" fixed="right" align="center">
          <template #default="{ row }">
            <el-tag v-if="!row.can_buy" type="info" size="small">停运</el-tag>
            <span v-else>--</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Switch, Right, Search, Ticket } from '@element-plus/icons-vue'
import api from '../api'

const router = useRouter()
const loading = ref(false)
const loadingFrom = ref(false)
const loadingTo = ref(false)
const fromStations = ref([])
const selectedTrains = ref([])
const toStations = ref([])
const trains = ref([])

const queryForm = reactive({
  from_station: '',
  to_station: '',
  train_date: '',
  train_types: '',
  only_has_ticket: true
})

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

const swapStations = () => {
  const temp = queryForm.from_station
  queryForm.from_station = queryForm.to_station
  queryForm.to_station = temp
}

const handleQuery = async () => {
  if (!queryForm.from_station || !queryForm.to_station || !queryForm.train_date) {
    ElMessage.warning('请填写完整查询条件')
    return
  }

  loading.value = true
  try {
    const res = await api.queryTickets({
      from_station: queryForm.from_station,
      to_station: queryForm.to_station,
      train_date: queryForm.train_date,
      train_types: queryForm.train_types || undefined,
      only_has_ticket: queryForm.only_has_ticket
    })

    if (res.success) {
      trains.value = res.trains
      if (trains.value.length === 0) {
        ElMessage.info('未找到符合条件的车次')
      }
    } else {
      ElMessage.error(res.message || '查询失败')
    }
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    loading.value = false
  }
}

const handleSelectionChange = (selection) => {
  selectedTrains.value = selection
}

const handleCreateTask = () => {
  const codes = selectedTrains.value.map(t => t.train_code)
  router.push({
    name: 'CreateTask',
    query: {
      from: queryForm.from_station,
      to: queryForm.to_station,
      date: queryForm.train_date,
      codes: codes.join(',')
    }
  })
}

const getTrainTagStyle = (code) => {
  if (!code) return {}
  const first = code[0].toUpperCase()

  let color = '#409EFF' // Default Blue

  // Standard colors
  if (first === 'G') color = '#F56C6C' // Red
  else if (first === 'D') color = '#409EFF' // Blue
  else if (first === 'Z' || first === 'T') color = '#67C23A' // Green
  else if (first === 'K' || first === 'C') color = '#E6A23C' // Orange
  else {
    // Hash based colors
    const colors = [
      '#722ED1', '#13C2C2', '#EB2F96', '#FAAD14',
      '#2F54EB', '#FA541C', '#1890FF', '#52C41A'
    ]
    let hash = 0
    for (let i = 0; i < code.length; i++) {
      hash = code.charCodeAt(i) + ((hash << 5) - hash)
    }
    color = colors[Math.abs(hash) % colors.length]
  }

  return {
    color: color,
    borderColor: color,
    backgroundColor: hexToRgba(color, 0.1)
  }
}

const hexToRgba = (hex, alpha) => {
  const r = parseInt(hex.slice(1, 3), 16)
  const g = parseInt(hex.slice(3, 5), 16)
  const b = parseInt(hex.slice(5, 7), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

const getSeatClass = (value) => {
  if (!value || value === '--' || value === '无' || value === '*') {
    return 'seat-none'
  }
  if (value === '有') return 'seat-has'
  const num = parseInt(value)
  if (!isNaN(num) && num > 0) return 'seat-has'
  return 'seat-none'
}
const disabledDate = (time) => {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return time.getTime() < today.getTime()
}
</script>

<style scoped>
.query-form {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.query-form .el-form-item {
  margin-right: 0;
  margin-bottom: 0;
}

/* 移动端优化 */
@media (max-width: 768px) {
  .query-form {
    flex-wrap: wrap;
    gap: 0;
    justify-content: space-between;
  }

  .query-form .el-form-item {
    margin-right: 0;
    margin-bottom: 0;
  }

  .query-form > :nth-child(1) {
    width: 42%;
  }
  .swap-wrapper {
    width: 16%; /* 剩余空间 100 - 42 - 42 = 16% */
    padding: 0;
    display: flex;
    justify-content: center;
  }

  .swap-btn {
    width: 32px;
    height: 32px;
    font-size: 14px;
    border: none;
    background: transparent;
  }

  .query-form > :nth-child(3) {
    width: 42%;
  }

  .query-form > :nth-child(4) {
    width: 100%;
    margin-top: 4px;
  }

  .query-form > :nth-child(5) {
    width: 42%;
    margin-top: 4px;
  }

  .query-form > :nth-child(6) {
    width: 42%;
    margin-top: 4px;
    margin-bottom: 0;
    display: flex;
    align-items: center;
    justify-content: flex-start;
    margin-right: 0;
  }

  .query-form > :nth-child(7) {
    width: 100%;
    margin-top: 10px;
  }

  .query-form .el-form-item :deep(.el-input),
  .query-form .el-form-item :deep(.el-select),
  .query-form .el-form-item :deep(.el-date-editor) {
    width: 100% !important;
  }

  .query-form .el-button {
    width: 100%;
  }

  .form-item-compact :deep(.el-form-item__label) {
    display: none; /* 移动端隐藏标签 */
  }
}

.swap-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 10px;
}

.result-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.train-code-cell {
  font-weight: bold;
  font-size: 16px;
  color: #303133;
}

.trip-info {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 15px;
}

.station {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.station .name {
  font-size: 14px;
}

.station .time {
  font-size: 18px;
  font-weight: bold;
  line-height: 1.2;
}

.station .time.start {
  color: #303133;
}

.station .time.arrive {
  color: #303133;
}

.arrow {
  display: flex;
  flex-direction: column;
  align-items: center;
  color: #909399;
  font-size: 12px;
}

.duration {
  margin-bottom: -4px;
}

.seat-info {
  font-weight: 500;
}

.seat-has {
  color: #67C23A;
}

.seat-none {
  color: #C0C4CC;
}
</style>
