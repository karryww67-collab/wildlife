<template>
  <div class="login-page">
    <!-- ══════════ 背景层（按指定实现，一比一照抄） ══════════ -->
    <div class="login-bg">
      <!-- 渐变底色层 -->
      <div class="bg-base"></div>
      <!-- 网格层 -->
      <div class="bg-grid"></div>
      <!-- YOLO虚线框 -->
      <div class="yolo-group">
        <div class="yolo"></div>
        <div class="yolo"></div>
        <div class="yolo"></div>
      </div>

      <!-- 猕猴 单线轮廓 -->
      <svg class="animal monkey" viewBox="0 0 100 90">
        <path
          d="M30,20 C20,25 22,38 32,42 C42,46 52,44 58,36 C62,28 54,20 44,20 Z M32,42 L30,70 M58,36 L60,70 M60,50 C72,48 78,58 72,72"
          fill="none"
          stroke="#fff"
          stroke-width="0.7"
        />
      </svg>
      <!-- 涉禽水鸟 -->
      <svg class="animal bird" viewBox="0 0 110 90">
        <path
          d="M50,15 C40,25 42,40 50,48 C60,54 72,48 78,42 M50,48 L48,80 M72,48 L74,80"
          fill="none"
          stroke="#fff"
          stroke-width="0.7"
        />
      </svg>
      <!-- 羚羊 -->
      <svg class="animal antelope" viewBox="0 0 120 90">
        <path
          d="M40,25 C30,10 22,14 30,26 C36,32 50,30 64,34 C74,38 76,50 72,62 M50,32 L48,78 M70,36 L72,78"
          fill="none"
          stroke="#fff"
          stroke-width="0.7"
        />
      </svg>
      <!-- 野猪 -->
      <svg class="animal boar" viewBox="0 0 100 90">
        <path
          d="M20,40 C14,44 16,54 26,56 C46,54 66,56 74,52 C78,58 76,66 70,68 M28,56 L26,78 M68,54 L70,78"
          fill="none"
          stroke="#fff"
          stroke-width="0.7"
        />
      </svg>
    </div>

    <!-- ══════════ 登录卡片（z-index:10，浮在背景之上） ══════════ -->
    <div class="login-card">
      <div class="login-logo">
        <span class="logo-icon">🐾</span>
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
/* ══ 页面容器：背景铺满、卡片居中 ════════════════════════════ */
.login-page {
  position: relative;
  width: 100%;
  height: 100vh;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ══ 背景层 ══════════════════════════════════════════════════ */
.login-bg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
  z-index: 0;
  pointer-events: none;
}
/* 底层渐变：深森林青绿色，四角更深，中心亮 */
.bg-base {
  width: 100%;
  height: 100%;
  background: radial-gradient(ellipse at center, #15806f 0%, #09443a 100%);
  position: absolute;
}
/* 网格层，淡淡的白色网格 */
.bg-grid {
  width: 100%;
  height: 100%;
  position: absolute;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.06) 1px, transparent 1px);
  background-size: 60px 60px;
}
/* YOLO虚线检测框 */
.yolo-group {
  width: 100%;
  height: 100%;
  position: absolute;
}
.yolo {
  position: absolute;
  border: 1px dashed rgba(255, 255, 255, 0.09);
  border-radius: 2px;
}
.yolo:nth-child(1) {
  top: 20%;
  left: 25%;
  width: 85px;
  height: 60px;
}
.yolo:nth-child(2) {
  top: 25%;
  right: 24%;
  width: 70px;
  height: 50px;
}
.yolo:nth-child(3) {
  bottom: 22%;
  left: 20%;
  width: 95px;
  height: 65px;
}
/* 动物统一样式：10%透明度，纯白单线，无填充 */
.animal {
  position: absolute;
  width: 140px;
  height: 110px;
  opacity: 0.1;
  fill: none;
  stroke: #ffffff;
  stroke-width: 0.7;
}
.monkey {
  left: 7%;
  bottom: 10%;
}
.bird {
  left: 11%;
  top: 11%;
}
.antelope {
  right: 9%;
  top: 9%;
}
.boar {
  right: 7%;
  bottom: 11%;
}

/* ══ 玻璃拟态登录卡片 ══════════════════════════════════════════ */
.login-card {
  position: relative;
  z-index: 10;
  width: 380px;
  padding: 42px 38px;
  display: flex;
  flex-direction: column;
  border-radius: 18px;
  background: linear-gradient(158deg, rgba(4, 26, 22, 0.62) 0%, rgba(4, 26, 22, 0.4) 100%);
  -webkit-backdrop-filter: blur(30px) saturate(165%);
  backdrop-filter: blur(30px) saturate(165%);
  border: 1px solid rgba(255, 255, 255, 0.22);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.05),
    0 26px 76px rgba(0, 12, 10, 0.55),
    0 0 70px rgba(39, 201, 176, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.28),
    inset 0 -1px 0 rgba(39, 201, 176, 0.12);
}

.login-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.logo-icon {
  font-size: 26px;
  line-height: 1;
}

.logo-title {
  font-size: 15.5px;
  font-weight: 700;
  letter-spacing: 0.4px;
  color: #ffffff;
  text-shadow: 0 1px 10px rgba(39, 201, 176, 0.25);
}

.login-subtitle {
  font-size: 13px;
  color: rgba(226, 245, 238, 0.62);
  font-weight: 400;
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
  color: rgba(226, 245, 238, 0.72);
  letter-spacing: 0.3px;
}

.form-item input {
  padding: 11px 14px;
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.17);
  border-radius: 8px;
  color: #ffffff;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s, background 0.2s;
}

.form-item input::placeholder {
  color: rgba(226, 245, 238, 0.38);
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
  background: linear-gradient(135deg, #0f6b5b 0%, #27c9b0 100%);
  color: #ffffff;
  border: none;
  border-radius: 8px;
  font-size: 14.5px;
  font-weight: 600;
  letter-spacing: 2px;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(15, 107, 91, 0.4);
  transition: box-shadow 0.25s, transform 0.15s, filter 0.25s;
}

.btn-login:hover:not(:disabled) {
  filter: brightness(1.1);
  box-shadow: 0 10px 30px rgba(39, 201, 176, 0.45), 0 0 26px rgba(39, 201, 176, 0.32);
  transform: translateY(-1px);
}

.btn-login:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.no-register {
  margin-top: 22px;
  font-size: 11.5px;
  color: rgba(226, 245, 238, 0.42);
  text-align: center;
}
</style>
