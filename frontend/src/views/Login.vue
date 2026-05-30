<template>
  <div class="login-page">
    <el-card class="login-card">
      <template #header>
        <div class="card-header">
          <div>
            <div class="title">12306 扫码登录</div>
            <div class="subtitle">扫码即平台登录，自动绑定当前12306账号</div>
          </div>
          <el-button type="primary" plain @click="refreshQRCode" :loading="loginState === 'loading'">
            刷新二维码
          </el-button>
        </div>
      </template>

      <div class="qrcode-container">
        <template v-if="loginState === 'idle'">
          <el-empty description="准备开始扫码登录">
            <template #image>
              <el-icon size="80" color="#909399"><Iphone /></el-icon>
            </template>
            <el-button type="primary" @click="startLogin">获取二维码</el-button>
          </el-empty>
        </template>

        <template v-else-if="loginState === 'loading'">
          <el-icon class="is-loading" size="60" color="#409EFF">
            <Loading />
          </el-icon>
          <p>{{ statusMessage }}</p>
        </template>

        <template v-else-if="loginState === 'qrcode'">
          <div class="qrcode-wrapper">
            <img :src="'data:image/png;base64,' + qrcodeImage" alt="登录二维码" />
            <div v-if="qrcodeExpired" class="qrcode-expired" @click="refreshQRCode">
              <el-icon size="40"><RefreshRight /></el-icon>
              <span>二维码已过期，点击刷新</span>
            </div>
          </div>
          <p>{{ statusMessage }}</p>
          <p class="tips">请使用 12306 APP 扫描并确认登录</p>
        </template>

        <template v-else-if="loginState === 'success'">
          <el-result icon="success" title="登录成功">
            <template #sub-title>
              欢迎，{{ loginUsername }}
            </template>
            <template #extra>
              <el-button type="primary" @click="router.replace('/')">进入系统</el-button>
            </template>
          </el-result>
        </template>

        <template v-else-if="loginState === 'error'">
          <el-result icon="error" title="登录失败">
            <template #sub-title>
              {{ statusMessage }}
            </template>
            <template #extra>
              <el-button type="primary" @click="refreshQRCode">重试</el-button>
            </template>
          </el-result>
        </template>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import api from '../api'

const router = useRouter()
const userStore = useUserStore()

const loginState = ref('idle') // idle, loading, qrcode, success, error
const statusMessage = ref('')
const qrcodeImage = ref('')
const challengeId = ref('')
const qrcodeExpired = ref(false)
const loginUsername = ref('')
let pollTimer = null

onMounted(async () => {
  if (userStore.isLoggedIn && userStore.currentUser) {
    router.replace('/')
    return
  }

  await startLogin()
})

onUnmounted(() => {
  stopPolling()
})

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const startLogin = async () => {
  stopPolling()
  loginState.value = 'loading'
  statusMessage.value = '正在获取二维码...'
  qrcodeExpired.value = false
  challengeId.value = ''

  try {
    const res = await api.createLoginQRCode()
    if (!res.success || !res.data) {
      loginState.value = 'error'
      statusMessage.value = res.message || '获取二维码失败'
      return
    }

    challengeId.value = res.data.challenge_id
    qrcodeImage.value = res.data.image_base64
    loginState.value = 'qrcode'
    statusMessage.value = '等待扫码...'
    startPolling()
  } catch (error) {
    loginState.value = 'error'
    statusMessage.value = error.message
  }
}

const startPolling = () => {
  stopPolling()

  pollTimer = setInterval(async () => {
    if (!challengeId.value) {
      stopPolling()
      return
    }

    try {
      const res = await api.checkLoginQRCodeStatus(challengeId.value)
      if (!res.success || !res.data) {
        return
      }

      const data = res.data
      statusMessage.value = data.message

      if (data.status === 1) {
        statusMessage.value = '已扫码，请在手机上确认...'
      } else if (data.status === 2 && data.is_success && data.auth?.access_token) {
        stopPolling()
        userStore.setAuthSession(data.auth)
        loginUsername.value = data.auth.user?.railway_username || data.auth.user?.username || '用户'
        loginState.value = 'success'
        setTimeout(() => {
          router.replace('/')
        }, 600)
      } else if (data.status === 3) {
        stopPolling()
        qrcodeExpired.value = true
        loginState.value = 'qrcode'
        statusMessage.value = '二维码已过期'
      } else if (data.status === 5) {
        stopPolling()
        loginState.value = 'error'
      }
    } catch (error) {
      stopPolling()
      loginState.value = 'error'
      statusMessage.value = error.message || '轮询登录状态失败'
    }
  }, 2000)
}

const refreshQRCode = async () => {
  await startLogin()
}
</script>

<style scoped>
.login-page {
  padding: 0;
  max-width: 720px;
  margin: 0 auto;
}

.login-card {
  min-height: 520px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.subtitle {
  margin-top: 6px;
  font-size: 13px;
  color: #909399;
}

.qrcode-container {
  min-height: 420px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}

.qrcode-wrapper {
  position: relative;
  width: 220px;
  height: 220px;
  margin-bottom: 16px;
}

.qrcode-wrapper img {
  width: 100%;
  height: 100%;
}

.qrcode-expired {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.72);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: white;
  cursor: pointer;
  gap: 8px;
}

.tips {
  color: #909399;
  font-size: 14px;
}
</style>
