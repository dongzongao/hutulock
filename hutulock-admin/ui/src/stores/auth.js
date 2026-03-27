import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '@/api'

export const useAuthStore = defineStore('auth', () => {
  const token    = ref(localStorage.getItem('admin_token') || '')
  const username = ref(localStorage.getItem('admin_user')  || '')

  const isLoggedIn = computed(() => !!token.value)

  async function login(user, pass) {
    const res = await api.post('/api/admin/login', { username: user, password: pass })
    token.value    = res.data.token
    username.value = res.data.username
    localStorage.setItem('admin_token', token.value)
    localStorage.setItem('admin_user',  username.value)
  }

  async function logout() {
    try { await api.post('/api/admin/logout') } catch (_) {}
    token.value    = ''
    username.value = ''
    localStorage.removeItem('admin_token')
    localStorage.removeItem('admin_user')
  }

  return { token, username, isLoggedIn, login, logout }
})
