<template>
  <div class="login-bg">
    <el-card class="login-card" shadow="always">
      <div class="login-header">
        <span class="lock-icon">🔒</span>
        <h1>HutuLock Admin</h1>
        <p class="subtitle">分布式锁管理控制台</p>
      </div>

      <el-form :model="form" :rules="rules" ref="formRef" @submit.prevent="handleLogin">
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
          style="width:100%;margin-top:8px"
          @click="handleLogin"
        >
          登 录
        </el-button>
      </el-form>

      <div class="hint">默认账户：<code>admin</code> / <code>admin123</code></div>
    </el-card>
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
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%);
  display: flex;
  align-items: center;
  justify-content: center;
}
.login-card {
  width: 400px;
  border-radius: 16px;
  padding: 16px;
}
.login-header {
  text-align: center;
  margin-bottom: 28px;
}
.lock-icon { font-size: 48px; display: block; margin-bottom: 8px; }
h1 { margin: 0 0 4px; font-size: 1.6rem; color: #1a1a2e; }
.subtitle { margin: 0; color: #888; font-size: .9rem; }
.hint { margin-top: 16px; text-align: center; font-size: .8rem; color: #aaa; }
code { background: #f5f7fa; padding: 1px 6px; border-radius: 4px; color: #555; }
</style>
