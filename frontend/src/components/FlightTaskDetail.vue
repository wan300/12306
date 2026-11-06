<template>
  <el-card v-loading="loading">
    <template #header
      ><div class="detail-heading">
        <strong>{{ task?.name || "机票任务详情" }}</strong
        ><el-button @click="router.push('/tasks')">返回任务列表</el-button>
      </div></template
    >
    <el-alert v-if="error" :title="error" type="error" />
    <template v-if="task">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="平台">{{
          task.provider === "ctrip" ? "携程" : "去哪儿"
        }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{
          task.statusLabel
        }}</el-descriptions-item>
        <el-descriptions-item label="行程"
          >{{ task.origin }} → {{ task.destination }}</el-descriptions-item
        >
        <el-descriptions-item label="日期与航班"
          >{{ task.date }} · {{ task.target }}</el-descriptions-item
        >
        <el-descriptions-item label="乘机人">{{
          task.record.config.passengers.join("、")
        }}</el-descriptions-item>
        <el-descriptions-item label="检查间隔"
          >{{ task.record.config.interval_seconds }} 秒</el-descriptions-item
        >
        <el-descriptions-item label="含税预算"
          >¥{{ task.budget }}</el-descriptions-item
        >
        <el-descriptions-item label="执行方式">{{
          modes[task.record.config.mode]
        }}</el-descriptions-item>
        <el-descriptions-item label="最近含税总价">{{
          task.total ? "¥" + task.total : "—"
        }}</el-descriptions-item>
        <el-descriptions-item
          v-if="task.indicativePrice"
          label="最近参考价（不含税）"
          >¥{{ task.indicativePrice }}</el-descriptions-item
        >
        <el-descriptions-item label="订单号">{{
          task.orderId || "—"
        }}</el-descriptions-item>
        <el-descriptions-item label="进展" :span="2">{{
          task.message
        }}</el-descriptions-item>
      </el-descriptions>
      <div class="detail-actions">
        <el-button v-if="task.actions.edit" @click="router.push(task.editPath)"
          >编辑</el-button
        >
        <el-button
          v-for="action in ['start', 'pause', 'remove']"
          v-show="task.actions[action]"
          :key="action"
          :loading="processing"
          @click="control(action)"
          >{{ labels[action] }}</el-button
        >
      </div>
    </template>
  </el-card>
</template>
<script setup>
import { ref, onMounted, onUnmounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import api from "../api/flights";
import { useTickets } from "../stores/tickets";
import { normalizeTask } from "../utils/ticket-task";
const route = useRoute(),
  router = useRouter(),
  store = useTickets();
const task = ref(null),
  error = ref(""),
  loading = ref(false),
  processing = ref(false);
const labels = { start: "启动", pause: "暂停", remove: "删除" };
const modes = {
  notify: "记录报价",
  prepare: "核对预订信息",
  auto_order: "自动提交订单",
};
async function load() {
  if (loading.value) return;
  loading.value = true;
  try {
    task.value = normalizeTask("flight", await api.get(route.params.id));
    error.value = "";
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}
async function control(action) {
  processing.value = true;
  try {
    await store.control(task.value, action);
    if (action === "remove") router.push("/tasks");
    else await load();
  } catch (e) {
    ElMessage.error(e.message);
  } finally {
    processing.value = false;
  }
}
let timer;
onMounted(() => {
  load();
  timer = setInterval(load, 5000);
});
onUnmounted(() => clearInterval(timer));
</script>
<style scoped>
.detail-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.detail-actions {
  display: flex;
  gap: 12px;
  margin-top: 20px;
}
</style>
