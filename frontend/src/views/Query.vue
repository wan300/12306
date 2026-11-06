<template>
  <div>
    <el-tabs v-model="type">
      <el-tab-pane label="火车票" name="rail" />
      <el-tab-pane label="机票" name="flight" />
    </el-tabs>
    <KeepAlive
      ><component
        :is="type === 'flight' ? FlightWorkspace : RailQuery"
        :key="type"
    /></KeepAlive>
  </div>
</template>
<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import RailQuery from '../components/RailQuery.vue'
import FlightWorkspace from '../components/FlightWorkspace.vue'
const route = useRoute(),
  router = useRouter()
const type = computed({
  get: () => (route.query.type === 'flight' ? 'flight' : 'rail'),
  set: (value) => router.replace({ query: { ...route.query, type: value } }),
})
</script>
