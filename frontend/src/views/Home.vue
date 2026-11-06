<template>
  <div class="ticket-home">
    <el-alert
      v-for="error in store.errors"
      :key="error"
      :title="error"
      type="warning"
      :closable="false"
    />
    <div class="home-banner">
      <h1>票务助手</h1>
      <p>统一查询机票与火车票，管理预订和抢票任务。</p>
    </div>
    <el-row :gutter="16">
      <el-col v-for="item in stats" :key="item.label" :xs="12" :md="6"
        ><el-card shadow="never" class="stat-card"
          ><strong>{{ item.value }}</strong
          ><span>{{ item.label }}</span></el-card
        ></el-col
      >
    </el-row>
    <el-card shadow="never">
      <div class="home-actions">
        <el-button @click="router.push('/login')">扫码登录</el-button
        ><el-button type="primary" @click="router.push('/query')"
          >票务查询</el-button
        ><el-button @click="router.push('/create-task')">创建任务</el-button
        ><el-button @click="router.push('/notification')">通知配置</el-button>
      </div>
    </el-card>
    <el-card shadow="never">
      <template #header
        ><div class="recent-heading">
          <strong>最近任务</strong
          ><el-button text @click="router.push('/tasks')">全部任务</el-button>
        </div></template
      >
      <el-table :data="store.tasks.slice(0, 8)" row-key="key">
        <el-table-column label="任务" min-width="160"
          ><template #default="{ row }"
            ><el-link type="primary" @click="router.push(row.detailPath)">{{
              row.name
            }}</el-link></template
          ></el-table-column
        >
        <el-table-column label="票种" width="90"
          ><template #default="{ row }">{{
            row.kind === "rail" ? "火车票" : "机票"
          }}</template></el-table-column
        >
        <el-table-column label="行程" min-width="170"
          ><template #default="{ row }"
            >{{ row.origin }} → {{ row.destination }} · {{ row.date }}</template
          ></el-table-column
        >
        <el-table-column prop="statusLabel" label="状态" width="130" />
      </el-table>
    </el-card>
  </div>
</template>
<script setup>
import { computed, onMounted, onUnmounted } from "vue";
import { useRouter } from "vue-router";
import { useTickets } from "../stores/tickets";
const store = useTickets(),
  router = useRouter();
const stats = computed(() => [
  { label: "任务总数", value: store.tasks.length },
  {
    label: "运行中",
    value: store.tasks.filter((t) => t.status === "running").length,
  },
  {
    label: "机票预算达标",
    value: store.tasks.filter(
      (t) => t.kind === "flight" && t.sourceStatus === "ready",
    ).length,
  },
  {
    label: "订单待支付",
    value: store.tasks.filter(
      (t) =>
        t.sourceStatus === "pending_payment" ||
        (t.kind === "rail" && t.sourceStatus === "success"),
    ).length,
  },
]);
let timer;
onMounted(() => {
  store.load();
  timer = setInterval(store.load, 5000);
});
onUnmounted(() => clearInterval(timer));
</script>
<style scoped>
.ticket-home {
  display: grid;
  gap: 20px;
}
.home-banner {
  padding: 24px;
  border-radius: 12px;
  background: #eef6ff;
  color: #17314c;
}
.home-banner p {
  margin-top: 8px;
  color: #5c738b;
}
.stat-card {
  margin-bottom: 12px;
}
.stat-card strong {
  display: block;
  font-size: 30px;
  color: #409eff;
}
.stat-card span {
  color: #738295;
}
.home-actions,
.recent-heading {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: center;
}
.recent-heading {
  justify-content: space-between;
}
</style>
