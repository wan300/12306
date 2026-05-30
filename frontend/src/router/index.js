import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Home',
    meta: { requiresAuth: true },
    component: () => import('../views/Home.vue')
  },
  {
    path: '/login',
    name: 'Login',
    meta: { requiresAuth: false },
    component: () => import('../views/Login.vue')
  },
  {
    path: '/tasks',
    name: 'Tasks',
    meta: { requiresAuth: true },
    component: () => import('../views/Tasks.vue')
  },
  {
    path: '/create-task',
    name: 'CreateTask',
    meta: { requiresAuth: true },
    component: () => import('../views/CreateTask.vue')
  },
  {
    path: '/edit-task/:id',
    name: 'EditTask',
    meta: { requiresAuth: true },
    component: () => import('../views/CreateTask.vue')
  },
  {
    path: '/query',
    name: 'Query',
    meta: { requiresAuth: true },
    component: () => import('../views/Query.vue')
  },
  {
    path: '/task/:id',
    name: 'TaskDetail',
    meta: { requiresAuth: true },
    component: () => import('../views/TaskDetail.vue')
  },
  {
    path: '/notification',
    name: 'NotificationSettings',
    meta: { requiresAuth: true },
    component: () => import('../views/NotificationSettings.vue')
  }
]

const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes
})

router.beforeEach((to) => {
  const token = localStorage.getItem('accessToken')
  const requiresAuth = to.meta.requiresAuth !== false

  if (requiresAuth && !token) {
    return '/login'
  }

  if (!requiresAuth && token) {
    return '/'
  }

  return true
})

export default router
