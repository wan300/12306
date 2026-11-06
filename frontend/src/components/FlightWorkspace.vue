<template>
  <div class="flight-workspace">
    <el-alert
      v-if="!desktop"
      title="请在桌面应用中连接机票平台"
      type="info"
      :closable="false"
      show-icon
    />
    <template v-else>
      <el-alert
        v-if="error"
        :title="error"
        type="error"
        show-icon
        @close="error = ''"
      />
      <el-card shadow="never" class="flight-card">
        <div class="provider-bar">
          <el-radio-group v-model="form.provider" :disabled="busy">
            <el-radio-button label="ctrip">携程</el-radio-button>
            <el-radio-button label="qunar">去哪儿</el-radio-button>
          </el-radio-group>
          <div class="provider-actions">
            <el-button @click="router.push('/login')">管理平台登录</el-button>
          </div>
        </div>
        <el-form
          label-position="top"
          class="search-form"
          novalidate
          @submit.prevent="search"
        >
          <el-form-item label="出发城市">
            <el-select
              v-model="form.origin"
              filterable
              :disabled="busy"
              aria-label="出发城市"
            >
              <el-option
                v-for="city in cities"
                :key="city.code"
                :value="city.code"
                :label="`${city.name} ${city.code}`"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="到达城市">
            <el-select
              v-model="form.destination"
              filterable
              :disabled="busy"
              aria-label="到达城市"
            >
              <el-option
                v-for="city in cities"
                :key="city.code"
                :value="city.code"
                :label="`${city.name} ${city.code}`"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="出发日期">
            <el-date-picker
              v-model="form.departure_date"
              type="date"
              value-format="YYYY-MM-DD"
              :clearable="false"
              :disabled="busy"
              aria-label="出发日期"
            />
          </el-form-item>
          <el-form-item label=" ">
            <el-button
              type="primary"
              :loading="searching"
              :disabled="busy && !searching"
              native-type="submit"
              >查询航班</el-button
            >
          </el-form-item>
        </el-form>
      </el-card>

      <el-card shadow="never" class="flight-card">
        <template #header>
          <div class="section-heading">
            <span>航班报价</span>
            <div class="quote-tools">
              <span class="hint">{{
                queriedAt
                  ? `更新于 ${formatTime(queriedAt)}`
                  : '查询后选择目标航班'
              }}</span>
              <el-select
                v-model="sortOrder"
                aria-label="航班排序"
                style="width: 180px"
              >
                <el-option label="默认排序" value="default" />
                <el-option label="起飞时间从早到晚" value="time_asc" />
                <el-option label="起飞时间从晚到早" value="time_desc" />
                <el-option label="价格从低到高" value="price_asc" />
                <el-option label="价格从高到低" value="price_desc" />
              </el-select>
            </div>
          </div>
        </template>
        <el-table
          :data="sortedFlights"
          :row-class-name="rowClass"
          @row-click="selectFlight"
          max-height="330"
          empty-text="还没有查询结果"
        >
          <el-table-column prop="flight_number" label="航班" min-width="100" />
          <el-table-column prop="departure_time" label="起飞" min-width="90" />
          <el-table-column prop="arrival_time" label="到达" min-width="90" />
          <el-table-column label="列表参考价（不含税）" min-width="190"
            ><template #default="{ row }"
              >¥{{ row.base_price }}</template
            ></el-table-column
          >
          <el-table-column label="操作" width="120"
            ><template #default="{ row }"
              ><el-button
                text
                type="primary"
                :disabled="busy"
                @click.stop="loadOffers(row)"
                >选择舱位</el-button
              ></template
            ></el-table-column
          >
        </el-table>
      </el-card>

      <el-card v-if="form.flight_number" shadow="never" class="flight-card">
        <template #header
          ><div class="section-heading">
            <span>{{ form.flight_number }} · 舱位选择</span
            ><el-button
              text
              :loading="loadingOffers"
              :disabled="busy && !loadingOffers"
              @click="loadOffers({ flight_number: form.flight_number })"
              >刷新舱位</el-button
            >
          </div></template
        >
        <el-table
          v-loading="loadingOffers"
          :data="offers"
          empty-text="点击选择舱位，获取当前报价"
          max-height="350"
        >
          <el-table-column label="舱位" width="100"
            ><template #default="{ row }">{{
              row.economy ? '经济舱' : '其他舱位'
            }}</template></el-table-column
          >
          <el-table-column
            prop="description"
            label="产品及退改行李规则"
            min-width="300"
          />
          <el-table-column label="票面价" width="110"
            ><template #default="{ row }"
              >¥{{ row.base_price }}</template
            ></el-table-column
          >
          <el-table-column label="操作" width="120"
            ><template #default="{ row }"
              ><el-button
                text
                type="primary"
                :disabled="
                  busy ||
                  !row.economy ||
                  row.restricted ||
                  !row.direct_booking ||
                  (row.service_package && !form.allow_service_packages)
                "
                @click="selectOffer(row)"
                >{{
                  form.offer_key === row.offer_key ? '已选择' : '选择'
                }}</el-button
              ></template
            ></el-table-column
          >
        </el-table>
      </el-card>

      <el-card shadow="never" class="flight-card">
        <template #header
          ><div class="section-heading"><span>预订信息</span></div></template
        >
        <el-form
          label-position="top"
          class="booking-form"
          :disabled="busy"
          novalidate
          @submit.prevent="
            props.purpose === 'create' ? createMonitor() : preview()
          "
        >
          <el-form-item label="目标航班"
            ><el-input
              v-model="form.flight_number"
              placeholder="例如 MU5138"
              maxlength="6"
              @input="form.flight_number = form.flight_number.toUpperCase()"
          /></el-form-item>
          <el-form-item label="乘机人姓名"
            ><el-input
              v-model="passengerText"
              placeholder="与平台姓名完全一致；多人用逗号分隔"
          /></el-form-item>
          <el-form-item label="所有乘机人的含税总价上限（元）"
            ><el-input-number
              v-model="form.max_total"
              :min="1"
              :max="1000000"
              :precision="2"
              :step="50"
              controls-position="right"
          /></el-form-item>
          <el-form-item v-if="props.purpose === 'create'" label="任务名称"
            ><el-input
              v-model="form.name"
              placeholder="例如 北京到上海低价机票"
              maxlength="100"
          /></el-form-item>
          <el-form-item v-if="props.purpose === 'create'" label="检查间隔"
            ><el-select v-model="form.interval_seconds"
              ><el-option
                v-for="item in intervals"
                :key="item.value"
                :value="item.value"
                :label="item.label" /></el-select
          ></el-form-item>
          <el-form-item
            v-if="props.purpose === 'create'"
            label="找到符合预算的产品后"
          >
            <el-select v-model="form.mode"
              ><el-option label="核对预订信息" value="prepare" /><el-option
                label="自动提交一次订单，由我支付"
                value="auto_order" /><el-option
                label="记录航班参考报价"
                value="notify"
            /></el-select>
          </el-form-item>
          <el-form-item class="wide-field"
            ><el-checkbox v-model="form.allow_service_packages"
              >允许包含付费服务包，总价仍须在上限内</el-checkbox
            ></el-form-item
          >
          <div class="wide-field action-row">
            <el-button
              :loading="preparing"
              :disabled="busy && !preparing"
              @click="preview"
              >核对预订信息</el-button
            >
            <el-button
              v-if="props.purpose === 'create'"
              type="primary"
              :loading="creating"
              :disabled="busy && !creating"
              native-type="submit"
              >{{ route.params.id ? '保存修改' : '保存监控任务' }}</el-button
            >
            <el-button
              v-else
              type="primary"
              :disabled="busy || !form.flight_number"
              @click="openCreate"
              >创建机票任务</el-button
            >
          </div>
        </el-form>
        <div v-if="checkout" class="checkout-result">
          <el-descriptions title="预订核对" :column="2" border>
            <el-descriptions-item label="行程"
              >{{ query().origin_name }} →
              {{ query().destination_name }}</el-descriptions-item
            >
            <el-descriptions-item label="航班与日期"
              >{{ checkout.flight_number }} ·
              {{ checkout.departure_date }}</el-descriptions-item
            >
            <el-descriptions-item label="乘机人">{{
              checkout.selected_passengers?.join('、') || '请选择乘机人'
            }}</el-descriptions-item>
            <el-descriptions-item label="联系人">{{
              checkout.contact_ready ? '已匹配账户联系人' : '请完善账户联系人'
            }}</el-descriptions-item>
            <el-descriptions-item label="票面价合计"
              >¥{{ ticketTotal }}</el-descriptions-item
            >
            <el-descriptions-item label="税费合计"
              >¥{{ taxTotal }}</el-descriptions-item
            >
            <el-descriptions-item label="含税总价"
              ><strong
                >¥{{ checkout.total || '—' }}</strong
              ></el-descriptions-item
            >
            <el-descriptions-item label="预算"
              ><el-tag
                :type="
                  checkout.total_verified &&
                  Number(checkout.total) <= form.max_total
                    ? 'success'
                    : 'warning'
                "
                >{{
                  !checkout.total_verified
                    ? '总价未确认'
                    : Number(checkout.total) <= form.max_total
                      ? '在预算内'
                      : '超出预算'
                }}</el-tag
              ></el-descriptions-item
            >
          </el-descriptions>
          <div v-if="props.purpose === 'query'" class="action-row">
            <el-button
              type="primary"
              :loading="submitting"
              :disabled="busy || !canOrder"
              @click="placeOrder"
              >提交订单</el-button
            >
          </div>
          <el-alert
            v-if="manualOrder"
            :type="
              manualOrder.status === 'pending_payment' ? 'success' : 'warning'
            "
            :closable="false"
            class="order-result"
            :title="
              manualOrder.order_id
                ? `订单 ${manualOrder.order_id} · 待支付`
                : manualOrder.message
            "
          />
        </div>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api/flights'
import { useRouter, useRoute } from 'vue-router'
import { useFlightDraft } from '../stores/flightDraft'
import { sortFlights } from '../utils/flight-sort'

const props = defineProps({ purpose: { type: String, default: 'query' } })
const router = useRouter(),
  route = useRoute()
const draft = useFlightDraft()
const desktop = !!window.electronAPI?.flights
const cities = [
  ['BJS', '北京'],
  ['SHA', '上海'],
  ['CAN', '广州'],
  ['SZX', '深圳'],
  ['CTU', '成都'],
  ['HGH', '杭州'],
  ['CKG', '重庆'],
  ['WUH', '武汉'],
  ['SIA', '西安'],
  ['NKG', '南京'],
  ['CSX', '长沙'],
  ['KMG', '昆明'],
  ['XMN', '厦门'],
  ['TAO', '青岛'],
  ['TSN', '天津'],
  ['DLC', '大连'],
  ['SHE', '沈阳'],
  ['HRB', '哈尔滨'],
  ['HAK', '海口'],
  ['SYX', '三亚'],
  ['FOC', '福州'],
  ['CGO', '郑州'],
].map(([code, name]) => ({ code, name }))
const tomorrow = new Date(Date.now() + 86400000)
const dateString = `${tomorrow.getFullYear()}-${String(tomorrow.getMonth() + 1).padStart(2, '0')}-${String(tomorrow.getDate()).padStart(2, '0')}`
const form = reactive({
  provider: 'ctrip',
  origin: 'BJS',
  destination: 'SHA',
  departure_date: dateString,
  flight_number: '',
  offer_key: null,
  max_total: 800,
  name: '',
  interval_seconds: 300,
  mode: 'prepare',
  allow_service_packages: false,
})
const intervals = [
  { value: 60, label: '1 分钟' },
  { value: 180, label: '3 分钟' },
  { value: 300, label: '5 分钟' },
  { value: 600, label: '10 分钟' },
  { value: 1800, label: '30 分钟' },
  { value: 3600, label: '1 小时' },
]
const passengerText = ref('')
const error = ref('')
const flights = ref([])
const sortOrder = ref('default')
const sortedFlights = computed(() =>
  sortFlights(flights.value, sortOrder.value),
)
const queriedAt = ref('')
const checkout = ref(null)
const offers = ref([])
const loadingOffers = ref(false)
const submitting = ref(false)
const manualOrder = ref(null)
const canOrder = computed(() => {
  const value = checkout.value
  const attempted =
    manualOrder.value &&
    ['submitting', 'pending_payment', 'unknown'].includes(
      manualOrder.value.status,
    )
  return (
    !!value &&
    !attempted &&
    value.total_verified &&
    value.route_verified &&
    value.adult_only &&
    value.contact_ready &&
    !value.restricted &&
    (!value.service_package || form.allow_service_packages) &&
    Number(value.total) > 0 &&
    Number(value.total) <= Number(form.max_total)
  )
})
const ticketTotal = computed(() =>
  checkout.value?.offer?.base_price
    ? (
        Number(checkout.value.offer.base_price) *
        (checkout.value.selected_passengers?.length || 0)
      ).toFixed(2)
    : '—',
)
const taxTotal = computed(() =>
  checkout.value?.total && ticketTotal.value !== '—'
    ? (Number(checkout.value.total) - Number(ticketTotal.value)).toFixed(2)
    : '—',
)
const searching = ref(false),
  preparing = ref(false),
  creating = ref(false)
const busy = computed(
  () =>
    searching.value ||
    preparing.value ||
    creating.value ||
    loadingOffers.value ||
    submitting.value,
)
const query = () => ({
  provider: form.provider,
  origin: form.origin,
  destination: form.destination,
  origin_name: cities.find((c) => c.code === form.origin)?.name,
  destination_name: cities.find((c) => c.code === form.destination)?.name,
  departure_date: form.departure_date,
})
const booking = () => {
  const passengers = passengerText.value
    .split(/[,，、\n]/)
    .map((v) => v.trim())
    .filter(Boolean)
  if (!form.flight_number || !passengers.length || !form.max_total)
    throw new Error('请填写目标航班、平台已保存的乘机人姓名和含税总价上限')
  return {
    ...query(),
    flight_number: form.flight_number.trim(),
    offer_key: form.offer_key,
    passengers,
    max_total: Number(form.max_total).toFixed(2),
    allow_service_packages: form.allow_service_packages,
  }
}
async function perform(flag, operation) {
  if (flag.value) return
  error.value = ''
  flag.value = true
  try {
    return await operation()
  } catch (e) {
    error.value = e.message
  } finally {
    flag.value = false
  }
}
async function search() {
  return perform(searching, async () => {
    checkout.value = null
    flights.value = []
    offers.value = []
    form.offer_key = null
    const result = await api.search(query())
    flights.value = result.flights || []
    queriedAt.value = result.queried_at
  })
}
function selectFlight(row) {
  if (busy.value) return
  if (form.flight_number !== row.flight_number) {
    offers.value = []
    form.offer_key = null
    checkout.value = null
  }
  form.flight_number = row.flight_number
  if (!form.name)
    form.name = `${query().origin_name}到${query().destination_name} ${row.flight_number}`
}
async function loadOffers(row) {
  if (busy.value) return
  selectFlight(row)
  offers.value = []
  form.offer_key = null
  checkout.value = null
  return perform(loadingOffers, async () => {
    const result = await api.offers({
      ...query(),
      flight_number: row.flight_number,
    })
    offers.value = result.offers || []
  })
}
function selectOffer(row) {
  form.offer_key = row.offer_key
  checkout.value = null
}
async function preview() {
  return perform(preparing, async () => {
    checkout.value = null
    checkout.value = await api.prepare(booking())
  })
}
async function placeOrder() {
  if (!canOrder.value || busy.value) return
  return perform(submitting, async () => {
    manualOrder.value = await api.order({
      ...booking(),
      verified_total: checkout.value.total,
    })
  })
}
async function createMonitor() {
  return perform(creating, async () => {
    const config = {
      ...booking(),
      name:
        form.name.trim() ||
        `${query().origin_name}到${query().destination_name} ${form.flight_number}`,
      interval_seconds: form.interval_seconds,
      mode: form.mode,
    }
    if (route.params.id && props.purpose === 'create')
      await api.update(route.params.id, config)
    else await api.create(config)
    ElMessage.success('任务已保存')
    router.push('/tasks')
  })
}
function openCreate() {
  draft.set({
    ...form,
    passengers: passengerText.value
      .split(/[,，、\n]/)
      .map((v) => v.trim())
      .filter(Boolean),
  })
  router.push({
    path: '/create-task',
    query: { type: 'flight', fromquery: '1' },
  })
}
const formatTime = (value) =>
  value ? new Date(value).toLocaleTimeString('zh-CN') : ''
const rowClass = ({ row }) =>
  row.flight_number === form.flight_number ? 'flight-selected' : ''
watch(
  () => [form.provider, form.origin, form.destination, form.departure_date],
  () => {
    flights.value = []
    offers.value = []
    checkout.value = null
    form.flight_number = ''
    form.offer_key = null
    queriedAt.value = ''
    error.value = ''
  },
  { flush: 'sync' },
)
watch(
  () => [form.flight_number, passengerText.value, form.allow_service_packages],
  () => {
    checkout.value = null
    manualOrder.value = null
  },
)
watch(
  () => form.flight_number,
  () => {
    offers.value = []
    form.offer_key = null
  },
  { flush: 'sync' },
)
onMounted(async () => {
  if (props.purpose !== 'create') return
  try {
    const config = route.params.id
      ? (await api.get(route.params.id)).config
      : route.query.fromquery
        ? draft.value
        : null
    if (!config) return
    for (const key of [
      'provider',
      'origin',
      'destination',
      'departure_date',
      'name',
      'interval_seconds',
      'mode',
      'allow_service_packages',
    ]) {
      if (config[key] !== undefined) form[key] = config[key]
    }
    form.max_total = Number(config.max_total || 800)
    form.flight_number = config.flight_number || ''
    form.offer_key = config.offer_key || null
    passengerText.value = (config.passengers || []).join('、')
  } catch (e) {
    error.value = e.message
  }
})
</script>

<style scoped>
.flight-login {
  text-align: center;
}
.flight-login img {
  object-fit: contain;
  image-rendering: pixelated;
}
.flight-login p {
  margin: 12px 0;
}
.flight-workspace {
  max-width: 1360px;
  margin: 0 auto;
  padding: 8px 4px 28px;
  display: grid;
  gap: 18px;
}
.flight-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0 8px;
  gap: 20px;
}
.eyebrow {
  color: #5379a2;
  font-size: 12px;
  letter-spacing: 1px;
  margin-bottom: 9px;
}
h1 {
  color: #17314c;
  font-size: 28px;
  font-weight: 650;
  margin-bottom: 8px;
}
.flight-intro p,
.hint {
  color: #738295;
  font-size: 12px;
  line-height: 1.7;
}
.flight-intro p {
  font-size: 14px;
}
.flight-card {
  border-radius: 12px;
  border-color: #e4eaf1;
}
.provider-bar,
.section-heading,
.action-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.provider-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.provider-actions .el-button + .el-button {
  margin-left: 0;
}
.section-heading {
  font-weight: 600;
  color: #253c54;
}
.section-heading .hint {
  font-weight: normal;
}
.quote-tools {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.order-result {
  margin-top: 16px;
}
.search-form {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr auto;
  gap: 16px;
  margin-top: 22px;
}
.booking-form {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 20px;
}
.search-form :deep(.el-select),
.search-form :deep(.el-date-editor),
.booking-form :deep(.el-select),
.booking-form :deep(.el-input-number) {
  width: 100%;
}
.wide-field {
  grid-column: 1 / -1;
}
.action-row {
  justify-content: flex-end;
  margin-top: 16px;
}
.task-note {
  background: #f4f8fd;
  padding: 12px 16px;
  border-radius: 7px;
  color: #506780;
  font-size: 13px;
  line-height: 1.7;
}
.checkout-result {
  margin-top: 20px;
  border-top: 1px solid #edf0f4;
  padding-top: 18px;
  color: #5c6c7c;
  font-size: 13px;
}
.checkout-result strong {
  color: #16735a;
  font-size: 24px;
}
.footer-hint {
  margin-top: 18px;
}
:deep(.flight-selected) {
  --el-table-tr-bg-color: #eef6ff;
}
@media (max-width: 1050px) {
  .booking-form {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .search-form {
    grid-template-columns: 1fr 1fr;
  }
}
@media (max-width: 640px) {
  .flight-intro,
  .provider-bar {
    align-items: flex-start;
    flex-direction: column;
  }
  .booking-form,
  .search-form {
    grid-template-columns: 1fr;
  }
}
</style>
