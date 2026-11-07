<template>
  <div class="login-page">
    <!-- 四角压深 -->
    <div class="bg-corners" aria-hidden="true"></div>

    <!-- 层1：遥感地形等高线（JS 生成） -->
    <div class="bg-terrain">
      <svg ref="terrainSvg" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice"></svg>
    </div>

    <!-- 层2：数字网格 + 点阵 + 数据流线 -->
    <div class="bg-grid" aria-hidden="true"></div>
    <div class="bg-dots" aria-hidden="true"></div>
    <div class="bg-flow" aria-hidden="true">
      <div class="flow-h fh1"></div>
      <div class="flow-h fh2"></div>
      <div class="flow-h fh3"></div>
      <div class="flow-v fv1"></div>
      <div class="flow-v fv2"></div>
    </div>

    <!-- 层3：粒子 -->
    <div class="bg-particles">
      <canvas ref="particlesCanvas"></canvas>
    </div>

    <!-- 层4：野生动物线稿剪影（轮廓线，无填充） -->
    <div class="bg-animals" aria-hidden="true">
      <!-- 野猪（左下） -->
      <svg class="an-boar" viewBox="0 0 240 140">
        <path d="M198 102 C206 92 206 70 194 62 C180 54 158 50 138 50 C124 50 112 54 102 60 C92 66 82 70 72 74 C60 79 48 84 40 88 C34 91 32 96 36 99 C40 102 48 101 56 98 C60 96 64 94 68 92 C76 98 92 104 112 106 C140 109 176 108 198 102 Z" />
        <path d="M96 58 C100 48 110 46 114 54" />
        <path d="M42 92 C35 95 32 101 35 106" />
        <path d="M88 105 L84 124" />
        <path d="M106 107 L105 125" />
        <path d="M166 107 L167 125" />
        <path d="M184 105 L188 121" />
        <path d="M198 88 C208 82 209 70 203 62" />
        <circle cx="58" cy="84" r="1.8" />
      </svg>

      <!-- 猕猴（右下） -->
      <svg class="an-macaque" viewBox="0 0 240 140">
        <path d="M62 56 C58 46 66 39 75 41 C83 43 87 50 87 58 C95 62 106 66 118 68 C136 71 154 74 168 77 C178 79 182 85 178 91 C162 95 140 95 122 93 C104 91 88 87 78 81 C72 77 66 70 62 64 C58 62 56 60 62 56 Z" />
        <path d="M62 60 C54 60 52 67 58 70 C61 72 65 71 67 68" />
        <path d="M73 40 C69 33 60 36 61 44" />
        <circle cx="69" cy="55" r="2" />
        <path d="M84 82 L80 108" />
        <path d="M96 86 L95 110" />
        <path d="M152 92 L151 114" />
        <path d="M166 90 L170 110" />
        <path d="M178 84 C194 80 204 68 200 56 C197 46 188 43 182 49" />
      </svg>

      <!-- 鸟类（右上） -->
      <svg class="an-bird" viewBox="0 0 220 130">
        <path d="M110 72 C96 52 72 54 52 78" />
        <path d="M110 72 C124 52 148 54 168 78" />
        <path d="M110 72 C108 64 104 60 100 58 C106 56 114 56 120 58 C116 60 112 64 110 72 Z" />
        <path d="M100 58 C96 52 92 50 88 50" />
      </svg>

      <!-- 羚羊（左上） -->
      <svg class="an-antelope" viewBox="0 0 240 140">
        <path d="M70 86 C70 76 84 70 106 68 C128 66 144 70 150 77 C154 82 151 88 144 92 C131 98 106 100 90 97 C78 95 70 92 70 86 Z" />
        <path d="M147 76 C150 64 156 52 164 42" />
        <path d="M139 80 C143 68 149 57 157 49" />
        <path d="M164 42 C171 35 181 36 183 43 C184 49 178 53 171 52 C167 51 165 47 164 42" />
        <path d="M168 39 C164 26 170 13 182 8" />
        <path d="M178 40 C183 26 192 16 204 17" />
        <path d="M86 98 L84 122" />
        <path d="M100 99 L99 123" />
        <path d="M134 98 L135 123" />
        <path d="M148 92 L154 114" />
        <path d="M70 78 C62 74 60 66 64 60" />
        <circle cx="173" cy="43" r="1.6" />
      </svg>
    </div>

    <!-- 层5：YOLO 目标检测框虚线 -->
    <div class="bg-boxes" aria-hidden="true">
      <div class="det-box d1"><span>野猪 0.91</span></div>
      <div class="det-box d2"><span>猕猴 0.87</span></div>
      <div class="det-box d3"><span>赤麂 0.83</span></div>
      <div class="det-box d4"><span>白鹇 0.76</span></div>
      <div class="det-box d5"><span>羚羊 0.80</span></div>
      <div class="det-box d6"><span>豹猫 0.72</span></div>
    </div>

    <!-- 层6：微弱扫光 + 云雾 -->
    <div class="bg-scan" aria-hidden="true"></div>
    <div class="bg-mist" aria-hidden="true">
      <div class="mist m1"></div>
      <div class="mist m2"></div>
      <div class="mist m3"></div>
    </div>

    <!-- 前景：玻璃拟态登录卡片 -->
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

/* ── 地形等高线：以若干"山丘"为中心的闭合等值线 ───────────────── */
function buildTerrain() {
  const svg = terrainSvg.value
  if (!svg) return
  const NS = 'http://www.w3.org/2000/svg'
  const centers = [
    { x: 340, y: 250, r: 300, seed: 1.2 },
    { x: 1120, y: 700, r: 360, seed: 3.7 },
    { x: 800, y: 90, r: 220, seed: 5.4 },
    { x: 120, y: 780, r: 200, seed: 7.9 },
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
  path.setAttribute('stroke', 'rgba(190,238,224,.13)')
  path.setAttribute('stroke-width', '1')
  svg.appendChild(path)
}

/* ── 粒子：缓慢漂浮 + 近邻细连线 ──────────────────────────────── */
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
      ctx.fillStyle = 'rgba(170,238,214,.40)'
      ctx.fill()
    }

    for (let i = 0; i < pts.length; i++) {
      for (let j = i + 1; j < pts.length; j++) {
        const dx = pts[i].x - pts[j].x
        const dy = pts[i].y - pts[j].y
        const d2 = dx * dx + dy * dy
        if (d2 < 24000) {
          ctx.strokeStyle = 'rgba(150,226,200,' + ((1 - d2 / 24000) * 0.09).toFixed(3) + ')'
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

// 每次到登录页都清除旧 token，强制重新登录
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
/* ══ 底色：四角深、中心柔亮 ═══════════════════════════════════ */
.login-page {
  position: relative;
  width: 100%;
  height: 100vh;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(ellipse 900px 620px at 50% 46%, rgba(39, 201, 176, 0.16) 0%, rgba(15, 107, 91, 0.1) 42%, transparent 68%),
    linear-gradient(160deg, #04120f 0%, #0a3028 46%, #04110e 100%);
}

.bg-corners {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(ellipse 55% 55% at 0% 0%, rgba(2, 8, 7, 0.85), transparent 70%),
    radial-gradient(ellipse 55% 55% at 100% 0%, rgba(2, 8, 7, 0.85), transparent 70%),
    radial-gradient(ellipse 55% 55% at 0% 100%, rgba(2, 8, 7, 0.85), transparent 70%),
    radial-gradient(ellipse 55% 55% at 100% 100%, rgba(2, 8, 7, 0.85), transparent 70%);
}

/* 层1 地形等高线 */
.bg-terrain {
  position: absolute;
  inset: -6%;
  opacity: 0.6;
  animation: terrainDrift 100s ease-in-out infinite alternate;
}
.bg-terrain svg { width: 100%; height: 100%; display: block; }
@keyframes terrainDrift {
  from { transform: scale(1); }
  to   { transform: scale(1.06) translate3d(-1.3%, -0.7%, 0); }
}

/* 层2 网格 / 点阵 / 数据流线 */
.bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(to right, rgba(255, 255, 255, 0.045) 1px, transparent 1px),
    linear-gradient(to bottom, rgba(255, 255, 255, 0.045) 1px, transparent 1px);
  background-size: 88px 88px;
  -webkit-mask-image: radial-gradient(ellipse 62% 62% at 50% 48%, #000 0%, rgba(0, 0, 0, 0.45) 58%, transparent 88%);
  mask-image: radial-gradient(ellipse 62% 62% at 50% 48%, #000 0%, rgba(0, 0, 0, 0.45) 58%, transparent 88%);
}

.bg-dots {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(rgba(255, 255, 255, 0.085) 1px, transparent 1px);
  background-size: 26px 26px;
  -webkit-mask-image: radial-gradient(ellipse 70% 70% at 50% 50%, transparent 22%, #000 78%);
  mask-image: radial-gradient(ellipse 70% 70% at 50% 50%, transparent 22%, #000 78%);
}

.bg-flow { position: absolute; inset: 0; pointer-events: none; }
.flow-h,
.flow-v {
  position: absolute;
  background: linear-gradient(90deg, transparent, rgba(120, 235, 205, 0.3), transparent);
}
.flow-h { height: 1px; left: 4%; right: 4%; }
.flow-v {
  width: 1px;
  top: 6%;
  bottom: 6%;
  background: linear-gradient(180deg, transparent, rgba(120, 235, 205, 0.24), transparent);
}
.fh1 { top: 21%; animation: flowA 9s ease-in-out infinite; }
.fh2 { top: 74%; animation: flowA 12s ease-in-out infinite reverse; }
.fh3 { top: 47%; left: 38%; right: 38%; opacity: 0.5; animation: flowA 7s ease-in-out infinite; }
.fv1 { left: 19%; animation: flowB 11s ease-in-out infinite; }
.fv2 { left: 82%; animation: flowB 14s ease-in-out infinite reverse; }
@keyframes flowA {
  0%, 100% { opacity: 0.25; }
  50%      { opacity: 0.85; }
}
@keyframes flowB {
  0%, 100% { opacity: 0.2; }
  50%      { opacity: 0.7; }
}

/* 层3 粒子 */
.bg-particles { position: absolute; inset: 0; }
.bg-particles canvas { width: 100%; height: 100%; display: block; }

/* 层4 动物线稿：单色细白线、仅外轮廓、均匀顺滑、暗纹级透明度 */
.bg-animals { position: absolute; inset: 0; pointer-events: none; }
.bg-animals svg { position: absolute; overflow: visible; }
.bg-animals path,
.bg-animals circle {
  fill: none;
  stroke: rgba(232, 248, 242, 0.85);
  stroke-width: 1.1;
  stroke-linecap: round;
  stroke-linejoin: round;
  vector-effect: non-scaling-stroke;
}
/* 尺寸差异：羚羊、水鸟偏大；猕猴、野猪偏小。透明度 8%–12% */
.an-antelope { width: 400px; left: 1%;  top: 10%;   opacity: 0.12; animation: floatB 30s ease-in-out infinite; }
.an-bird     { width: 320px; right: 2%; top: 11%;   opacity: 0.11; animation: floatA 25s ease-in-out infinite; }
.an-boar     { width: 260px; left: 2%;  bottom: 5%; opacity: 0.09; animation: floatA 29s ease-in-out infinite; }
.an-macaque  { width: 220px; right: 4%; bottom: 7%; opacity: 0.08; animation: floatB 34s ease-in-out infinite; }
@keyframes floatA {
  0%, 100% { transform: translate3d(0, 0, 0); }
  50%      { transform: translate3d(0, -9px, 0); }
}
@keyframes floatB {
  0%, 100% { transform: translate3d(0, 0, 0); }
  50%      { transform: translate3d(0, 11px, 0); }
}

/* 层5 检测框 */
.bg-boxes { position: absolute; inset: 0; pointer-events: none; }
.det-box {
  position: absolute;
  border: 1px dashed rgba(180, 240, 220, 0.26);
  border-radius: 2px;
  animation: boxPulse 8s ease-in-out infinite;
}
.det-box::before,
.det-box::after {
  content: '';
  position: absolute;
  width: 7px;
  height: 7px;
  border: 1px solid rgba(180, 240, 220, 0.45);
}
.det-box::before { left: -1px; top: -1px; border-right: 0; border-bottom: 0; }
.det-box::after  { right: -1px; bottom: -1px; border-left: 0; border-top: 0; }
.det-box span {
  position: absolute;
  left: -1px;
  top: -16px;
  font-size: 9px;
  letter-spacing: 0.06em;
  color: rgba(180, 240, 220, 0.45);
  white-space: nowrap;
  font-family: ui-monospace, Consolas, monospace;
}
@keyframes boxPulse {
  0%, 100% { opacity: 0.4; }
  50%      { opacity: 0.95; }
}
.d1 { width: 124px; height: 88px;  left: 26%; top: 66%; animation-delay: 0s; }
.d2 { width: 92px;  height: 124px; left: 78%; top: 22%; animation-delay: -1.8s; }
.d3 { width: 146px; height: 80px;  left: 82%; top: 70%; animation-delay: -3.3s; }
.d4 { width: 80px;  height: 80px;  left: 5%;  top: 44%; animation-delay: -4.6s; }
.d5 { width: 112px; height: 72px;  left: 63%; top: 8%;  animation-delay: -2.4s; }
.d6 { width: 70px;  height: 70px;  left: 37%; top: 22%; animation-delay: -5.7s; }

/* 层6 扫光 + 云雾 */
.bg-scan {
  position: absolute;
  left: -20%;
  right: -20%;
  height: 40vh;
  top: -40vh;
  background: linear-gradient(
    180deg,
    transparent 0%,
    rgba(39, 201, 176, 0.045) 40%,
    rgba(39, 201, 176, 0.09) 50%,
    rgba(39, 201, 176, 0.045) 60%,
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

.bg-mist { position: absolute; inset: 0; pointer-events: none; }
.mist {
  position: absolute;
  border-radius: 50%;
  filter: blur(70px);
  background: radial-gradient(circle, rgba(150, 228, 208, 0.14), transparent 70%);
}
.m1 { width: 640px; height: 360px; left: -8%; top: 52%;   animation: mistA 46s ease-in-out infinite; }
.m2 { width: 720px; height: 400px; right: -12%; top: 4%;  animation: mistB 54s ease-in-out infinite; }
.m3 { width: 580px; height: 320px; left: 34%; bottom: -12%; animation: mistA 40s ease-in-out infinite reverse; }
@keyframes mistA {
  0%, 100% { transform: translate3d(0, 0, 0) scale(1); }
  50%      { transform: translate3d(44px, -20px, 0) scale(1.07); }
}
@keyframes mistB {
  0%, 100% { transform: translate3d(0, 0, 0) scale(1); }
  50%      { transform: translate3d(-50px, 24px, 0) scale(1.05); }
}

/* ══ 玻璃拟态登录卡片（深色玻璃 + 白字） ═══════════════════════ */
.login-card {
  position: relative;
  z-index: 10;
  width: 380px;
  padding: 42px 38px;
  display: flex;
  flex-direction: column;
  border-radius: 18px;
  background: linear-gradient(158deg, rgba(255, 255, 255, 0.09) 0%, rgba(255, 255, 255, 0.045) 100%);
  -webkit-backdrop-filter: blur(30px) saturate(165%);
  backdrop-filter: blur(30px) saturate(165%);
  border: 1px solid rgba(255, 255, 255, 0.2);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.04),
    0 26px 76px rgba(0, 10, 8, 0.6),
    0 0 60px rgba(39, 201, 176, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.3),
    inset 0 -1px 0 rgba(39, 201, 176, 0.1);
}

.login-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.logo-icon { font-size: 26px; line-height: 1; }

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

.form-item input::placeholder { color: rgba(226, 245, 238, 0.38); }

.form-item input:focus {
  border-color: rgba(39, 201, 176, 0.75);
  background: rgba(255, 255, 255, 0.14);
  box-shadow: 0 0 0 3px rgba(39, 201, 176, 0.16), 0 0 18px rgba(39, 201, 176, 0.12);
}

.form-item input:disabled { opacity: 0.6; }

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
.btn-login:disabled { opacity: 0.55; cursor: not-allowed; }

.no-register {
  margin-top: 22px;
  font-size: 11.5px;
  color: rgba(226, 245, 238, 0.42);
  text-align: center;
}
</style>
