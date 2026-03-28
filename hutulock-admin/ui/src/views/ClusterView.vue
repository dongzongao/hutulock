<template>
  <div>
    <div class="page-header">
      <h2>
        <el-icon style="color:var(--color-primary)"><DataAnalysis /></el-icon>
        集群状态
      </h2>
      <el-button :icon="Refresh" @click="cluster.fetch()" :loading="cluster.loading" size="small">
        刷新
      </el-button>
    </div>

    <!-- Stat cards -->
    <el-row :gutter="16" style="margin-bottom:20px">
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-label">当前角色</div>
          <el-tag :type="roleTagType" size="large" effect="dark" class="role-tag-lg">
            {{ d?.role || '—' }}
          </el-tag>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-label">Leader 节点</div>
          <div class="stat-value mono-sm">{{ d?.leaderId || '—' }}</div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-label">配置阶段</div>
          <el-tag :type="d?.configPhase === 'JOINT' ? 'warning' : 'success'" effect="plain" size="large">
            {{ d?.configPhase || '—' }}
          </el-tag>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-label">集群成员数</div>
          <div class="stat-value">{{ d?.members?.length ?? '—' }}</div>
        </div>
      </el-col>
    </el-row>

    <!-- Members list -->
    <el-card style="margin-bottom:16px">
      <template #header>
        <div style="display:flex;align-items:center;gap:8px">
          <el-icon style="color:var(--color-primary)"><Connection /></el-icon>
          <span>集群成员</span>
          <el-tag v-if="d?.members?.length" type="info" size="small" effect="plain">
            {{ d.members.length }} 个节点
          </el-tag>
        </div>
      </template>
      <div class="members-grid">
        <div
          v-for="m in d?.members || []"
          :key="m"
          class="member-chip"
          :class="{ 'is-leader': m === d?.leaderId }"
        >
          <span class="member-dot" :class="m === d?.leaderId ? 'dot-leader' : 'dot-member'"></span>
          <span class="member-id">{{ m }}</span>
          <el-tag v-if="m === d?.leaderId" type="success" size="small" effect="dark" style="margin-left:auto">
            Leader
          </el-tag>
        </div>
      </div>
    </el-card>

    <!-- Peers table -->
    <el-card>
      <template #header>
        <div style="display:flex;align-items:center;gap:8px">
          <el-icon style="color:var(--color-info)"><Monitor /></el-icon>
          <span>Peer 节点详情</span>
          <el-tag v-if="d?.membershipChangePending" type="warning" size="small" effect="dark">
            ⚠ 变更进行中
          </el-tag>
        </div>
      </template>
      <el-empty v-if="!d?.peers?.length" description="暂无 Peer 节点" :image-size="60" />
      <el-table v-else :data="d?.peers || []" stripe>
        <el-table-column prop="nodeId" label="节点 ID" width="140">
          <template #default="{ row }">
            <code class="mono">{{ row.nodeId }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="host" label="Host" width="160">
          <template #default="{ row }">
            <code class="mono">{{ row.host }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="port" label="Port" width="90" />
        <el-table-column prop="nextIndex"  label="nextIndex"  width="110" />
        <el-table-column prop="matchIndex" label="matchIndex" width="110" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.inFlight ? 'warning' : 'success'" size="small" effect="light">
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
import { Refresh, DataAnalysis, Connection, Monitor } from '@element-plus/icons-vue'
import { useClusterStore } from '@/stores/cluster'

const cluster = useClusterStore()
const d = computed(() => cluster.data)
const roleTagType = computed(() => ({
  LEADER: 'success', FOLLOWER: 'primary', CANDIDATE: 'warning'
}[d.value?.role] || 'info'))
</script>

<style scoped>
.role-tag-lg { font-size: .9rem !important; padding: 8px 16px !important; }
.mono-sm { font-family: var(--font-mono); font-size: .9rem; font-weight: 600; color: var(--text-primary); }

.members-grid { display: flex; flex-direction: column; gap: 8px; }
.member-chip {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid var(--border-color);
  transition: background .15s;
}
.member-chip:hover { background: #f1f5f9; }
.member-chip.is-leader { background: #f0fdf4; border-color: #bbf7d0; }
.member-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.dot-leader { background: var(--color-success); box-shadow: 0 0 0 3px rgba(34,197,94,.2); }
.dot-member { background: var(--color-primary); }
.member-id { font-family: var(--font-mono); font-size: .875rem; font-weight: 500; color: var(--text-primary); }
</style>
