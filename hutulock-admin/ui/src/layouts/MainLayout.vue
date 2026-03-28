<template>
  <el-container class="layout">
    <!-- Sidebar -->
    <el-aside width="240px" class="sidebar">
      <div class="logo">
        <div class="logo-icon-wrap">🔒</div>
        <div>
          <div class="logo-text">HutuLock</div>
          <div class="logo-sub">Admin Console</div>
        </div>
      </div>

      <nav class="nav">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: $route.path === item.path }"
        >
          <el-icon class="nav-icon"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
          <span v-if="item.badge" class="nav-badge">{{ item.badge }}</span>
        </router-link>
      </nav>

      <div class="sidebar-footer">
        <div class="version-tag">v1.0.1-SNAPSHOT</div>
      </div>
    </el-aside>

    <!-- Main -->
    <el-container>
      <!-- Header -->
      <el-header class="topbar">
        <div class="topbar-left">
          <div class="cluster-status">
            <span class="status-dot" :class="statusDotClass"></span>
            <el-tag :type="roleTagType" size="small" effect="dark" class="role-tag">
              {{ clusterRole }}
            </el-tag>
            <span class="node-id">{{ nodeId }}</span>
          </div>
        </div>

        <div class="topbar-right">
          <div class="refresh-info" v-if="lastRefresh">
            <el-icon class="refresh-icon"><Clock /></el-icon>
            <span>{{ lastRefresh }}</span>
          </div>
          <el-button :icon="Refresh" circle size="small" @click="refresh" :loading="refreshing" />
          <div class="divider-v" />
          <el-dropdown @command="handleCmd" trigger="click">
            <div class="user-btn">
              <el-avatar size="small" class="user-avatar">{{ auth.username?.[0]?.toUpperCase() }}</el-avatar>
              <span class="user-name">{{ auth.username }}</span>
              <el-icon class="chevron"><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout" :icon="SwitchButton">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- Content -->
      <el-main class="main-content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
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
  Refresh, ArrowDown, SwitchButton, Clock
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useClusterStore } from '@/stores/cluster'

const router  = useRouter()
const auth    = useAuthStore()
const cluster = useClusterStore()

const refreshing  = ref(false)
const lastRefresh = ref('')

const navItems = [
  { path: '/cluster',  label: '集群状态', icon: DataAnalysis },
  { path: '/sessions', label: '活跃会话', icon: Connection },
  { path: '/locks',    label: '锁状态',   icon: Lock },
  { path: '/members',  label: '成员管理', icon: Setting },
]

const nodeId      = computed(() => cluster.data?.nodeId || '—')
const clusterRole = computed(() => cluster.data?.role || 'UNKNOWN')
const roleTagType = computed(() => ({
  LEADER: 'success', FOLLOWER: 'primary', CANDIDATE: 'warning'
}[clusterRole.value] || 'info'))

const statusDotClass = computed(() => ({
  'dot-success': clusterRole.value === 'LEADER',
  'dot-primary': clusterRole.value === 'FOLLOWER',
  'dot-warning': clusterRole.value === 'CANDIDATE',
}))

function updateRefreshTime() {
  const now = new Date()
  lastRefresh.value = now.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

async function refresh() {
  refreshing.value = true
  await cluster.fetch()
  updateRefreshTime()
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
  cluster.fetch().then(updateRefreshTime)
  timer = setInterval(() => { cluster.fetch().then(updateRefreshTime) }, 5000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.layout { height: 100vh; overflow: hidden; }

/* ── Sidebar ── */
.sidebar {
  background: var(--sidebar-bg);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid rgba(255,255,255,.06);
}

.logo {
  height: 68px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  border-bottom: 1px solid rgba(255,255,255,.06);
  flex-shrink: 0;
}
.logo-icon-wrap {
  width: 36px;
  height: 36px;
  background: linear-gradient(135deg, #4f6ef7, #7c3aed);
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}
.logo-text { color: #fff; font-size: 1rem; font-weight: 700; line-height: 1.2; }
.logo-sub  { color: #475569; font-size: .7rem; margin-top: 1px; }

.nav { flex: 1; padding: 12px 10px; overflow-y: auto; }
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  color: var(--sidebar-text);
  text-decoration: none;
  font-size: .875rem;
  font-weight: 500;
  margin-bottom: 2px;
  transition: all .15s;
  position: relative;
}
.nav-item:hover { background: var(--sidebar-hover); color: #cbd5e1; }
.nav-item.active {
  background: var(--sidebar-active);
  color: var(--sidebar-active-text);
  box-shadow: 0 2px 8px rgba(29,78,216,.4);
}
.nav-icon { font-size: 16px; flex-shrink: 0; }
.nav-badge {
  margin-left: auto;
  background: rgba(255,255,255,.15);
  color: #fff;
  font-size: .7rem;
  padding: 1px 7px;
  border-radius: 10px;
}

.sidebar-footer {
  padding: 16px 20px;
  border-top: 1px solid rgba(255,255,255,.06);
}
.version-tag {
  font-size: .7rem;
  color: #334155;
  font-family: var(--font-mono);
}

/* ── Topbar ── */
.topbar {
  background: var(--header-bg);
  border-bottom: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  height: 60px;
  flex-shrink: 0;
  box-shadow: 0 1px 0 var(--border-color);
}
.topbar-left { display: flex; align-items: center; gap: 16px; }
.cluster-status { display: flex; align-items: center; gap: 8px; }
.status-dot {
  width: 8px; height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.dot-success { background: var(--color-success); box-shadow: 0 0 0 3px rgba(34,197,94,.2); }
.dot-primary { background: var(--color-primary); box-shadow: 0 0 0 3px rgba(79,110,247,.2); }
.dot-warning { background: var(--color-warning); box-shadow: 0 0 0 3px rgba(245,158,11,.2); }
.role-tag { font-size: .75rem !important; }
.node-id { color: var(--text-muted); font-size: .8rem; font-family: var(--font-mono); }

.topbar-right { display: flex; align-items: center; gap: 10px; }
.refresh-info { display: flex; align-items: center; gap: 4px; color: var(--text-muted); font-size: .75rem; }
.refresh-icon { font-size: 12px; }
.divider-v { width: 1px; height: 20px; background: var(--border-color); margin: 0 4px; }

.user-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 6px 10px;
  border-radius: 8px;
  transition: background .15s;
}
.user-btn:hover { background: #f1f5f9; }
.user-avatar { background: linear-gradient(135deg, #4f6ef7, #7c3aed) !important; font-weight: 700; }
.user-name { font-size: .875rem; font-weight: 500; color: var(--text-primary); }
.chevron { font-size: 12px; color: var(--text-muted); }

/* ── Main content ── */
.main-content {
  background: var(--content-bg);
  padding: 24px;
  overflow-y: auto;
}

/* ── Page transition ── */
.fade-enter-active, .fade-leave-active { transition: opacity .15s, transform .15s; }
.fade-enter-from { opacity: 0; transform: translateY(6px); }
.fade-leave-to   { opacity: 0; transform: translateY(-4px); }
</style>
