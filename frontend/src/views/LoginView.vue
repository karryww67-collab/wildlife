<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-logo">
        <span class="logo-icon">🐾</span>
        <span class="logo-title">野生动物图像批量识别系统</span>
      </div>
      <h2 class="login-subtitle">后台管理登录</h2>

      <form @submit.prevent="handleLogin" class="login-form">
        <div class="form-item">
          <label>用户名</label>
          <input
            v-model="username"
            type="text"
            placeholder="请输入用户名"
            autocomplete="username"
            :disabled="loading"
          />
        </div>
        <div class="form-item">
          <label>密码</label>
          <input
            v-model="password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
            :disabled="loading"
          />
        </div>

        <div v-if="errorMsg" class="error-msg">{{ errorMsg }}</div>

        <button type="submit" class="btn-login" :disabled="loading || !username || !password">
          {{ loading ? '登录中...' : '登录' }}
        </button>
      </form>

      <p class="no-register">系统不开放注册，请联系管理员获取账号</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { login, setToken, removeToken } from '@/api'

const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMsg = ref('')

// 每次到登录页都清除旧 token，强制重新登录
onMounted(() => {
  removeToken()
})

async function handleLogin() {
  if (!username.value || !password.value) return
  loading.value = true
  errorMsg.value = ''
  try {
    const res = await login(username.value, password.value)
    setToken(res.data.token)
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } catch (err: any) {
    errorMsg.value = err.response?.data?.error || '登录失败，请检查用户名和密码'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* 登录页：整屏背景图 + 居中毛玻璃卡片（图片放 public/login-bg.jpg） */
.login-page {
  position: relative;
  width: 100%;
  height: 100vh;
  /* 渐变仅作图片加载失败时的兜底底色 */
  background: #c4f7dd url('/login-bg.jpg') center center / cover no-repeat;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

/* 浅色薄纱：压一压背景图的杂乱度，保证卡片边缘与文字对比度 */
.login-page::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
    180deg,
    rgba(255, 255, 255, 0.24) 0%,
    rgba(255, 255, 255, 0.06) 55%,
    rgba(255, 255, 255, 0.3) 100%
  );
  pointer-events: none;
}

.login-card {
  position: relative;
  z-index: 1;
  width: 380px;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(10px) saturate(1.15);
  -webkit-backdrop-filter: blur(10px) saturate(1.15);
  border: 1px solid rgba(255, 255, 255, 0.78);
  border-radius: 16px;
  padding: 40px 36px;
  display: flex;
  flex-direction: column;
  align-items: center;
  box-shadow: 0 18px 44px rgba(16, 96, 63, 0.22);
}

.login-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.logo-icon { font-size: 28px; }

.logo-title {
  font-size: 15px;
  font-weight: 700;
  color: #0d5c3f;
  letter-spacing: 0.3px;
}

.login-subtitle {
  font-size: 14px;
  color: #4f8f74;
  font-weight: 400;
  margin-bottom: 28px;
}

.login-form {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-item label {
  font-size: 12px;
  color: #57907a;
}

.form-item input {
  padding: 10px 14px;
  background: #f7fffb;
  border: 1px solid #b3e8cd;
  border-radius: 6px;
  color: #14382a;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s, background 0.2s;
}

.form-item input::placeholder { color: #9dc4b3; }

.form-item input:focus {
  border-color: #2eb872;
  background: #fff;
  box-shadow: 0 0 0 3px rgba(46, 184, 114, 0.16);
}

.form-item input:disabled {
  opacity: 0.6;
}

.error-msg {
  padding: 8px 12px;
  background: #fdecec;
  border: 1px solid #e57373;
  border-radius: 4px;
  color: #c62828;
  font-size: 13px;
}

.btn-login {
  padding: 11px;
  background: #2eb872;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s, box-shadow 0.2s;
  margin-top: 4px;
}

.btn-login:hover:not(:disabled) {
  background: #25a765;
  box-shadow: 0 6px 16px rgba(46, 184, 114, 0.32);
}
.btn-login:disabled { opacity: 0.5; cursor: not-allowed; }

.no-register {
  margin-top: 20px;
  font-size: 12px;
  color: #6a9a83;
  text-align: center;
}
</style>
