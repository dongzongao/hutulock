<template>
  <div>
    <div class="page-header">
      <h2>
        <el-icon style="color:var(--color-primary)"><Lock /></el-icon>
        锁状态
        <el-tag type="info" size="small" effect="plain">{{ locks.length }} 把锁</el-tag>
      </h2>
      <el-button :icon="Refresh" @click="load" :loading="loading" size="small">刷新</el-button>
    </div>

    <el-empty v-if="!loading && locks.length === 0" description="暂无活跃锁" :image-size="80" />

    <div v-for="lock in locks" :key="lock.lockName" class="lock-card">
      <div class="lock-card-header">
        <div class="lock-title">
          <div class="lock-icon-wrap">
            <el-icon><Lock /></el-icon>
          </div>
          <span class="lock-name">{{ lock.lockName }}</span>
        </div>
        <div class="lock-meta">
          <el-tag type="success" size="small" effect="light" v-if="lock.holders.length > 0">
            1 持有者
          </el-tag>
          <el-tag type="warning" size="small" effect="light" v-if="lock.holders.length > 1">
            {{ lock.holders.length - 1 }} 等待
          </el-tag>
        </div>
      </div>

      <div class="holders-list">
        <div
          v-for="(h, i) in lock.holders"
          :key="h.seqPath"
          class="holder-row"
          :class="{ 'is-holder': h.isHolder }"
        >
          <div class="holder-rank">
            <span class="rank-num" :class="h.isHolder ? 'rank-1' : ''">{{ i + 1 }}</span>
          </div>
          <div class="holder-info">
            <code class="mono">{{ h.seqPath.split('/').pop() }}</code>
            <span class="holder-session">
              <el-tooltip :content="h.sessionId" placement="top">
                <code class="mono session-id">{{ h.sessionId.substring(0, 14) }}…</code>
              </el-tooltip>
              <el-icon class="copy-icon" @click="copy(h.sessionId)"><CopyDocument /></el-icon>
            </span>
          </div>
          <div class="holder-right">
            <el-tag :type="h.isHolder ? 'success' : 'warning'" size="small" effect="dark">
              {{ h.isHolder ? '🔑 持有' : '⏳ 等待' }}
            </el-tag>
            <span class="holder-time">{{ new Date(h.createTime).toLocaleTimeString() }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { Refresh, Lock, CopyDocument } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import api from '@/api'

const locks   = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await api.get('/api/admin/locks')
    locks.value = res.data
  } finally {
    loading.value = false
  }
}

async function copy(text) {
  await navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}

let timer
onMounted(() => { load(); timer = setInterval(load, 3000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.lock-card {
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  margin-bottom: 16px;
  overflow: hidden;
  box-shadow: var(--shadow-sm);
  transition: box-shadow .2s;
}
.lock-card:hover { box-shadow: var(--shadow-md); }

.lock-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--border-color);
  background: #fafbfc;
}
.lock-title { display: flex; align-items: center; gap: 10px; }
.lock-icon-wrap {
  width: 32px; height: 32px;
  background: linear-gradient(135deg, #4f6ef7, #7c3aed);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 14px;
}
.lock-name { font-weight: 700; font-size: 1rem; color: var(--text-primary); }
.lock-meta { display: flex; gap: 6px; }

.holders-list { padding: 8px 0; }
.holder-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 20px;
  transition: background .15s;
}
.holder-row:hover { background: #f8fafc; }
.holder-row.is-holder { background: #f0fdf4; }
.holder-row.is-holder:hover { background: #dcfce7; }

.holder-rank { width: 28px; flex-shrink: 0; }
.rank-num {
  width: 24px; height: 24px;
  border-radius: 50%;
  background: #e2e8f0;
  color: var(--text-secondary);
  font-size: .75rem;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.rank-1 { background: linear-gradient(135deg, #4f6ef7, #7c3aed); color: #fff; }

.holder-info { flex: 1; display: flex; flex-direction: column; gap: 3px; }
.holder-session { display: flex; align-items: center; gap: 4px; }
.session-id { color: var(--text-muted) !important; background: transparent !important; padding: 0 !important; }
.copy-icon { cursor: pointer; color: var(--text-muted); font-size: 12px; opacity: 0; transition: opacity .15s; }
.holder-session:hover .copy-icon { opacity: 1; }

.holder-right { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.holder-time { font-size: .75rem; color: var(--text-muted); }
</style>
