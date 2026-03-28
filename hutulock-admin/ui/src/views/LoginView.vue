<template>
  <div class="login-bg">
    <div class="login-card">
      <div class="login-header">
        <div class="logo-wrap">
          <span class="logo-emoji">🔒</span>
        </div>
        <h1>HutuLock Admin</h1>
        <p class="subtitle">分布式锁管理控制台</p>
      </div>

      <el-form :model="form" :rules="rules" ref="formRef" @submit.prevent="handleLogin" class="login-form">
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            placeholder="用户名"
            size="large"
            :prefix-icon="User"
            autocomplete="username"
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            size="large"
            :prefix-icon="Lock"
            show-password
            autocomplete="current-password"
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-button
          type="primary"
          size="large"
          :loading="loading"
          class="login-btn"
          @click="handleLogin"
        >
          {{ loading ? '登录中…' : '登 录' }}
        </el-button>
      </el-form>

      <div class="hint">
        默认账户：<code>admin</code> / <code>admin123</code>
      </div>
    </div>

    <!-- Background decoration -->
    <div class="bg-circle c1"></div>
    <div class="bg-circle c2"></div>
    <div class="bg-circle c3"></div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const router  = useRouter()
const auth    = useAuthStore()
const formRef = ref()
const loading = ref(false)

const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码',   trigger: 'blur' }]
}

async function handleLogin() {
  await formRef.value.validate()
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    router.push('/')
  } catch (e) {
    ElMessage.error('用户名或密码错误')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-bg {
  min-height: 100vh;
  background: linear-gradient(135deg, #0f172a 0%, #1e1b4b 50%, #0f172a 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
}

/* Decorative circles */
.bg-circle {
  position: absolute;
  border-radius: 50%;
  opacity: .06;
  background: #4f6ef7;
  pointer-events: none;
}
.c1 { width: 500px; height: 500px; top: -150px; right: -100px; }
.c2 { width: 300px; height: 300px; bottom: -80px; left: -60px; background: #7c3aed; }
.c3 { width: 200px; height: 200px; top: 40%; left: 20%; background: #06b6d4; opacity: .04; }

.login-card {
  position: relative;
  z-index: 1;
  width: 420px;
  background: rgba(255,255,255,.97);
  border-radius: 20px;
  padding: 40px 40px 32px;
  box-shadow: 0 25px 60px rgba(0,0,0,.4), 0 0 0 1px rgba(255,255,255,.1);
  backdrop-filter: blur(20px);
}

.login-header { text-align: center; margin-bottom: 32px; }
.logo-wrap {
  width: 64px;
  height: 64px;
  background: linear-gradient(135deg, #4f6ef7, #7c3aed);
  border-radius: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 16px;
  box-shadow: 0 8px 24px rgba(79,110,247,.4);
}
.logo-emoji { font-size: 30px; }
h1 {
  margin: 0 0 6px;
  font-size: 1.6rem;
  font-weight: 800;
  color: #0f172a;
  letter-spacing: -.02em;
}
.subtitle { margin: 0; color: #64748b; font-size: .875rem; }

.login-form { margin-bottom: 8px; }
.login-btn {
  width: 100%;
  margin-top: 8px;
  height: 44px;
  font-size: 1rem;
  font-weight: 600;
  border-radius: 10px !important;
  background: linear-gradient(135deg, #4f6ef7, #7c3aed) !important;
  border: none !important;
  box-shadow: 0 4px 14px rgba(79,110,247,.4) !important;
  transition: transform .15s, box-shadow .15s !important;
}
.login-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 6px 20px rgba(79,110,247,.5) !important;
}
.login-btn:active { transform: translateY(0); }

.hint {
  margin-top: 20px;
  text-align: center;
  font-size: .78rem;
  color: #94a3b8;
  padding-top: 16px;
  border-top: 1px solid #f1f5f9;
}
code {
  background: #f1f5f9;
  padding: 2px 7px;
  border-radius: 5px;
  color: #475569;
  font-family: var(--font-mono);
  font-size: .78rem;
}
</style>
