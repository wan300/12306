<template>
  <el-config-provider :locale="zhCn">
    <div class="app-container">
      <el-container>
        <el-header>
          <div class="header-content">
            <div class="logo-area">
              <el-button
                class="collapse-btn"
                :icon="collapseIcon"
                text
                @click="toggleCollapse"
              />
              <div class="logo">
                <span>{{ pageTitle }}</span>
              </div>
            </div>
          </div>
        </el-header>

        <el-container class="main-container">
          <div
            v-if="isMobile && !isCollapse"
            class="mobile-mask"
            @click="toggleCollapse"
          />

          <el-aside :width="asideWidth">
            <div class="aside-layout">
              <el-menu
                :default-active="route.path"
                router
                class="side-menu"
                :collapse="isCollapse"
                :collapse-transition="false"
              >
                <el-menu-item index="/">
                  <el-icon><HomeFilled /></el-icon>
                  <template #title>首页</template>
                </el-menu-item>
                <el-menu-item v-if="!userStore.isLoggedIn" index="/login">
                  <el-icon><User /></el-icon>
                  <template #title>扫码登录</template>
                </el-menu-item>
                <el-menu-item index="/tasks">
                  <el-icon><List /></el-icon>
                  <template #title>任务列表</template>
                </el-menu-item>
                <el-menu-item index="/create-task">
                  <el-icon><Plus /></el-icon>
                  <template #title>创建任务</template>
                </el-menu-item>
                <el-menu-item index="/query">
                  <el-icon><Search /></el-icon>
                  <template #title>车票查询</template>
                </el-menu-item>
                <el-menu-item index="/notification">
                  <el-icon><Bell /></el-icon>
                  <template #title>通知配置</template>
                </el-menu-item>
              </el-menu>

              <div
                v-if="userStore.currentUser && userStore.isLoggedIn"
                class="user-info-footer"
              >
                <el-dropdown placement="top" style="width: 100%;">
                  <div class="user-dropdown-footer" :class="{ collapsed: isCollapse }">
                    <el-avatar :size="32" class="user-avatar">
                      {{ displayAccountInitial }}
                    </el-avatar>
                    <div v-if="!isCollapse" class="user-details">
                      <span class="username-text">{{ displayAccountName }}</span>
                    </div>
                    <el-icon v-if="!isCollapse" class="dropdown-icon"><ArrowUp /></el-icon>
                  </div>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item @click="handleLogout">
                        <el-icon><SwitchButton /></el-icon>
                        退出登录
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </div>
          </el-aside>

          <el-main>
            <router-view />
          </el-main>
        </el-container>
      </el-container>
    </div>
  </el-config-provider>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, Expand, Fold } from '@element-plus/icons-vue'
import { useUserStore } from './stores/user'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const isCollapse = ref(false)
const isMobile = ref(false)

const asideWidth = computed(() => {
  if (isMobile.value) {
    return isCollapse.value ? '0px' : '200px'
  }
  return isCollapse.value ? '64px' : '200px'
})

const collapseIcon = computed(() => (isCollapse.value ? Expand : Fold))

const displayAccountName = computed(() => {
  if (!userStore.currentUser) return ''
  return userStore.currentUser.railway_username || userStore.currentUser.username || ''
})

const displayAccountInitial = computed(() => {
  return displayAccountName.value ? displayAccountName.value[0] : 'U'
})

const pageTitle = computed(() => {
  const path = route.path
  if (path === '/') return '首页'
  if (path === '/login') return '扫码登录'
  if (path === '/tasks') return '任务列表'
  if (path === '/create-task') return '创建任务'
  if (path === '/query') return '车票查询'
  if (path === '/notification') return '通知配置'
  if (path.startsWith('/task/')) return '任务详情'
  if (path.startsWith('/edit-task/')) return '编辑任务'
  return '12306 自动化抢票系统'
})

const toggleCollapse = () => {
  isCollapse.value = !isCollapse.value
}

const checkScreenSize = () => {
  const mobile = window.innerWidth < 768
  isMobile.value = mobile
  isCollapse.value = mobile
}

watch(
  () => route.path,
  () => {
    if (isMobile.value) {
      isCollapse.value = true
    }
  }
)

onMounted(async () => {
  await userStore.restoreUser()
  checkScreenSize()
  window.addEventListener('resize', checkScreenSize)
})

onUnmounted(() => {
  window.removeEventListener('resize', checkScreenSize)
})

const handleLogout = async () => {
  await userStore.logout()
  router.replace('/login')
}
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html,
body,
#app {
  height: 100%;
}

body {
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB',
    'Microsoft YaHei', '微软雅黑', Arial, sans-serif;
}

.app-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.el-container {
  height: 100%;
}

.main-container {
  position: relative;
  min-height: 0;
}

.el-header {
  background: linear-gradient(90deg, #409eff 0%, #3a8ee6 100%);
  color: white;
  display: flex;
  align-items: center;
  padding: 0 20px;
  height: 50px;
  flex-shrink: 0;
}

.header-content {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.logo-area {
  display: flex;
  align-items: center;
  gap: 12px;
}

.collapse-btn {
  color: white !important;
  width: 32px;
  height: 32px;
}

.logo {
  display: flex;
  align-items: center;
  font-size: 18px;
  font-weight: 600;
}

.el-aside {
  background: #fff;
  border-right: 1px solid #e4e7ed;
  transition: width 0.25s ease;
  overflow: hidden;
}

.aside-layout {
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.side-menu {
  border-right: none;
  background: transparent;
  flex: 1;
  overflow-y: auto;
}

.el-menu--collapse .el-menu-item {
  display: flex !important;
  justify-content: center !important;
  align-items: center !important;
  padding: 0 !important;
}

.el-menu--collapse .el-menu-item .el-icon {
  margin: 0 !important;
  position: static !important;
  left: auto !important;
  transform: none !important;
}

.el-menu--collapse .el-menu-item span {
  display: none !important;
}

.el-menu--collapse .el-menu-item .el-menu-tooltip__trigger {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  padding: 0 !important;
}

.user-info-footer {
  padding: 12px;
  border-top: 1px solid #f0f0f0;
}

.user-dropdown-footer {
  display: flex;
  align-items: center;
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
  width: 100%;
  box-sizing: border-box;
  color: #606266;
  overflow: hidden;
}

.user-dropdown-footer:hover {
  background-color: #f5f7fa;
}

.user-dropdown-footer.collapsed {
  justify-content: center;
  padding: 8px 0;
}

.user-avatar {
  background: #409eff;
  color: white;
  flex-shrink: 0;
}

.user-details {
  margin-left: 12px;
  flex: 1;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.username-text {
  font-size: 14px;
  font-weight: 500;
}

.dropdown-icon {
  margin-left: auto;
  font-size: 12px;
}

.el-main {
  background: #f0f2f5;
  padding: 20px;
  overflow-y: auto;
  min-width: 0;
}

@media (max-width: 768px) {
  .el-header {
    padding: 0 12px;
    height: 48px;
  }

  .logo span {
    font-size: 16px;
  }

  .el-main {
    padding: 12px;
  }

  .el-aside {
    position: absolute;
    top: 0;
    left: 0;
    height: 100%;
    z-index: 2000;
    box-shadow: 2px 0 8px rgba(0, 0, 0, 0.15);
  }

  .user-info-footer {
    padding: 8px 4px;
  }

  .mobile-mask {
    position: absolute;
    inset: 0;
    background-color: rgba(0, 0, 0, 0.45);
    z-index: 1999;
  }
}
</style>
