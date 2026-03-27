<template>
  <div>
    <div class="page-header">
      <h2>锁状态 <el-tag type="info">{{ locks.length }} 把锁</el-tag></h2>
      <el-button :icon="Refresh" @click="load" :loading="loading">刷新</el-button>
    </div>

    <el-empty v-if="!loading && locks.length === 0" description="暂无活跃锁" />

    <el-card
      v-for="lock in locks"
      :key="lock.lockName"
      class="lock-card"
    >
      <template #header>
        <div class="lock-header">
          <el-icon><Lock /></el-icon>
          <span class="lock-name">{{ lock.lockName }}</span>
          <el-tag size="small" type="info">{{ lock.holders.length }} 个等待者</el-tag>
        </div>
      </template>

      <el-table :data="lock.holders" size="small">
        <el-table-column label="#" width="50">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column label="顺序节点" min-width="200">
          <template #default="{ row }">
            <code class="mono">{{ row.seqPath.split('/').pop() }}</code>
          </template>
        </el-table-column>
        <el-table-column label="Session" min-width="160">
          <template #default="{ row }">
            <el-tooltip :content="row.sessionId" placement="top">
              <code class="mono">{{ row.sessionId.substring(0, 14) }}…</code>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="100">
          <template #default="{ row }">
            <el-tag :type="row.isHolder ? 'success' : 'warning'" size="small" effect="dark">
              {{ row.isHolder ? '持有' : '等待' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }">
            {{ new Date(row.createTime).toLocaleString() }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { Refresh, Lock } from '@element-plus/icons-vue'
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

let timer
onMounted(() => { load(); timer = setInterval(load, 3000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.page-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:16px; }
.page-header h2 { margin:0; font-size:1.2rem; color:#1a1a2e; display:flex; align-items:center; gap:8px; }
.lock-card { margin-bottom:16px; }
.lock-header { display:flex; align-items:center; gap:8px; }
.lock-name { font-weight:600; font-size:1rem; }
.mono { font-family:monospace; font-size:.85rem; }
</style>
