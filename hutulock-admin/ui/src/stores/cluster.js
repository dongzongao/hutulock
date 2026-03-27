import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '@/api'

export const useClusterStore = defineStore('cluster', () => {
  const data    = ref(null)
  const loading = ref(false)
  const error   = ref(null)

  async function fetch() {
    loading.value = true
    try {
      const res = await api.get('/api/admin/cluster')
      data.value  = res.data
      error.value = null
    } catch (e) {
      error.value = e.message
    } finally {
      loading.value = false
    }
  }

  return { data, loading, error, fetch }
})
