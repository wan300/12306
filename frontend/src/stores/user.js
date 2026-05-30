import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import api from '../api'

export const useUserStore = defineStore('user', () => {
  const currentUser = ref(null)
  const loginStatus = ref(null)
  const accessToken = ref(api.getAccessToken())
  const initialized = ref(false)

  const isLoggedIn = computed(() => {
    return !!accessToken.value && !!currentUser.value && loginStatus.value?.is_logged_in !== false
  })

  const hasToken = computed(() => !!accessToken.value)

  // 监听 loginStatus 变化，持久化到 localStorage
  watch(loginStatus, (newStatus) => {
    if (currentUser.value && newStatus) {
      localStorage.setItem(`loginStatus_${currentUser.value.id}`, JSON.stringify(newStatus))
    }
  }, { deep: true })

  // 监听 currentUser 变化
  watch(currentUser, (newUser) => {
    if (newUser) {
      localStorage.setItem('currentUserId', newUser.id.toString())
      localStorage.setItem('currentUser', JSON.stringify(newUser))
    } else {
      localStorage.removeItem('currentUserId')
      localStorage.removeItem('currentUser')
    }
  }, { deep: true })

  const setAuthSession = (auth) => {
    if (!auth?.access_token || !auth?.user) return

    accessToken.value = auth.access_token
    api.setAccessToken(auth.access_token)

    currentUser.value = auth.user
    loginStatus.value = {
      is_logged_in: true,
      username: auth.user.username,
      railway_username: auth.user.railway_username,
      login_time: auth.user.login_time || null
    }

    localStorage.setItem('currentUserId', auth.user.id.toString())
    localStorage.setItem('currentUser', JSON.stringify(auth.user))
    localStorage.setItem(`loginStatus_${auth.user.id}`, JSON.stringify(loginStatus.value))
  }

  const clearAuthSession = () => {
    const oldUserId = currentUser.value?.id
    accessToken.value = ''
    api.clearAccessToken()

    currentUser.value = null
    loginStatus.value = null

    if (oldUserId) {
      localStorage.removeItem(`loginStatus_${oldUserId}`)
    }
    localStorage.removeItem('currentUserId')
    localStorage.removeItem('currentUser')
  }

  async function logout() {
    if (!accessToken.value) {
      clearAuthSession()
      return
    }

    try {
      await api.logout()
    } catch (error) {
      console.error('登出失败:', error)
    } finally {
      clearAuthSession()
    }
  }

  // 恢复上次选择的用户
  async function restoreUser() {
    if (initialized.value) return
    initialized.value = true

    const token = api.getAccessToken()
    accessToken.value = token

    if (!token) {
      clearAuthSession()
      return
    }

    try {
      const res = await api.getCurrentUser()
      if (res.success && res.data) {
        currentUser.value = res.data
        loginStatus.value = {
          is_logged_in: true,
          username: res.data.username,
          railway_username: res.data.railway_username,
          login_time: res.data.login_time || null
        }
        localStorage.setItem('currentUserId', res.data.id.toString())
        localStorage.setItem('currentUser', JSON.stringify(res.data))
        localStorage.setItem(`loginStatus_${res.data.id}`, JSON.stringify(loginStatus.value))
        return
      }
    } catch (e) {
      console.error('恢复令牌用户失败:', e)
    }

    clearAuthSession()
  }

  return {
    currentUser,
    loginStatus,
    accessToken,
    isLoggedIn,
    hasToken,
    initialized,
    logout,
    restoreUser,
    setAuthSession,
    clearAuthSession
  }
})
