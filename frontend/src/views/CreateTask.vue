<template>
  <div>
    <el-tabs v-model="type">
      <el-tab-pane
        label="火车票"
        name="rail"
        :disabled="editing && type !== 'rail'"
      />
      <el-tab-pane
        label="机票"
        name="flight"
        :disabled="editing && type !== 'flight'"
      />
    </el-tabs>
    <el-alert
      v-if="type === 'rail' && !user.isLoggedIn"
      title="请先在扫码登录页登录 12306，再创建火车票任务"
      type="info"
      :closable="false"
    />
    <KeepAlive>
      <component
        v-if="type === 'flight' || user.isLoggedIn"
        :is="type === 'flight' ? FlightWorkspace : RailTaskForm"
        :key="
          type +
          ':' +
          (route.params.id || 'new') +
          ':' +
          (type === 'rail' ? user.currentUser?.id : 'local')
        "
        purpose="create"
      />
    </KeepAlive>
  </div>
</template>
<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import RailTaskForm from '../components/RailTaskForm.vue'
import FlightWorkspace from '../components/FlightWorkspace.vue'
const route = useRoute(),
  router = useRouter(),
  user = useUserStore()
const editing = computed(() => !!route.params.id)
const type = computed({
  get: () =>
    (route.params.kind || route.query.type) === 'flight' ? 'flight' : 'rail',
  set: (value) => {
    if (!editing.value)
      router.replace({ query: { ...route.query, type: value } })
  },
})
</script>
