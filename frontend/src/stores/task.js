import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '../api'

export const useTaskStore = defineStore('task', () => {
  const tasks = ref([])
  const currentTask = ref(null)
  const taskLogs = ref([])
  const loading = ref(false)

  async function fetchTasks(status = null) {
    loading.value = true
    try {
      const res = await api.getTasks({ status })
      tasks.value = res.tasks || []
    } catch (error) {
      console.error('获取任务列表失败:', error)
    } finally {
      loading.value = false
    }
  }

  async function getTask(taskId) {
    try {
      const res = await api.getTask(taskId)
      if (res.success) {
        currentTask.value = res.data
        return res.data
      }
    } catch (error) {
      console.error('获取任务详情失败:', error)
    }
  }

  async function createTask(taskData) {
    try {
      const res = await api.createTask(taskData)
      if (res.success) {
        tasks.value.unshift(res.data)
        return res.data
      }
    } catch (error) {
      throw error
    }
  }

  async function updateTask(taskId, taskData) {
    try {
      const res = await api.updateTask(taskId, taskData)
      if (res.success) {
        const index = tasks.value.findIndex(t => t.id === taskId)
        if (index !== -1) {
          tasks.value[index] = res.data
        }
        return res.data
      }
    } catch (error) {
      throw error
    }
  }

  async function startTask(taskId) {
    try {
      const res = await api.startTask(taskId)
      if (res.success) {
        const task = tasks.value.find(t => t.id === taskId)
        if (task) task.status = 'running'
      }
      return res
    } catch (error) {
      throw error
    }
  }

  async function stopTask(taskId) {
    try {
      const res = await api.stopTask(taskId)
      if (res.success) {
        const task = tasks.value.find(t => t.id === taskId)
        if (task) task.status = 'paused'
      }
      return res
    } catch (error) {
      throw error
    }
  }

  async function cancelTask(taskId) {
    try {
      const res = await api.cancelTask(taskId)
      if (res.success) {
        const task = tasks.value.find(t => t.id === taskId)
        if (task) task.status = 'cancelled'
      }
      return res
    } catch (error) {
      throw error
    }
  }

  async function deleteTask(taskId) {
    try {
      const res = await api.deleteTask(taskId)
      if (res.success) {
        tasks.value = tasks.value.filter(t => t.id !== taskId)
      }
      return res
    } catch (error) {
      throw error
    }
  }

  async function fetchTaskLogs(taskId) {
    try {
      const res = await api.getTaskLogs(taskId)
      taskLogs.value = res.logs || []
    } catch (error) {
      console.error('获取任务日志失败:', error)
    }
  }

  return {
    tasks,
    currentTask,
    taskLogs,
    loading,
    fetchTasks,
    getTask,
    createTask,
    startTask,
    stopTask,
    cancelTask,
    deleteTask,
    fetchTaskLogs
  }
})
