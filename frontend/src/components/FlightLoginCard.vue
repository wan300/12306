<template>
  <el-card class="account-card" shadow="never">
    <template #header
      ><div class="account-heading">
        <strong>{{ name }}</strong
        ><el-button text :loading="loading" @click="start">{{
          state === 'authenticated' ? '重新扫码' : '刷新二维码'
        }}</el-button>
      </div></template
    >
    <div class="account-body">
      <el-result
        v-if="state === 'authenticated'"
        icon="success"
        title="已登录"
        :sub-title="name + '账号可用'"
      />
      <template v-else-if="state === 'pending'"
        ><img
          :src="image"
          :alt="name + '登录二维码'"
          width="210"
          height="210"
        />
        <p>使用{{ name }} App 扫码并确认</p></template
      >
      <el-skeleton v-else-if="loading" :rows="5" animated />
      <el-empty
        v-else
        :description="message || '点击刷新二维码登录'"
        :image-size="100"
      />
      <el-button
        v-if="wasAuthenticated && state !== 'authenticated'"
        text
        @click="cancel"
        >返回已登录账号</el-button
      >
    </div>
  </el-card>
</template>
<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import api from '../api/flights'
const props = defineProps({ provider: { type: String, required: true } })
const name = computed(() => (props.provider === 'ctrip' ? '携程' : '去哪儿'))
const state = ref('loading'),
  loading = ref(false),
  image = ref(''),
  message = ref(''),
  wasAuthenticated = ref(false)
let timer,
  attempt = 0,
  disposed = false
async function poll(id) {
  try {
    const result = await api.loginStatus(props.provider)
    if (disposed || id !== attempt) return
    state.value = result.status
    if (result.status === 'authenticated') {
      wasAuthenticated.value = true
      return
    }
    if (result.status === 'pending') timer = setTimeout(() => poll(id), 1500)
    else
      message.value =
        result.status === 'expired'
          ? '二维码已过期，请刷新'
          : '请重新获取二维码'
  } catch (error) {
    if (id === attempt && !disposed) {
      state.value = 'error'
      message.value = error.message
    }
  }
}
async function start() {
  const id = ++attempt
  clearTimeout(timer)
  loading.value = true
  state.value = 'loading'
  message.value = ''
  try {
    const result = await api.loginQr(props.provider)
    if (disposed || id !== attempt) return
    image.value = result.image
    state.value = result.status
    if (result.status === 'pending') timer = setTimeout(() => poll(id), 1500)
  } catch (error) {
    if (id === attempt && !disposed) {
      state.value = 'error'
      message.value = error.message
    }
  } finally {
    if (id === attempt) loading.value = false
  }
}
function cancel() {
  attempt++
  clearTimeout(timer)
  loading.value = false
  state.value = 'authenticated'
}
onMounted(async () => {
  if (!window.electronAPI?.flights) {
    state.value = 'error'
    message.value = '请在桌面应用中连接机票平台'
    return
  }
  try {
    const result = await api.session(props.provider)
    if (disposed) return
    state.value = result.status
    if (result.status === 'authenticated') wasAuthenticated.value = true
    else await start()
  } catch (error) {
    state.value = 'error'
    message.value = error.message
  }
})
onUnmounted(() => {
  disposed = true
  attempt++
  clearTimeout(timer)
})
</script>
<style scoped>
.account-card {
  min-height: 390px;
  border-radius: 12px;
}
.account-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.account-body {
  min-height: 275px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
}
.account-body p {
  color: #738295;
  font-size: 13px;
}
.account-body img {
  object-fit: contain;
  image-rendering: pixelated;
}
</style>
