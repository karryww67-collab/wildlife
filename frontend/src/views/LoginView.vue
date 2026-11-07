<template>
  <div class="login-page">
    <!-- ══════════ 背景层（按 code_20261107 实现） ══════════ -->
    <div class="login-bg">
      <!-- 遥感地形底图 -->
      <div class="bg-terrain"></div>

      <!-- 全局渐变遮罩 -->
      <div class="bg-gradient"></div>

      <!-- 数字网格 -->
      <div class="bg-grid"></div>

      <!-- 数据流粒子与扫描线 -->
      <div class="bg-dataflow">
        <span class="data-particle"></span>
        <span class="data-particle"></span>
        <span class="data-particle"></span>
        <span class="scan-line"></span>
      </div>

      <!-- YOLO 目标检测虚线框 -->
      <div class="yolo-box-group">
        <div class="yolo-box yolo-box-1"></div>
        <div class="yolo-box yolo-box-2"></div>
        <div class="yolo-box yolo-box-3"></div>
        <div class="yolo-box yolo-box-4"></div>
      </div>

      <!-- 野生动物线稿暗纹（从参考图提取：仅保留亮线，背景透明） -->
      <img class="animal-silhouette macaque" src="../assets/animals/macaque.png" alt="" />
      <img class="animal-silhouette heron" src="../assets/animals/heron.png" alt="" />
      <img class="animal-silhouette antelope" src="../assets/animals/antelope.png" alt="" />
      <img class="animal-silhouette boar" src="../assets/animals/boar.png" alt="" />
    </div>

    <!-- ══════════ 玻璃拟态登录卡片 ══════════ -->
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
/* ══ 页面容器：居中承载卡片，背景层绝对定位填充 ══════════════ */
.login-page {
  position: relative;
  width: 100%;
  height: 100vh;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ══ 外层背景容器 ══════════════════════════════════════════════ */
.login-bg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
  z-index: 0;
}

/* 第一层：遥感地形底图 */
.bg-terrain {
  position: absolute;
  inset: 0;
  background-image:
    radial-gradient(circle at 20% 30%, rgba(255, 255, 255, 0.08) 1px, transparent 1px),
    radial-gradient(circle at 70% 60%, rgba(255, 255, 255, 0.06) 1px, transparent 1px),
    linear-gradient(135deg, #07453a 0%, #0f6b5b 45%, #1b9986 100%);
  background-size: 3px 3px, 2px 2px, 100% 100%;
  filter: blur(0.4px);
  opacity: 0.85;
}

/* 第二层：深青绿色渐变遮罩，四角更深 */
.bg-gradient {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse at 50% 50%, rgba(27, 153, 134, 0.35) 0%, rgba(7, 69, 58, 0.85) 70%),
    linear-gradient(135deg, #06352c 0%, #0c5a4d 50%, #07453a 100%);
  opacity: 0.9;
}

/* 第三层：数字网格 */
.bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.07) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.07) 1px, transparent 1px);
  background-size: 55px 55px;
  opacity: 0.55;
  mask-image: radial-gradient(ellipse at 50% 50%, black 40%, transparent 75%);
  -webkit-mask-image: radial-gradient(ellipse at 50% 50%, black 40%, transparent 75%);
}

/* 第四层：数据流粒子与扫描线 */
.bg-dataflow {
  position: absolute;
  inset: 0;
  opacity: 0.35;
}

.data-particle {
  position: absolute;
  width: 2px;
  height: 2px;
  background: rgba(52, 226, 201, 0.75);
  border-radius: 50%;
  box-shadow: 0 0 8px rgba(52, 226, 201, 0.45);
}

.data-particle:nth-child(1) {
  top: 18%;
  left: 26%;
  animation: float 9s infinite ease-in-out;
}

.data-particle:nth-child(2) {
  top: 64%;
  left: 72%;
  animation: float 11s infinite ease-in-out 2s;
}

.data-particle:nth-child(3) {
  top: 42%;
  left: 44%;
  animation: float 13s infinite ease-in-out 4s;
}

.scan-line {
  position: absolute;
  left: 0;
  right: 0;
  top: 30%;
  height: 1px;
  background: linear-gradient(
    90deg,
    transparent,
    rgba(52, 226, 201, 0.35),
    rgba(52, 226, 201, 0.55),
    rgba(52, 226, 201, 0.35),
    transparent
  );
  animation: scan 7s infinite linear;
}

/* 第五层：YOLO 虚线检测框 */
.yolo-box-group {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.yolo-box {
  position: absolute;
  border: 1px dashed rgba(255, 255, 255, 0.12);
  border-radius: 2px;
}

.yolo-box-1 {
  top: 16%;
  left: 28%;
  width: 90px;
  height: 64px;
}

.yolo-box-2 {
  top: 24%;
  right: 22%;
  width: 72px;
  height: 52px;
}

.yolo-box-3 {
  bottom: 28%;
  left: 18%;
  width: 110px;
  height: 78px;
}

.yolo-box-4 {
  bottom: 18%;
  right: 30%;
  width: 82px;
  height: 58px;
}

/* 动物线稿暗纹：统一外轮廓单线，无填充、无内部纹理 */
.animal-silhouette {
  position: absolute;
  height: auto;
  pointer-events: none;
  opacity: 0.12;
  user-select: none;
}

/* 猕猴：坐姿侧视（左上） */
.macaque {
  width: 300px;
  left: 2%;
  top: 5%;
}

/* 水鸟：S 颈长腿（右上） */
.heron {
  width: 300px;
  right: 2%;
  top: 4%;
}

/* 羚羊：后掠长角（左下） */
.antelope {
  width: 400px;
  left: 1%;
  bottom: 2%;
}

/* 野猪：敦实拱嘴（右下） */
.boar {
  width: 420px;
  right: 1%;
  bottom: 4%;
}
/* 扫描线动画 */
@keyframes scan {
  0% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(120px);
  }
  100% {
    transform: translateY(0);
  }
}

/* 粒子浮动动画 */
@keyframes float {
  0%,
  100% {
    transform: translateY(0) translateX(0);
    opacity: 0.4;
  }
  50% {
    transform: translateY(-18px) translateX(10px);
    opacity: 0.8;
  }
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
  background: linear-gradient(158deg, rgba(4, 26, 22, 0.62) 0%, rgba(4, 26, 22, 0.40) 100%);
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
