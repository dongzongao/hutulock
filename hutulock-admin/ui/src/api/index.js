import axios from 'axios'
import { ElMessage } from 'element-plus'

const api = axios.create({ timeout: 10000 })

// 请求拦截：自动附加 token
api.interceptors.request.use(config => {
  const token = localStorage.getItem('admin_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// 响应拦截：统一处理 401
api.interceptors.response.use(
  res => res,
  err => {
    if (err.response?.status === 401) {
      localStorage.removeItem('admin_token')
      localStorage.removeItem('admin_user')
      window.location.href = '/login'
    } else {
      const msg = err.response?.data?.error || err.message
      ElMessage.error(msg || '请求失败')
    }
    return Promise.reject(err)
  }
)

export default api
