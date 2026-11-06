<template>
  <RailTaskDetail
    v-if="kind === 'rail' && user.isLoggedIn"
    :key="route.params.id"
  />
  <el-alert
    v-else-if="kind === 'rail'"
    title="请先登录 12306 查看火车票任务"
    type="info"
    :closable="false"
  />
  <FlightTaskDetail v-else :key="route.params.id" />
</template>
<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '../stores/user'
import RailTaskDetail from '../components/RailTaskDetail.vue'
import FlightTaskDetail from '../components/FlightTaskDetail.vue'
const route = useRoute(),
  user = useUserStore()
const kind = computed(() =>
  route.params.kind === 'flight' ? 'flight' : 'rail',
)
</script>
