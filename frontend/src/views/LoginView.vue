<template>
  <div class="login-page">
    <!-- ══════════ 背景层（自底向上严格叠加） ══════════ -->
    <div class="login-bg">
      <!-- 第 1 层：深森林青绿全局渐变，四角深、中心亮 -->
      <div class="bg-base"></div>

      <!-- 第 2 层：遥感山林地形底图（真实卫星地形影像） -->
      <div class="bg-terrain"></div>
      <!-- 第 2b 层：地形等高线 -->
      <div class="bg-contour">
        <svg ref="terrainSvg" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice"></svg>
      </div>

      <!-- 第 3 层：数字网格 + 点阵 + 数据流线 + 粒子 -->
      <div class="bg-grid"></div>
      <div class="bg-dots"></div>
      <div class="bg-flow">
        <div class="flow-h fh1"></div>
        <div class="flow-h fh2"></div>
        <div class="flow-h fh3"></div>
        <div class="flow-v fv1"></div>
        <div class="flow-v fv2"></div>
      </div>
      <div class="bg-particles">
        <canvas ref="particlesCanvas"></canvas>
      </div>

      <!-- 第 4 层：散落的 YOLO 虚线检测框 -->
      <div class="bg-yolo">
        <div class="yolo y1"></div>
        <div class="yolo y2"></div>
        <div class="yolo y3"></div>
        <div class="yolo y4"></div>
        <div class="yolo y5"></div>
        <div class="yolo y6"></div>
      </div>

      <!-- 第 5 层：细白单线动物轮廓（真实手绘线稿，位图无渲染变形） -->
      <img class="animal monkey" src="../assets/animals/macaque.png" alt="" />
      <img class="animal bird" src="../assets/animals/heron.png" alt="" />
      <img class="animal antelope" src="../assets/animals/antelope.png" alt="" />
      <img class="animal boar" src="../assets/animals/boar.png" alt="" />

      <!-- 第 6 层：微弱扫光 + 云雾 + 暗角 -->
      <div class="bg-scan"></div>
      <div class="bg-mist">
        <div class="mist m1"></div>
        <div class="mist m2"></div>
        <div class="mist m3"></div>
      </div>
      <div class="bg-vignette"></div>
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
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { login, setToken, removeToken } from '@/api'

const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMsg = ref('')

const terrainSvg = ref<SVGSVGElement | null>(null)
const particlesCanvas = ref<HTMLCanvasElement | null>(null)

let rafId = 0
let detachResize: (() => void) | null = null

/* ── 遥感地形底图：以若干山丘为中心的闭合等高线 ────────────────── */
function buildTerrain() {
  const svg = terrainSvg.value
  if (!svg) return
  const NS = 'http://www.w3.org/2000/svg'
  const centers = [
    { x: 300, y: 240, r: 290, seed: 1.2 },
    { x: 1140, y: 680, r: 340, seed: 3.7 },
    { x: 780, y: 90, r: 210, seed: 5.4 },
    { x: 110, y: 800, r: 190, seed: 7.9 },
  ]
  let d = ''
  for (const c of centers) {
    for (let ring = 1; ring <= 9; ring++) {
      const base = (c.r / 9) * ring
      const pts: string[] = []
      for (let i = 0; i < 96; i++) {
        const t = (i / 96) * Math.PI * 2
        const wob =
          0.2 * Math.sin(3 * t + c.seed) +
          0.12 * Math.sin(5 * t + c.seed * 2.3) +
          0.07 * Math.sin(9 * t + c.seed * 3.1)
        const rr = base * (1 + wob)
        pts.push(
          (c.x + Math.cos(t) * rr * 1.35).toFixed(1) + ' ' +
          (c.y + Math.sin(t) * rr * 0.86).toFixed(1)
        )
      }
      d += 'M' + pts.join('L') + 'Z'
    }
  }
  const path = document.createElementNS(NS, 'path')
  path.setAttribute('d', d)
  path.setAttribute('fill', 'none')
  path.setAttribute('stroke', 'rgba(190, 238, 224, 0.14)')
  path.setAttribute('stroke-width', '1')
  svg.appendChild(path)
}

/* ── 数据流粒子 ────────────────────────────────────────────────── */
function startParticles() {
  const cv = particlesCanvas.value
  if (!cv) return
  const ctx = cv.getContext('2d')
  if (!ctx) return

  let W = 0
  let H = 0
  const DPR = Math.min(window.devicePixelRatio || 1, 2)
  const N = 40
  const pts: { x: number; y: number; vx: number; vy: number; r: number }[] = []

  const resize = () => {
    W = cv.clientWidth
    H = cv.clientHeight
    cv.width = Math.round(W * DPR)
    cv.height = Math.round(H * DPR)
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0)
  }
  resize()
  window.addEventListener('resize', resize)
  detachResize = () => window.removeEventListener('resize', resize)

  for (let i = 0; i < N; i++) {
    pts.push({
      x: Math.random() * 1440,
      y: Math.random() * 900,
      vx: (Math.random() - 0.5) * 0.12,
      vy: (Math.random() - 0.5) * 0.12,
      r: 0.6 + Math.random() * 1.0,
    })
  }

  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches

  const frame = () => {
    ctx.clearRect(0, 0, W, H)
    for (const p of pts) {
      if (!reduced) {
        p.x += p.vx
        p.y += p.vy
        if (p.x < -20) p.x = W + 20
        if (p.x > W + 20) p.x = -20
        if (p.y < -20) p.y = H + 20
        if (p.y > H + 20) p.y = -20
      }
      ctx.beginPath()
      ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2)
      ctx.fillStyle = 'rgba(170, 238, 214, 0.38)'
      ctx.fill()
    }
    for (let i = 0; i < pts.length; i++) {
      for (let j = i + 1; j < pts.length; j++) {
        const dx = pts[i].x - pts[j].x
        const dy = pts[i].y - pts[j].y
        const d2 = dx * dx + dy * dy
        if (d2 < 24000) {
          ctx.strokeStyle = 'rgba(150, 226, 200, ' + ((1 - d2 / 24000) * 0.08).toFixed(3) + ')'
          ctx.lineWidth = 0.7
          ctx.beginPath()
          ctx.moveTo(pts[i].x, pts[i].y)
          ctx.lineTo(pts[j].x, pts[j].y)
          ctx.stroke()
        }
      }
    }
    rafId = requestAnimationFrame(frame)
  }
  rafId = requestAnimationFrame(frame)
}

onMounted(() => {
  removeToken()
  buildTerrain()
  startParticles()
})

onBeforeUnmount(() => {
  if (rafId) cancelAnimationFrame(rafId)
  if (detachResize) detachResize()
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
/* ══ 页面容器 ════════════════════════════════════════════════ */
.login-page {
  position: relative;
  width: 100%;
  height: 100vh;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

.login-bg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: hidden;
  z-index: 0;
  pointer-events: none;
}

/* ── 第 1 层：全局渐变，四角深、中心亮 ───────────────────── */
.bg-base {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse 920px 640px at 50% 46%, rgba(39, 201, 176, 0.2) 0%, rgba(15, 107, 91, 0.13) 44%, transparent 70%),
    linear-gradient(160deg, #05130f 0%, #0a3028 48%, #051210 100%);
}

/* ── 第 2 层：遥感山林地形底图（真实卫星地形）─────────────── */
.bg-terrain {
  position: absolute;
  inset: 0;
  background: url("../assets/terrain.jpg") center center / cover no-repeat;
  opacity: 0.42;
  filter: blur(0.4px);
}
/* ── 第 2b 层：地形等高线 ────────────────────────────────── */
.bg-contour {
  position: absolute;
  inset: -6%;
  opacity: 0.6;
}
.bg-contour svg {
  width: 100%;
  height: 100%;
  display: block;
}

/* ── 第 3 层：网格 + 点阵 + 数据流线 + 粒子 ───────────────── */
.bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(to right, rgba(255, 255, 255, 0.05) 1px, transparent 1px),
    linear-gradient(to bottom, rgba(255, 255, 255, 0.05) 1px, transparent 1px);
  background-size: 62px 62px;
  -webkit-mask-image: radial-gradient(ellipse 66% 66% at 50% 48%, #000 0%, rgba(0, 0, 0, 0.5) 56%, transparent 86%);
  mask-image: radial-gradient(ellipse 66% 66% at 50% 48%, #000 0%, rgba(0, 0, 0, 0.5) 56%, transparent 86%);
}

.bg-dots {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(rgba(255, 255, 255, 0.09) 1px, transparent 1px);
  background-size: 26px 26px;
  -webkit-mask-image: radial-gradient(ellipse 72% 72% at 50% 50%, transparent 24%, #000 78%);
  mask-image: radial-gradient(ellipse 72% 72% at 50% 50%, transparent 24%, #000 78%);
}

.bg-flow {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.flow-h,
.flow-v {
  position: absolute;
  background: linear-gradient(90deg, transparent, rgba(120, 235, 205, 0.28), transparent);
}
.flow-h {
  height: 1px;
  left: 4%;
  right: 4%;
}
.flow-v {
  width: 1px;
  top: 6%;
  bottom: 6%;
  background: linear-gradient(180deg, transparent, rgba(120, 235, 205, 0.22), transparent);
}
.fh1 { top: 22%; animation: flowA 9s ease-in-out infinite; }
.fh2 { top: 73%; animation: flowA 12s ease-in-out infinite reverse; }
.fh3 { top: 47%; left: 38%; right: 38%; opacity: 0.5; animation: flowA 7s ease-in-out infinite; }
.fv1 { left: 19%; animation: flowB 11s ease-in-out infinite; }
.fv2 { left: 82%; animation: flowB 14s ease-in-out infinite reverse; }
@keyframes flowA {
  0%, 100% { opacity: 0.22; }
  50%      { opacity: 0.8; }
}
@keyframes flowB {
  0%, 100% { opacity: 0.18; }
  50%      { opacity: 0.62; }
}

.bg-particles {
  position: absolute;
  inset: 0;
}
.bg-particles canvas {
  width: 100%;
  height: 100%;
  display: block;
}

/* ── 第 4 层：YOLO 虚线检测框 ─────────────────────────────── */
.bg-yolo {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.yolo {
  position: absolute;
  border: 1px dashed rgba(220, 245, 236, 0.16);
  border-radius: 2px;
}
.y1 { top: 17%; left: 27%; width: 92px; height: 66px; }
.y2 { top: 24%; right: 21%; width: 74px; height: 54px; }
.y3 { bottom: 26%; left: 19%; width: 104px; height: 72px; }
.y4 { bottom: 17%; right: 29%; width: 84px; height: 60px; }
.y5 { top: 42%; left: 8%; width: 62px; height: 46px; }
.y6 { bottom: 40%; right: 10%; width: 70px; height: 50px; }

/* ── 第 5 层：动物线稿（真实手绘线稿，位图无变形） ─────────── */
.animal {
  position: absolute;
  height: auto;
  opacity: 0.3;
  user-select: none;
}
.monkey {
  left: 6%;
  bottom: 9%;
  width: 250px;
}
.bird {
  left: 9%;
  top: 11%;
  width: 270px;
}
.antelope {
  right: 8%;
  top: 9%;
  width: 300px;
}
.boar {
  right: 6%;
  bottom: 10%;
  width: 380px;
}

/* ── 第 6 层：扫光 / 云雾 / 暗角 ──────────────────────────── */
.bg-scan {
  position: absolute;
  left: -20%;
  right: -20%;
  height: 38vh;
  top: -38vh;
  background: linear-gradient(
    180deg,
    transparent 0%,
    rgba(39, 201, 176, 0.04) 40%,
    rgba(39, 201, 176, 0.085) 50%,
    rgba(39, 201, 176, 0.04) 60%,
    transparent 100%
  );
  animation: scanMove 20s linear infinite;
  pointer-events: none;
  mix-blend-mode: screen;
}
@keyframes scanMove {
  from { transform: translateY(0); }
  to   { transform: translateY(160vh); }
}

.bg-mist {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.mist {
  position: absolute;
  border-radius: 50%;
  filter: blur(70px);
  background: radial-gradient(circle, rgba(150, 228, 208, 0.13), transparent 70%);
}
.m1 { width: 640px; height: 360px; left: -8%;   top: 52%;    animation: mistA 46s ease-in-out infinite; }
.m2 { width: 720px; height: 400px; right: -12%; top: 4%;     animation: mistB 54s ease-in-out infinite; }
.m3 { width: 580px; height: 320px; left: 34%;   bottom: -12%; animation: mistA 40s ease-in-out infinite reverse; }
@keyframes mistA {
  0%, 100% { transform: translate3d(0, 0, 0) scale(1); }
  50%      { transform: translate3d(44px, -20px, 0) scale(1.07); }
}
@keyframes mistB {
  0%, 100% { transform: translate3d(0, 0, 0) scale(1); }
  50%      { transform: translate3d(-50px, 24px, 0) scale(1.05); }
}

.bg-vignette {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: radial-gradient(ellipse at 50% 50%, transparent 38%, rgba(2, 10, 8, 0.62) 100%);
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
  background: linear-gradient(158deg, rgba(6, 30, 25, 0.58) 0%, rgba(6, 30, 25, 0.36) 100%);
  -webkit-backdrop-filter: blur(30px) saturate(165%);
  backdrop-filter: blur(30px) saturate(165%);
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
