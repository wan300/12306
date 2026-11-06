<template>
  <el-card class="account-card" shadow="never">
    <template #header
      ><div class="account-heading">
        <strong>12306</strong
        ><el-button text :loading="loading" @click="start">重新扫码</el-button>
      </div></template
    >
    <div class="account-body">
      <el-result
        v-if="user.isLoggedIn && state !== 'pending' && !loading"
        icon="success"
        title="已登录"
        :sub-title="
          user.currentUser?.railway_username || user.currentUser?.username
        "
      >
        <template #extra
          ><el-button text @click="logout">退出 12306</el-button></template
        >
      </el-result>
      <template v-else-if="state === 'pending'"
        ><img
          :src="'data:image/png;base64,' + image"
          alt="12306 登录二维码"
          width="210"
          height="210"
        />
        <p>{{ message || '使用12306 App扫码并确认' }}</p></template
      >
      <el-skeleton v-else-if="loading" :rows="5" animated />
      <el-empty
        v-else
        :description="message || '二维码已过期，请刷新'"
        :image-size="100"
      />
      <el-button
        v-if="user.isLoggedIn && state === 'pending'"
        text
        @click="cancel"
        >返回已登录账号</el-button
      >
    </div>
  </el-card>
</template>
<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import api from '../api'
import { useUserStore } from '../stores/user'
const user = useUserStore()
const state = ref('idle'),
  loading = ref(false),
  image = ref(''),
  message = ref('')
let timer,
  attempt = 0,
  disposed = false
async function poll(id, challenge) {
  try {
    const result = await api.checkLoginQRCodeStatus(challenge)
    if (disposed || id !== attempt) return
    const data = result.data
    if (!result.success || !data)
      throw new Error(result.message || '登录状态读取失败')
    message.value = data.message
    if (data.status === 2 && data.is_success && data.auth?.access_token) {
      user.setAuthSession(data.auth)
      state.value = 'authenticated'
      return
    }
    if ([3, 5].includes(data.status)) {
      state.value = 'expired'
      return
    }
    timer = setTimeout(() => poll(id, challenge), 2000)
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
  message.value = ''
  state.value = 'loading'
  try {
    const result = await api.createLoginQRCode()
    if (disposed || id !== attempt) return
    if (!result.success || !result.data)
      throw new Error(result.message || '二维码获取失败')
    image.value = result.data.image_base64
    state.value = 'pending'
    timer = setTimeout(() => poll(id, result.data.challenge_id), 2000)
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
async function logout() {
  await user.logout()
  await start()
}
onMounted(async () => {
  await user.restoreUser()
  if (!disposed) {
    if (user.isLoggedIn) state.value = 'authenticated'
    else await start()
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
}
</style>
