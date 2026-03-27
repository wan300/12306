<template>
  <el-config-provider :locale="zhCn">
    <div class="app-container">
      <el-container>
        <el-header>
          <div class="header-content">
            <div class="logo">
              <el-icon size="28"><Promotion /></el-icon>
              <span>12306 自动化抢票系统</span>
            </div>
            <div class="user-info" v-if="userStore.currentUser">
              <el-dropdown>
                <span class="user-dropdown">
                  <el-avatar :size="32">{{ userStore.currentUser.username[0] }}</el-avatar>
                  <span class="username">{{ userStore.currentUser.username }}</span>
                  <el-icon><ArrowDown /></el-icon>
                </span>
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
        </el-header>
        
        <el-container>
          <el-aside width="200px">
            <el-menu
              :default-active="route.path"
              router
              class="side-menu"
            >
              <el-menu-item index="/">
                <el-icon><HomeFilled /></el-icon>
                <span>首页</span>
              </el-menu-item>
              <el-menu-item index="/login">
                <el-icon><User /></el-icon>
                <span>账号管理</span>
              </el-menu-item>
              <el-menu-item index="/tasks">
                <el-icon><List /></el-icon>
                <span>任务列表</span>
              </el-menu-item>
              <el-menu-item index="/create-task">
                <el-icon><Plus /></el-icon>
                <span>创建任务</span>
              </el-menu-item>
              <el-menu-item index="/query">
                <el-icon><Search /></el-icon>
                <span>车票查询</span>
              </el-menu-item>
              <el-menu-item index="/logs">
                <el-icon><Document /></el-icon>
                <span>后端日志</span>
              </el-menu-item>
            </el-menu>
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
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from './stores/user'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'

const route = useRoute()
const userStore = useUserStore()

// 应用启动时恢复用户状态
onMounted(async () => {
  await userStore.restoreUser()
})

const handleLogout = () => {
  userStore.logout()
}
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB',
    'Microsoft YaHei', '微软雅黑', Arial, sans-serif;
}

.app-container {
  min-height: 100vh;
}

.el-header {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  display: flex;
  align-items: center;
}

.header-content {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 20px;
  font-weight: bold;
}

.user-dropdown {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: white;
}

.username {
  margin-left: 4px;
}

.el-aside {
  background: #f5f7fa;
  border-right: 1px solid #e4e7ed;
}

.side-menu {
  border-right: none;
  background: transparent;
}

.el-main {
  background: #f0f2f5;
  padding: 20px;
}
</style>
