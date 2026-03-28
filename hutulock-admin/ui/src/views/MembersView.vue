<template>
  <div>
    <div class="page-header">
      <h2>
        <el-icon style="color:var(--color-primary)"><Setting /></el-icon>
        成员管理
      </h2>
    </div>

    <!-- Current members -->
    <el-card style="margin-bottom:20px">
      <template #header>
        <div style="display:flex;align-items:center;gap:8px">
          <el-icon style="color:var(--color-success)"><Connection /></el-icon>
          <span>当前集群成员</span>
          <el-tag type="info" size="small" effect="plain">{{ memberList.length }} 个节点</el-tag>
        </div>
      </template>
      <div class="members-grid">
        <div
          v-for="m in memberList"
          :key="m.id"
          class="member-item"
          :class="{ 'is-leader': m.id === cluster.data?.leaderId }"
        >
          <div class="member-avatar" :class="m.id === cluster.data?.leaderId ? 'avatar-leader' : 'avatar-member'">
            {{ m.id[0]?.toUpperCase() }}
          </div>
          <div class="member-details">
            <code class="mono">{{ m.id }}</code>
            <span class="member-role">{{ m.id === cluster.data?.leaderId ? 'Leader' : 'Member' }}</span>
          </div>
          <el-tag
            :type="m.id === cluster.data?.leaderId ? 'success' : 'primary'"
            size="small"
            effect="dark"
            style="margin-left:auto"
          >
            {{ m.id === cluster.data?.leaderId ? '👑 Leader' : 'Member' }}
          </el-tag>
        </div>
      </div>
    </el-card>

    <!-- Add / Remove -->
    <el-row :gutter="20">
      <el-col :span="12">
        <el-card class="action-card add-card">
          <template #header>
            <div style="display:flex;align-items:center;gap:8px">
              <div class="action-icon add-icon"><el-icon><CirclePlus /></el-icon></div>
              <span>添加成员</span>
            </div>
          </template>
          <el-form :model="addForm" :rules="addRules" ref="addRef" label-width="80px">
            <el-form-item label="节点 ID" prop="nodeId">
              <el-input v-model="addForm.nodeId" placeholder="如 n4" />
            </el-form-item>
            <el-form-item label="Host" prop="host">
              <el-input v-model="addForm.host" placeholder="如 127.0.0.1" />
            </el-form-item>
            <el-form-item label="Port" prop="port">
              <el-input v-model="addForm.port" placeholder="如 9884" type="number" />
            </el-form-item>
            <el-form-item>
              <el-button type="success" :loading="addLoading" @click="handleAdd" style="width:100%">
                <el-icon><CirclePlus /></el-icon>
                提交添加
              </el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card class="action-card remove-card">
          <template #header>
            <div style="display:flex;align-items:center;gap:8px">
              <div class="action-icon remove-icon"><el-icon><RemoveFilled /></el-icon></div>
              <span>移除成员</span>
            </div>
          </template>
          <el-form :model="rmForm" :rules="rmRules" ref="rmRef" label-width="80px">
            <el-form-item label="节点 ID" prop="nodeId">
              <el-select v-model="rmForm.nodeId" placeholder="选择要移除的节点" style="width:100%">
                <el-option v-for="m in memberList" :key="m.id" :label="m.id" :value="m.id" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-popconfirm
                title="确认移除该节点？此操作将触发 Joint Consensus 变更。"
                @confirm="handleRemove"
                confirm-button-type="danger"
              >
                <template #reference>
                  <el-button type="danger" :loading="rmLoading" style="width:100%">
                    <el-icon><RemoveFilled /></el-icon>
                    提交移除
                  </el-button>
                </template>
              </el-popconfirm>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <!-- Pending alert -->
    <el-alert
      v-if="cluster.data?.membershipChangePending"
      title="成员变更进行中（Joint Consensus）"
      type="warning"
      show-icon
      style="margin-top:20px;border-radius:10px"
      description="当前有成员变更正在通过 Raft 日志复制，请等待完成后再发起新的变更。"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { CirclePlus, RemoveFilled, Setting, Connection } from '@element-plus/icons-vue'
import api from '@/api'
import { useClusterStore } from '@/stores/cluster'

const cluster = useClusterStore()
const memberList = computed(() => (cluster.data?.members || []).map(id => ({ id })))

const addRef     = ref()
const addLoading = ref(false)
const addForm    = ref({ nodeId: '', host: '', port: '' })
const addRules   = {
  nodeId: [{ required: true, message: '请输入节点 ID' }],
  host:   [{ required: true, message: '请输入 Host' }],
  port:   [{ required: true, message: '请输入端口' }]
}

async function handleAdd() {
  await addRef.value.validate()
  addLoading.value = true
  try {
    await api.post('/api/admin/members/add', addForm.value)
    ElMessage.success('已提交添加请求，等待 Raft 确认')
    addForm.value = { nodeId: '', host: '', port: '' }
    cluster.fetch()
  } finally {
    addLoading.value = false
  }
}

const rmRef     = ref()
const rmLoading = ref(false)
const rmForm    = ref({ nodeId: '' })
const rmRules   = { nodeId: [{ required: true, message: '请选择节点' }] }

async function handleRemove() {
  await rmRef.value.validate()
  rmLoading.value = true
  try {
    await api.post('/api/admin/members/remove', { nodeId: rmForm.value.nodeId })
    ElMessage.success('已提交移除请求，等待 Raft 确认')
    rmForm.value = { nodeId: '' }
    cluster.fetch()
  } finally {
    rmLoading.value = false
  }
}

onMounted(() => cluster.fetch())
</script>

<style scoped>
.members-grid { display: flex; flex-direction: column; gap: 8px; }
.member-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-radius: 10px;
  border: 1px solid var(--border-color);
  background: #fafbfc;
  transition: all .15s;
}
.member-item:hover { background: #f1f5f9; border-color: #cbd5e1; }
.member-item.is-leader { background: #f0fdf4; border-color: #bbf7d0; }

.member-avatar {
  width: 36px; height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  font-size: .9rem;
  color: #fff;
  flex-shrink: 0;
}
.avatar-leader { background: linear-gradient(135deg, #22c55e, #16a34a); }
.avatar-member { background: linear-gradient(135deg, #4f6ef7, #7c3aed); }

.member-details { display: flex; flex-direction: column; gap: 2px; }
.member-role { font-size: .75rem; color: var(--text-muted); }

.action-card { height: 100%; }
.action-icon {
  width: 28px; height: 28px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
}
.add-icon    { background: #dcfce7; color: #16a34a; }
.remove-icon { background: #fee2e2; color: #dc2626; }
</style>
