<template>
  <div>
    <div class="page-header">
      <h2>成员管理</h2>
    </div>

    <!-- 当前成员 -->
    <el-card style="margin-bottom:16px">
      <template #header>当前集群成员</template>
      <el-table :data="memberList" stripe>
        <el-table-column prop="id" label="节点 ID" />
        <el-table-column label="角色">
          <template #default="{ row }">
            <el-tag :type="row.id === cluster.data?.leaderId ? 'success' : 'primary'" size="small">
              {{ row.id === cluster.data?.leaderId ? 'Leader' : 'Member' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-row :gutter="16">
      <!-- 添加成员 -->
      <el-col :span="12">
        <el-card>
          <template #header>
            <el-icon style="color:#67c23a"><CirclePlus /></el-icon>
            添加成员
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
              <el-button type="success" :loading="addLoading" @click="handleAdd">
                提交添加
              </el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <!-- 移除成员 -->
      <el-col :span="12">
        <el-card>
          <template #header>
            <el-icon style="color:#f56c6c"><RemoveFilled /></el-icon>
            移除成员
          </template>
          <el-form :model="rmForm" :rules="rmRules" ref="rmRef" label-width="80px">
            <el-form-item label="节点 ID" prop="nodeId">
              <el-select v-model="rmForm.nodeId" placeholder="选择要移除的节点" style="width:100%">
                <el-option
                  v-for="m in memberList"
                  :key="m.id"
                  :label="m.id"
                  :value="m.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-popconfirm
                title="确认移除该节点？此操作将触发 Joint Consensus 变更。"
                @confirm="handleRemove"
              >
                <template #reference>
                  <el-button type="danger" :loading="rmLoading">提交移除</el-button>
                </template>
              </el-popconfirm>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <!-- 变更进行中提示 -->
    <el-alert
      v-if="cluster.data?.membershipChangePending"
      title="成员变更进行中（Joint Consensus）"
      type="warning"
      show-icon
      style="margin-top:16px"
      description="当前有成员变更正在通过 Raft 日志复制，请等待完成后再发起新的变更。"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { CirclePlus, RemoveFilled } from '@element-plus/icons-vue'
import api from '@/api'
import { useClusterStore } from '@/stores/cluster'

const cluster = useClusterStore()

const memberList = computed(() =>
  (cluster.data?.members || []).map(id => ({ id }))
)

// 添加表单
const addRef    = ref()
const addLoading = ref(false)
const addForm   = ref({ nodeId: '', host: '', port: '' })
const addRules  = {
  nodeId: [{ required: true, message: '请输入节点 ID' }],
  host:   [{ required: true, message: '请输入 Host' }],
  port:   [{ required: true, message: '请输入端口' }]
}

async function handleAdd() {
  await addRef.value.validate()
  addLoading.value = true
  try {
    await api.post('/api/admin/members/add', {
      nodeId: addForm.value.nodeId,
      host:   addForm.value.host,
      port:   addForm.value.port
    })
    ElMessage.success('已提交添加请求，等待 Raft 确认')
    addForm.value = { nodeId: '', host: '', port: '' }
    cluster.fetch()
  } finally {
    addLoading.value = false
  }
}

// 移除表单
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
.page-header { display:flex; justify-content:space-between; align-items:center; margin-bottom:16px; }
.page-header h2 { margin:0; font-size:1.2rem; color:#1a1a2e; }
</style>
