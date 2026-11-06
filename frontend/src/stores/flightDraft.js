import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useFlightDraft = defineStore('flightDraft', () => {
  const value = ref(null)
  const set = (data) => {
    value.value = structuredClone(data)
  }
  return { value, set }
})
