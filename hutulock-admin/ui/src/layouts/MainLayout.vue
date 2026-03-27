<template>
  <el-container class="layout">
    <!-- 侧边栏 -->
    <el-aside width="220px" class="sidebar">
      <div class="logo">
        <span class="logo-icon">🔒</span>
        <span class="logo-text">HutuLock</span>
      </div>
      <el-menu
        :default-active="$route.path"
        router
        background-color="#1a1a2e"
        text-color="#a0aec0"
        active-text-color="#63b3ed"
      >
        <el-menu-item index="/cluster">
          <el-icon><DataAnalysis /></el-icon>
          <span>集群状态</span>
        </el-menu-item>
        <el-menu-item index="/sessions">
          <el-icon><Connection /></el-icon>
          <span>活跃会话</span>
        </el-menu-item>
        <el-menu-item index="/locks">
          <el-icon><Lock /></el-icon>
          <span>锁状态</span>
        </el-menu-item>
        <el-menu-item index="/members">
          <el-icon><Setting /></el-icon>
          <span>成员管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <!-- 主内容区 -->
    <el-container>
      <!-- 顶栏 -->
      <el-header class="topbar">
        <div class="topbar-left">
          <el-tag :type="roleTagType" size="large" effect="dark">
            {{ clusterRole }}
          </el-tag>
          <span class="node-id">{{ nodeId }}</span>
        </div>
        <div class="topbar-right">
          <el-button :icon="Refresh" circle @click="refresh" :loading="refreshing" />
          <el-dropdown @command="handleCmd">
            <span class="user-info">
              <el-avatar size="small" :icon="UserFilled" />
              <span>{{ auth.username }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout" :icon="SwitchButton">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- 页面内容 -->
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  DataAnalysis, Connection, Lock, Setting,
  Refresh, UserFilled, ArrowDown, SwitchButton
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useClusterStore } from '@/stores/cluster'

const router  = useRouter()
const auth    = useAuthStore()
const cluster = useClusterStore()

const refreshing = ref(false)
const nodeId     = computed(() => cluster.data?.nodeId || '—')
const clusterRole = computed(() => cluster.data?.role || 'UNKNOWN')
const roleTagType = computed(() => ({
  LEADER: 'success', FOLLOWER: 'primary', CANDIDATE: 'warning'
}[clusterRole.value] || 'info'))

async function refresh() {
  refreshing.value = true
  await cluster.fetch()
  refreshing.value = false
}

async function handleCmd(cmd) {
  if (cmd === 'logout') {
    await auth.logout()
    router.push('/login')
    ElMessage.success('已退出登录')
  }
}

let timer
onMounted(() => {
  cluster.fetch()
  timer = setInterval(cluster.fetch, 5000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.layout { height: 100vh; }

.sidebar {
  background: #1a1a2e;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.logo {
  height: 64px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  border-bottom: 1px solid rgba(255,255,255,.08);
}
.logo-icon { font-size: 24px; }
.logo-text { color: #fff; font-size: 1.1rem; font-weight: 700; letter-spacing: .02em; }

.topbar {
  background: #fff;
  border-bottom: 1px solid #e8ecf0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  height: 64px;
}
.topbar-left { display: flex; align-items: center; gap: 12px; }
.node-id { color: #666; font-size: .9rem; font-family: monospace; }
.topbar-right { display: flex; align-items: center; gap: 12px; }
.user-info {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: #333;
  font-size: .9rem;
}

.main-content {
  background: #f5f7fa;
  padding: 24px;
  overflow-y: auto;
}
</style>
