<template>
  <el-card>
    <template #header>
      <div class="task-toolbar">
        <span>任务列表</span>
        <div class="task-filters">
          <el-select
            v-model="kind"
            placeholder="全部票种"
            aria-label="票种筛选"
            style="width: 120px"
            ><el-option label="全部票种" value="" /><el-option
              label="火车票"
              value="rail" /><el-option label="机票" value="flight"
          /></el-select>
          <el-select
            v-model="provider"
            placeholder="全部平台"
            aria-label="平台筛选"
            style="width: 120px"
            ><el-option label="全部平台" value="" /><el-option
              v-for="(name, key) in providers"
              :key="key"
              :label="name"
              :value="key"
          /></el-select>
          <el-select
            v-model="status"
            placeholder="全部状态"
            aria-label="状态筛选"
            style="width: 130px"
            ><el-option label="全部状态" value="" /><el-option
              v-for="(name, key) in statuses"
              :key="key"
              :label="name"
              :value="key"
          /></el-select>
          <el-button :loading="store.loading" @click="store.load"
            >刷新</el-button
          >
          <el-button type="primary" @click="router.push('/create-task')"
            >创建任务</el-button
          >
        </div>
      </div>
    </template>
    <el-alert
      v-for="error in store.errors"
      :key="error"
      :title="error"
      type="warning"
      :closable="false"
    />
    <el-table :data="filtered" row-key="key" v-loading="store.loading" stripe>
      <el-table-column label="任务" min-width="170"
        ><template #default="{ row }"
          ><el-link type="primary" @click="router.push(row.detailPath)">{{
            row.name
          }}</el-link></template
        ></el-table-column
      >
      <el-table-column label="票种 / 平台" min-width="120"
        ><template #default="{ row }"
          >{{ row.kind === "rail" ? "火车票" : "机票" }} ·
          {{ providers[row.provider] }}</template
        ></el-table-column
      >
      <el-table-column label="行程" min-width="160"
        ><template #default="{ row }"
          >{{ row.origin }} → {{ row.destination }}
          <div class="task-secondary">
            {{ row.date }} · {{ row.target }}
          </div></template
        ></el-table-column
      >
      <el-table-column label="状态" min-width="115"
        ><template #default="{ row }"
          ><el-tag
            :type="
              row.status === 'running'
                ? 'success'
                : ['unknown', 'failed'].includes(row.status)
                  ? 'danger'
                  : 'info'
            "
            >{{ row.statusLabel }}</el-tag
          ></template
        ></el-table-column
      >
      <el-table-column label="进展" min-width="200"
        ><template #default="{ row }"
          >{{ row.message }}
          <div v-if="row.total" class="task-secondary">
            含税 ¥{{ row.total }} / 预算 ¥{{ row.budget }}
          </div>
          <div v-else-if="row.indicativePrice" class="task-secondary">
            参考价 ¥{{ row.indicativePrice }}（不含税）
          </div>
          <div v-if="row.orderId">订单 {{ row.orderId }}</div></template
        ></el-table-column
      >
      <el-table-column label="操作" min-width="245"
        ><template #default="{ row }">
          <el-button
            v-if="row.actions.edit"
            text
            @click="router.push(row.editPath)"
            >编辑</el-button
          >
          <el-button
            v-for="action in ['start', 'pause', 'cancel', 'remove']"
            v-show="row.actions[action]"
            :key="action"
            text
            :type="
              action === 'remove' || action === 'cancel' ? 'danger' : 'primary'
            "
            :loading="processing[row.key]"
            @click="control(row, action)"
            >{{ actionNames[action] }}</el-button
          >
        </template></el-table-column
      >
    </el-table>
  </el-card>
</template>
<script setup>
import { ref, computed, onMounted, onUnmounted } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useTickets } from "../stores/tickets";
const store = useTickets(),
  router = useRouter();
const kind = ref(""),
  provider = ref(""),
  status = ref(""),
  processing = ref({});
const providers = { 12306: "12306", ctrip: "携程", qunar: "去哪儿" };
const statuses = {
  pending: "待运行",
  running: "运行中",
  paused: "已暂停",
  ready: "预算达标",
  pending_payment: "订单待支付",
  unknown: "结果待核对",
  success: "订单已生成",
  failed: "失败",
  cancelled: "已取消",
};
const actionNames = {
  start: "启动",
  pause: "暂停",
  cancel: "取消",
  remove: "删除",
};
const filtered = computed(() =>
  store.tasks.filter(
    (row) =>
      (!kind.value || row.kind === kind.value) &&
      (!provider.value || row.provider === provider.value) &&
      (!status.value || row.status === status.value),
  ),
);
async function control(row, action) {
  if (processing.value[row.key]) return;
  processing.value[row.key] = true;
  try {
    await store.control(row, action);
  } catch (error) {
    ElMessage.error(error.message);
  } finally {
    processing.value[row.key] = false;
  }
}
let timer;
onMounted(() => {
  store.load();
  timer = setInterval(store.load, 5000);
});
onUnmounted(() => clearInterval(timer));
</script>
<style scoped>
.task-toolbar,
.task-filters {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.task-toolbar {
  justify-content: space-between;
}
.task-secondary {
  color: #738295;
  font-size: 12px;
  margin-top: 5px;
}
</style>
