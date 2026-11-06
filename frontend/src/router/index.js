import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'Home', component: () => import('../views/Home.vue') },
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
  },
  {
    path: '/tasks',
    name: 'Tasks',
    component: () => import('../views/Tasks.vue'),
  },
  {
    path: '/query',
    name: 'Query',
    component: () => import('../views/Query.vue'),
  },
  {
    path: '/flights',
    redirect: (to) => ({
      path: '/query',
      query: { ...to.query, type: 'flight' },
    }),
  },
  {
    path: '/create-task',
    name: 'CreateTask',
    component: () => import('../views/CreateTask.vue'),
  },
  {
    path: '/edit-task/:id',
    redirect: (to) => '/edit-task/rail/' + to.params.id,
  },
  {
    path: '/edit-task/:kind(rail|flight)/:id',
    name: 'EditTask',
    component: () => import('../views/CreateTask.vue'),
  },
  { path: '/task/:id', redirect: (to) => '/task/rail/' + to.params.id },
  {
    path: '/task/:kind(rail|flight)/:id',
    name: 'TaskDetail',
    component: () => import('../views/TaskDetail.vue'),
  },
  {
    path: '/notification',
    name: 'NotificationSettings',
    component: () => import('../views/NotificationSettings.vue'),
  },
]
export default createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes,
})
