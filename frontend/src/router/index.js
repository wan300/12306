import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('../views/Home.vue')
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue')
  },
  {
    path: '/tasks',
    name: 'Tasks',
    component: () => import('../views/Tasks.vue')
  },
  {
    path: '/create-task',
    name: 'CreateTask',
    component: () => import('../views/CreateTask.vue')
  },
  {
    path: '/edit-task/:id',
    name: 'EditTask',
    component: () => import('../views/CreateTask.vue')
  },
  {
    path: '/query',
    name: 'Query',
    component: () => import('../views/Query.vue')
  },
  {
    path: '/task/:id',
    name: 'TaskDetail',
    component: () => import('../views/TaskDetail.vue')
  },
  {
    path: '/logs',
    name: 'Logs',
    component: () => import('../views/Logs.vue')
  }
]

const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes
})

export default router
