<template>
  <div class="login-page">
    <!-- ── 背景层 1：地形等高线（遥感地形底的抽象表达，JS 生成） ── -->
    <div class="bg-terrain">
      <svg ref="terrainSvg" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice"></svg>
    </div>

    <!-- ── 背景层 2：数字网格 ── -->
    <div class="bg-grid"></div>

    <!-- ── 背景层 3：数据流粒子 ── -->
    <div class="bg-particles">
      <canvas ref="particlesCanvas"></canvas>
    </div>

    <!-- ── 背景层 4：野生动物线稿剪影 ── -->
    <div class="bg-animals" aria-hidden="true">
      <!-- 野猪 -->
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

      <!-- 猕猴 -->
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

      <!-- 鸟类 -->
      <svg class="an-bird" viewBox="0 0 220 130">
        <path d="M110 72 C96 52 72 54 52 78" />
        <path d="M110 72 C124 52 148 54 168 78" />
        <path d="M110 72 C108 64 104 60 100 58 C106 56 114 56 120 58 C116 60 112 64 110 72 Z" />
        <path d="M100 58 C96 52 92 50 88 50" />
      </svg>

      <!-- 羚羊 -->
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

    <!-- ── 背景层 5：目标检测框虚线 ── -->
    <div class="bg-boxes" aria-hidden="true">
      <div class="det-box d1"><span>野猪 0.91</span></div>
      <div class="det-box d2"><span>猕猴 0.87</span></div>
      <div class="det-box d3"><span>赤麂 0.83</span></div>
      <div class="det-box d4"><span>白鹇 0.76</span></div>
      <div class="det-box d5"><span>羚羊 0.80</span></div>
      <div class="det-box d6"><span>豹猫 0.72</span></div>
    </div>

    <!-- ── 背景层 6：微弱扫描光晕 ── -->
    <div class="bg-scan" aria-hidden="true"></div>

    <!-- ── 背景层 7：云雾 ── -->
    <div class="bg-mist" aria-hidden="true">
      <div class="mist m1"></div>
      <div class="mist m2"></div>
      <div class="mist m3"></div>
    </div>

    <!-- ── 背景层 8：卡片后柔光与暗角 ── -->
    <div class="bg-halo" aria-hidden="true"></div>
    <div class="bg-vignette" aria-hidden="true"></div>

    <!-- ── 玻璃拟态登录卡片（内容与原版一致） ── -->
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

/* ── 地形等高线：以若干"山丘"为中心生成闭合等值线 ─────────────── */
function buildTerrain() {
  const svg = terrainSvg.value
  if (!svg) return
  const NS = 'http://www.w3.org/2000/svg'
  const centers = [
    { x: 380, y: 300, r: 300, seed: 1.2 },
    { x: 1080, y: 640, r: 360, seed: 3.7 },
    { x: 760, y: 120, r: 220, seed: 5.4 },
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
  path.setAttribute('stroke', 'rgba(168,226,208,.17)')
  path.setAttribute('stroke-width', '1')
  svg.appendChild(path)
}

/* ── 数据流粒子：缓慢漂浮 + 近邻细连线 ────────────────────────── */
function startParticles() {
  const cv = particlesCanvas.value
  if (!cv) return
  const ctx = cv.getContext('2d')
  if (!ctx) return

  let W = 0
  let H = 0
  const DPR = Math.min(window.devicePixelRatio || 1, 2)
  const N = 46
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
      vx: (Math.random() - 0.5) * 0.14,
      vy: (Math.random() - 0.5) * 0.14,
      r: 0.7 + Math.random() * 1.1,
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
      ctx.fillStyle = 'rgba(170,238,214,.45)'
      ctx.fill()
    }

    for (let i = 0; i < pts.length; i++) {
      for (let j = i + 1; j < pts.length; j++) {
        const dx = pts[i].x - pts[j].x
        const dy = pts[i].y - pts[j].y
        const d2 = dx * dx + dy * dy
        if (d2 < 26000) {
          ctx.strokeStyle = 'rgba(150,226,200,' + ((1 - d2 / 26000) * 0.1).toFixed(3) + ')'
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
/* ══ 登录页：深森林青绿渐变 + 多层背景 + 玻璃拟态卡片 ══════════ */
.login-page {
  position: relative;
  width: 100%;
  height: 100vh;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(1100px 760px at 16% 10%, rgba(44, 132, 110, 0.36), transparent 62%),
    radial-gradient(900px 700px at 84% 82%, rgba(24, 98, 84, 0.38), transparent 64%),
    radial-gradient(1500px 980px at 50% 48%, rgba(12, 58, 49, 0.62), transparent 72%),
    linear-gradient(158deg, #061d18 0%, #0a2b24 44%, #04140f 100%);
}

/* 层 1：地形等高线 */
.bg-terrain {
  position: absolute;
  inset: -6%;
  opacity: 0.55;
  animation: terrainDrift 90s ease-in-out infinite alternate;
}
.bg-terrain svg { width: 100%; height: 100%; display: block; }
@keyframes terrainDrift {
  from { transform: scale(1) translate3d(0, 0, 0); }
  to   { transform: scale(1.07) translate3d(-1.4%, -0.9%, 0); }
}

/* 层 2：数字网格 */
.bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(to right, rgba(150, 255, 225, 0.075) 1px, transparent 1px),
    linear-gradient(to bottom, rgba(150, 255, 225, 0.075) 1px, transparent 1px);
  background-size: 72px 72px;
  -webkit-mask-image: radial-gradient(circle at 50% 46%, #000 0%, rgba(0, 0, 0, 0.5) 54%, transparent 82%);
  mask-image: radial-gradient(circle at 50% 46%, #000 0%, rgba(0, 0, 0, 0.5) 54%, transparent 82%);
}

/* 层 3：粒子画布 */
.bg-particles { position: absolute; inset: 0; }
.bg-particles canvas { width: 100%; height: 100%; display: block; }

/* 层 4：动物线稿 */
.bg-animals { position: absolute; inset: 0; pointer-events: none; }
.bg-animals svg { position: absolute; overflow: visible; }
.bg-animals path,
.bg-animals circle {
  fill: none;
  stroke: rgba(206, 240, 230, 0.5);
  stroke-width: 1.3;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.an-boar     { width: 430px; left: 2%;  bottom: 5%; opacity: 0.17; animation: floatA 26s ease-in-out infinite; }
.an-macaque  { width: 300px; right: 4%; bottom: 10%; opacity: 0.16; animation: floatB 31s ease-in-out infinite; }
.an-bird     { width: 240px; right: 10%; top: 10%;  opacity: 0.18; animation: floatA 23s ease-in-out infinite; }
.an-antelope { width: 400px; left: 2%;  top: 15%;   opacity: 0.16; animation: floatB 29s ease-in-out infinite; }
@keyframes floatA {
  0%, 100% { transform: translate3d(0, 0, 0); }
  50%      { transform: translate3d(0, -10px, 0); }
}
@keyframes floatB {
  0%, 100% { transform: translate3d(0, 0, 0); }
  50%      { transform: translate3d(0, 12px, 0); }
}

/* 层 5：检测框 */
.bg-boxes { position: absolute; inset: 0; pointer-events: none; }
.det-box {
  position: absolute;
  border: 1px dashed rgba(146, 226, 200, 0.32);
  border-radius: 2px;
  animation: boxPulse 7s ease-in-out infinite;
}
.det-box::before,
.det-box::after {
  content: '';
  position: absolute;
  width: 7px;
  height: 7px;
  border: 1px solid rgba(146, 226, 200, 0.55);
}
.det-box::before { left: -1px; top: -1px; border-right: 0; border-bottom: 0; }
.det-box::after  { right: -1px; bottom: -1px; border-left: 0; border-top: 0; }
.det-box span {
  position: absolute;
  left: -1px;
  top: -16px;
  font-size: 9px;
  letter-spacing: 0.06em;
  color: rgba(146, 226, 200, 0.55);
  white-space: nowrap;
  font-family: ui-monospace, Consolas, monospace;
}
@keyframes boxPulse {
  0%, 100% { opacity: 0.45; }
  50%      { opacity: 0.95; }
}
.d1 { width: 128px; height: 92px;  left: 24%; top: 63%; animation-delay: 0s; }
.d2 { width: 96px;  height: 130px; left: 76%; top: 19%; animation-delay: -1.6s; }
.d3 { width: 150px; height: 84px;  left: 80%; top: 67%; animation-delay: -3.1s; }
.d4 { width: 84px;  height: 84px;  left: 6%;  top: 41%; animation-delay: -4.4s; }
.d5 { width: 118px; height: 76px;  left: 61%; top: 7%;  animation-delay: -2.2s; }
.d6 { width: 74px;  height: 74px;  left: 35%; top: 19%; animation-delay: -5.5s; }

/* 层 6：扫描光晕 */
.bg-scan {
  position: absolute;
  left: -20%;
  right: -20%;
  height: 34vh;
  top: -34vh;
  background: linear-gradient(
    180deg,
    transparent 0%,
    rgba(122, 226, 198, 0.05) 42%,
    rgba(122, 226, 198, 0.1) 50%,
    rgba(122, 226, 198, 0.05) 58%,
    transparent 100%
  );
  animation: scanMove 16s linear infinite;
  pointer-events: none;
  mix-blend-mode: screen;
}
@keyframes scanMove {
  from { transform: translateY(0); }
  to   { transform: translateY(150vh); }
}

/* 层 7：云雾 */
.bg-mist { position: absolute; inset: 0; pointer-events: none; }
.mist {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  background: radial-gradient(circle, rgba(150, 222, 204, 0.16), transparent 70%);
}
.m1 { width: 620px; height: 340px; left: -8%; top: 48%;  animation: mistA 44s ease-in-out infinite; }
.m2 { width: 700px; height: 380px; right: -12%; top: 6%; animation: mistB 52s ease-in-out infinite; }
.m3 { width: 560px; height: 300px; left: 32%; bottom: -10%; animation: mistA 38s ease-in-out infinite reverse; }
@keyframes mistA {
  0%, 100% { transform: translate3d(0, 0, 0) scale(1); }
  50%      { transform: translate3d(46px, -22px, 0) scale(1.08); }
}
@keyframes mistB {
  0%, 100% { transform: translate3d(0, 0, 0) scale(1); }
  50%      { transform: translate3d(-52px, 26px, 0) scale(1.05); }
}

/* 层 8：卡片柔光 + 暗角 */
.bg-halo {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 780px;
  height: 680px;
  transform: translate(-50%, -50%);
  background: radial-gradient(circle, rgba(4, 24, 19, 0.72) 0%, rgba(4, 24, 19, 0.4) 46%, transparent 72%);
  pointer-events: none;
}
.bg-vignette {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: radial-gradient(ellipse at 50% 50%, transparent 40%, rgba(2, 12, 9, 0.6) 100%);
}

/* ══ 玻璃拟态卡片 ══════════════════════════════════════════════ */
.login-card {
  position: relative;
  z-index: 10;
  width: 380px;
  padding: 40px 36px;
  display: flex;
  flex-direction: column;
  align-items: center;
  border-radius: 18px;
  background: linear-gradient(158deg, rgba(255, 255, 255, 0.66) 0%, rgba(228, 249, 241, 0.46) 100%);
  -webkit-backdrop-filter: blur(26px) saturate(180%);
  backdrop-filter: blur(26px) saturate(180%);
  border: 1px solid rgba(255, 255, 255, 0.6);
  box-shadow:
    0 24px 70px rgba(1, 14, 10, 0.55),
    0 2px 10px rgba(1, 14, 10, 0.26),
    inset 0 1px 0 rgba(255, 255, 255, 0.95),
    inset 0 -1px 0 rgba(180, 235, 214, 0.22);
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
  color: #08402b;
  letter-spacing: 0.3px;
}

.login-subtitle {
  font-size: 14px;
  color: #2b6650;
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
  color: #2f6b54;
}

.form-item input {
  padding: 10px 14px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(120, 192, 163, 0.85);
  border-radius: 6px;
  color: #14382a;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s, background 0.2s;
}

.form-item input::placeholder { color: #86b09f; }

.form-item input:focus {
  border-color: #2eb872;
  background: rgba(255, 255, 255, 0.95);
  box-shadow: 0 0 0 3px rgba(46, 184, 114, 0.18);
}

.form-item input:disabled {
  opacity: 0.6;
}

.error-msg {
  padding: 8px 12px;
  background: rgba(253, 236, 236, 0.9);
  border: 1px solid #e57373;
  border-radius: 4px;
  color: #c62828;
  font-size: 13px;
}

.btn-login {
  padding: 11px;
  background: linear-gradient(180deg, #31c07a 0%, #26a866 100%);
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  margin-top: 4px;
  box-shadow: 0 6px 18px rgba(38, 168, 102, 0.32);
  transition: background 0.2s, box-shadow 0.2s, transform 0.12s;
}

.btn-login:hover:not(:disabled) {
  box-shadow: 0 8px 22px rgba(38, 168, 102, 0.44);
  transform: translateY(-1px);
}
.btn-login:disabled { opacity: 0.5; cursor: not-allowed; }

.no-register {
  margin-top: 20px;
  font-size: 12px;
  color: #2c6349;
  text-align: center;
}
</style>
