<template>
  <div>
    <div class="page-header">
      <h2>
        <el-icon style="color:var(--color-primary)"><Connection /></el-icon>
        活跃会话
        <el-tag type="info" size="small" effect="plain">{{ sessions.length }}</el-tag>
      </h2>
      <el-button :icon="Refresh" @click="load" :loading="loading" size="small">刷新</el-button>
    </div>

    <el-card>
      <el-empty v-if="!loading && sessions.length === 0" description="暂无活跃会话" :image-size="80" />
      <el-table v-else :data="sessions" stripe v-loading="loading">
        <el-table-column label="Session ID" min-width="200">
          <template #default="{ row }">
            <div class="id-cell">
              <code class="mono">{{ row.sessionId.substring(0, 16) }}…</code>
              <el-tooltip :content="row.sessionId" placement="top">
                <el-icon class="copy-icon" @click="copy(row.sessionId)"><CopyDocument /></el-icon>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="clientId" label="Client" min-width="140">
          <template #default="{ row }">
            <code class="mono">{{ row.clientId }}</code>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="stateType(row.state)" size="small" effect="light">
              <span class="state-dot" :class="`dot-${stateType(row.state)}`"></span>
              {{ row.state }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="剩余 TTL" width="130">
          <template #default="{ row }">
            <div class="ttl-cell">
              <div class="ttl-bar-wrap">
                <div class="ttl-bar" :style="ttlBarStyle(row)"></div>
              </div>
              <span :class="row.ttlMs < 5000 ? 'ttl-warn' : 'ttl-ok'">{{ formatTtl(row.ttlMs) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="过期时间" min-width="160">
          <template #default="{ row }">
            <span class="time-text">{{ new Date(row.expireTime).toLocaleString() }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { Refresh, Connection, CopyDocument } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
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

function ttlBarStyle(row) {
  const pct = Math.min(100, Math.max(0, row.ttlMs / 30000 * 100))
  const color = pct > 50 ? '#22c55e' : pct > 20 ? '#f59e0b' : '#ef4444'
  return { width: pct + '%', background: color }
}

async function copy(text) {
  await navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}

let timer
onMounted(() => { load(); timer = setInterval(load, 5000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.id-cell { display: flex; align-items: center; gap: 6px; }
.copy-icon { cursor: pointer; color: var(--text-muted); font-size: 13px; opacity: 0; transition: opacity .15s; }
.id-cell:hover .copy-icon { opacity: 1; }

.state-dot {
  display: inline-block;
  width: 6px; height: 6px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: middle;
}
.dot-success { background: var(--color-success); }
.dot-warning { background: var(--color-warning); }
.dot-danger  { background: var(--color-danger); }
.dot-info    { background: var(--color-info); }

.ttl-cell { display: flex; flex-direction: column; gap: 4px; }
.ttl-bar-wrap { height: 3px; background: #e2e8f0; border-radius: 2px; overflow: hidden; }
.ttl-bar { height: 100%; border-radius: 2px; transition: width .5s; }
.ttl-warn { color: var(--color-danger); font-weight: 700; font-size: .8rem; }
.ttl-ok   { color: var(--text-secondary); font-size: .8rem; }

.time-text { color: var(--text-secondary); font-size: .85rem; }
</style>
