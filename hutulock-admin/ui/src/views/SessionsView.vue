<template>
  <div>
    <div class="page-header">
      <h2>活跃会话 <el-tag type="info">{{ sessions.length }}</el-tag></h2>
      <el-button :icon="Refresh" @click="load" :loading="loading">刷新</el-button>
    </div>

    <el-card>
      <el-table :data="sessions" stripe v-loading="loading">
        <el-table-column label="Session ID" min-width="200">
          <template #default="{ row }">
            <el-tooltip :content="row.sessionId" placement="top">
              <code class="mono">{{ row.sessionId.substring(0, 16) }}…</code>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="clientId" label="Client ID" min-width="140" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="stateType(row.state)" size="small">{{ row.state }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="剩余 TTL" width="120">
          <template #default="{ row }">
            <span :class="row.ttlMs < 5000 ? 'ttl-warn' : ''">
              {{ formatTtl(row.ttlMs) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="过期时间" min-width="160">
          <template #default="{ row }">
            {{ new Date(row.expireTime).toLocaleString() }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import api from '@/api'

const sessions = ref([])
const loading  = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await api.get('/api/admin/sessions')
    sessions.value = res.data
  } finally {
    loading.value = false
  }
}

function stateType(s) {
  return { CONNECTED: 'success', RECONNECTING: 'warning', EXPIRED: 'danger', CLOSED: 'info' }[s] || 'info'
}

function formatTtl(ms) {
  if (ms <= 0) return '已过期'
  const s = Math.floor(ms / 1000)
  return s >= 60 ? `${Math.floor(s/60)}m ${s%60}s` : `${s}s`
}

let timer
onMounted(() => { load(); timer = setInterval(load, 5000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.page-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:16px; }
.page-header h2 { margin:0; font-size:1.2rem; color:#1a1a2e; display:flex; align-items:center; gap:8px; }
.mono { font-family:monospace; font-size:.85rem; }
.ttl-warn { color:#e6a23c; font-weight:600; }
</style>
