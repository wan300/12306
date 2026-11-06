import { defineStore } from 'pinia'
import { ref } from 'vue'
import rail from '../api'
import flights from '../api/flights'
import { useUserStore } from './user'
import { normalizeTask } from '../utils/ticket-task'

export const useTickets = defineStore('tickets', () => {
  const tasks = ref([])
  const errors = ref([])
  const loading = ref(false)
  let pending
  async function load() {
    if (pending) return pending
    loading.value = true
    pending = (async () => {
      const user = useUserStore()
      await user.restoreUser()
      const sources = [
        [
          'rail',
          user.hasToken ? rail.getTasks() : Promise.resolve({ tasks: [] }),
        ],
        [
          'flight',
          window.electronAPI?.flights
            ? flights.monitors()
            : Promise.resolve([]),
        ],
      ]
      const results = await Promise.allSettled(
        sources.map(([, request]) => request),
      )
      const rows = []
      errors.value = []
      results.forEach((result, index) => {
        const kind = sources[index][0]
        if (result.status === 'fulfilled') {
          const values =
            kind === 'rail' ? result.value.tasks || [] : result.value
          rows.push(...values.map((row) => normalizeTask(kind, row)))
        } else {
          rows.push(...tasks.value.filter((task) => task.kind === kind))
          errors.value.push(
            `${kind === 'rail' ? '火车票' : '机票'}：${result.reason.message}`,
          )
        }
      })
      tasks.value = rows.sort(
        (a, b) => b.timestamp - a.timestamp || a.key.localeCompare(b.key),
      )
    })()
    try {
      await pending
    } finally {
      pending = null
      loading.value = false
    }
  }
  async function control(task, action) {
    if (!task.actions[action]) throw new Error('当前状态无法执行此操作')
    if (task.kind === 'flight')
      await flights[
        { start: 'start', pause: 'pause', remove: 'remove' }[action]
      ](task.id)
    else
      await rail[
        {
          start: 'startTask',
          pause: 'stopTask',
          remove: 'deleteTask',
          cancel: 'cancelTask',
        }[action]
      ](task.id)
    await load()
  }
  return { tasks, errors, loading, load, control }
})
