<template>
  <div>
    <div class="page-header">
      <h2>集群状态</h2>
      <el-button :icon="Refresh" @click="cluster.fetch()" :loading="cluster.loading">刷新</el-button>
    </div>

    <el-row :gutter="16" style="margin-bottom:16px">
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-label">当前角色</div>
          <el-tag :type="roleTagType" size="large" effect="dark" style="font-size:1rem;padding:8px 16px">
            {{ d?.role || '—' }}
          </el-tag>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-label">Leader</div>
          <div class="stat-value">{{ d?.leaderId || '—' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-label">配置阶段</div>
          <el-tag :type="d?.configPhase === 'JOINT' ? 'warning' : 'info'" effect="plain">
            {{ d?.configPhase || '—' }}
          </el-tag>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-label">成员数</div>
          <div class="stat-value">{{ d?.members?.length ?? '—' }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card>
      <template #header>
        <span>Peer 节点</span>
        <el-tag v-if="d?.membershipChangePending" type="warning" style="margin-left:8px">
          变更进行中
        </el-tag>
      </template>
      <el-table :data="d?.peers || []" stripe>
        <el-table-column prop="nodeId"     label="节点 ID"    width="140" />
        <el-table-column prop="host"       label="Host"       width="160" />
        <el-table-column prop="port"       label="Port"       width="90" />
        <el-table-column prop="nextIndex"  label="nextIndex"  width="110" />
        <el-table-column prop="matchIndex" label="matchIndex" width="110" />
        <el-table-column label="inFlight" width="100">
          <template #default="{ row }">
            <el-tag :type="row.inFlight ? 'warning' : 'success'" size="small">
              {{ row.inFlight ? '在途' : '空闲' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { useClusterStore } from '@/stores/cluster'

const cluster = useClusterStore()
const d = computed(() => cluster.data)
const roleTagType = computed(() => ({
  LEADER: 'success', FOLLOWER: 'primary', CANDIDATE: 'warning'
}[d.value?.role] || 'info'))
</script>

<style scoped>
.page-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:16px; }
.page-header h2 { margin:0; font-size:1.2rem; color:#1a1a2e; }
.stat-card { text-align:center; padding:8px 0; }
.stat-label { font-size:.8rem; color:#888; margin-bottom:8px; }
.stat-value { font-size:1.4rem; font-weight:700; color:#1a1a2e; }
</style>
