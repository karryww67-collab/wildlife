<template>
  <div class="login-page">
    <!-- 背景：整张生态监测主题图（含地形、网格、YOLO 框、动物线稿、云雾） -->
    <div class="login-bg"></div>
    <!-- 卡片后方柔光压暗，保证卡片与文字的对比度 -->
    <div class="bg-halo"></div>

    <!-- 玻璃拟态登录卡片 -->
    <div class="login-card">
      <div class="login-logo">
        <span class="logo-icon">
          <svg viewBox="0 0 34 26" aria-hidden="true">
            <!-- 后脚印（大，左上） -->
            <ellipse cx="9.4" cy="16.4" rx="5.1" ry="4.3" />
            <ellipse cx="4.3" cy="10.6" rx="2.0" ry="2.6" />
            <ellipse cx="8.1" cy="8.4" rx="2.1" ry="2.7" />
            <ellipse cx="12.2" cy="8.4" rx="2.1" ry="2.7" />
            <ellipse cx="16.0" cy="10.6" rx="2.0" ry="2.6" />
            <!-- 前脚印（小，右下） -->
            <ellipse cx="25.0" cy="21.2" rx="3.9" ry="3.3" />
            <ellipse cx="21.1" cy="16.9" rx="1.6" ry="2.0" />
            <ellipse cx="24.0" cy="15.4" rx="1.6" ry="2.1" />
            <ellipse cx="27.1" cy="15.4" rx="1.6" ry="2.1" />
            <ellipse cx="30.0" cy="16.9" rx="1.6" ry="2.0" />
          </svg>
        </span>
        <span class="logo-title">野生动物图像批量识别系统</span>
      </div>
      <h2 class="login-subtitle">后台管理登录</h2>

      <form @submit.prevent="handleLogin" class="login-form">
        <div class="form-item">
          <label>账号</label>
          <input
            v-model="username"
            type="text"
            placeholder="请输入账号"
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
.login-page {
  position: relative;
  width: 100%;
  height: 100vh;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 背景图：铺满整屏 */
.login-bg {
  position: absolute;
  inset: 0;
  background: #0a3028 url("../assets/login-bg.jpg") center center / cover no-repeat;
}

/* 卡片后方柔光：轻微压暗，让玻璃卡片浮起来 */
.bg-halo {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 820px;
  height: 700px;
  transform: translate(-50%, -50%);
  background: radial-gradient(circle, rgba(3, 22, 18, 0.55) 0%, rgba(3, 22, 18, 0.28) 46%, transparent 72%);
  pointer-events: none;
}

/* ══ 玻璃拟态登录卡片 ══════════════════════════════════════════ */
.login-card {
  position: relative;
  z-index: 10;
  width: 440px;
  padding: 44px 40px;
  display: flex;
  flex-direction: column;
  border-radius: 18px;
  background: linear-gradient(158deg, rgba(6, 30, 25, 0.18) 0%, rgba(6, 30, 25, 0.06) 100%);
  -webkit-backdrop-filter: blur(9px) saturate(145%);
  backdrop-filter: blur(9px) saturate(145%);
  border: 1px solid rgba(255, 255, 255, 0.22);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.05),
    0 26px 76px rgba(0, 12, 10, 0.5),
    0 0 70px rgba(39, 201, 176, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.28),
    inset 0 -1px 0 rgba(39, 201, 176, 0.12);
}

.login-logo {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-bottom: 10px;
}

.logo-icon {
  width: 36px;
  height: 27px;
  flex: 0 0 auto;
  display: block;
}
.logo-icon svg {
  width: 100%;
  height: 100%;
  fill: #ffffff;
  display: block;
}

.logo-title {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.4px;
  color: #ffffff;
  text-shadow: 0 1px 10px rgba(39, 201, 176, 0.25);
}

.login-subtitle {
  font-size: 14px;
  color: #ffffff;
  font-weight: 500;
  text-align: center;
  letter-spacing: 1px;
  margin-bottom: 30px;
}

.login-form {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-item label {
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.95);
  letter-spacing: 0.3px;
}

.form-item input {
  padding: 11px 14px;
  background: rgba(255, 255, 255, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.38);
  border-radius: 6px;
  color: #ffffff;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s, background 0.2s;
}

.form-item input::placeholder {
  color: rgba(255, 255, 255, 0.6);
}

.form-item input:focus {
  border-color: rgba(39, 201, 176, 0.75);
  background: rgba(255, 255, 255, 0.14);
  box-shadow: 0 0 0 3px rgba(39, 201, 176, 0.16), 0 0 18px rgba(39, 201, 176, 0.12);
}

.form-item input:disabled {
  opacity: 0.6;
}

.error-msg {
  padding: 8px 12px;
  background: rgba(198, 40, 40, 0.22);
  border: 1px solid rgba(229, 115, 115, 0.6);
  border-radius: 6px;
  color: #ffd9d9;
  font-size: 13px;
}

.btn-login {
  margin-top: 6px;
  padding: 12px;
  background: linear-gradient(135deg, #12a894 0%, #35e3c6 100%);
  color: #ffffff;
  border: none;
  border-radius: 8px;
  font-size: 14.5px;
  font-weight: 600;
  letter-spacing: 1px;
  cursor: pointer;
  box-shadow: 0 8px 26px rgba(53, 227, 198, 0.38);
  transition: box-shadow 0.25s, transform 0.15s, filter 0.25s;
}

.btn-login:hover:not(:disabled) {
  filter: brightness(1.1);
  box-shadow: 0 10px 30px rgba(39, 201, 176, 0.45), 0 0 26px rgba(39, 201, 176, 0.32);
  transform: translateY(-1px);
}

.btn-login:disabled {
  opacity: 0.85;
  cursor: not-allowed;
}

.no-register {
  margin-top: 22px;
  font-size: 11.5px;
  color: rgba(255, 255, 255, 0.68);
  text-align: center;
}
</style>
