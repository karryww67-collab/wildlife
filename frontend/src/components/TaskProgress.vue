<template>
  <section class="task-progress" :class="[`s-${statusKey}`, { compact }]">
    <header class="head">
      <div class="head-left">
        <span class="task-id">任务 #{{ taskId ?? '—' }}</span>
        <span class="status-badge" :class="statusKey">{{ statusLabel }}</span>
      </div>
      <div class="head-right">
        <span class="percent">{{ percentText }}</span>
      </div>
    </header>

    <div class="bar-row">
      <div class="bar">
        <div class="bar-fill" :class="statusKey" :style="{ width: percent + '%' }"></div>
      </div>
    </div>

    <div class="stats">
      <div class="stat">
        <span class="stat-value">{{ total }}</span>
        <span class="stat-label">图像总数</span>
      </div>
      <div class="stat">
        <span class="stat-value">{{ processed }}</span>
        <span class="stat-label">已处理</span>
      </div>
      <div class="stat">
        <span class="stat-value ok">{{ success }}</span>
        <span class="stat-label">成功</span>
      </div>
      <div class="stat">
        <span class="stat-value bad">{{ failed }}</span>
        <span class="stat-label">失败</span>
      </div>
    </div>

    <div v-if="!compact" class="meta">
      <template v-if="status === 'PROCESSING'">
        <span class="meta-item">剩余 {{ remaining }} 张</span>
        <span v-if="rate > 0" class="meta-item">{{ rate.toFixed(1) }} 张/秒</span>
        <span v-if="etaText" class="meta-item">预计剩余 {{ etaText }}</span>
      </template>
      <template v-else-if="status === 'COMPLETED'">
        <span class="meta-item">识别完成</span>
        <span v-if="finishedAt" class="meta-item">{{ finishedAt }}</span>
      </template>
      <template v-else-if="status === 'PENDING'">
        <span class="meta-item">等待 AI 引擎领取任务...</span>
      </template>
      <template v-else-if="status === 'FAILED'">
        <span class="meta-item bad">{{ errorMessage || '任务处理失败' }}</span>
      </template>
      <template v-else-if="status === 'CANCELED'">
        <span class="meta-item">任务已取消</span>
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { TASK_STATUS_LABEL, type TaskStatus } from '@/api/index'

const props = withDefaults(
  defineProps<{
    taskId?: number
    totalCount?: number
    processedCount?: number
    successCount?: number
    failedCount?: number
    progress?: number
    status?: TaskStatus
    errorMessage?: string
    finishedAt?: string
    /** 是否自行订阅 /ws/recognition 接收该任务的实时进度。 */
    autoSubscribe?: boolean
    /** 进度推送地址。 */
    wsPath?: string
    /** 紧凑模式：只保留进度条与关键数字。 */
    compact?: boolean
  }>(),
  {
    taskId: 0,
    totalCount: 0,
    processedCount: 0,
    successCount: 0,
    failedCount: 0,
    progress: 0,
    status: 'PENDING',
    errorMessage: '',
    finishedAt: '',
    autoSubscribe: true,
    wsPath: '/ws/recognition',
    compact: false
  }
)

const emit = defineEmits<{
  (
    e: 'update',
    payload: {
      taskId: number
      totalCount: number
      processedCount: number
      successCount: number
      failedCount: number
      progress: number
      status: TaskStatus
    }
  ): void
  (e: 'completed', taskId: number): void
}>()

// 本地状态：默认跟随 props，收到 WebSocket 推送后由推送值覆盖
const total = ref(props.totalCount)
const processed = ref(props.processedCount)
const success = ref(props.successCount)
const failed = ref(props.failedCount)
const serverProgress = ref(props.progress)
const status = ref<TaskStatus>(props.status)

let ws: WebSocket | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let tickTimer: ReturnType<typeof setInterval> | null = null
let closedByUnmount = false

// 速率统计
const firstProcessedAt = ref(0)
const lastProcessed = ref(0)
const elapsedSec = ref(0)

function syncFromProps() {
  total.value = props.totalCount
  processed.value = props.processedCount
  success.value = props.successCount
  failed.value = props.failedCount
  serverProgress.value = props.progress
  status.value = props.status
}

watch(
  () => [
    props.totalCount,
    props.processedCount,
    props.successCount,
    props.failedCount,
    props.progress,
    props.status
  ],
  syncFromProps
)

/** 进度百分比：优先用后端算好的值，缺失时按张数自算。 */
const percent = computed(() => {
  if (serverProgress.value > 0) return Math.min(100, Math.round(serverProgress.value * 100) / 100)
  if (total.value > 0) return Math.min(100, Math.round((processed.value * 10000) / total.value) / 100)
  return 0
})

const percentText = computed(() => `${percent.value.toFixed(2)}%`)
const remaining = computed(() => Math.max(0, total.value - processed.value))
const statusKey = computed(() => status.value.toLowerCase())

const statusLabel = computed(() => TASK_STATUS_LABEL[status.value] ?? status.value)

/** 处理速率（张/秒），只在进行中且已收到过进度时有效。 */
const rate = computed(() => {
  if (status.value !== 'PROCESSING') return 0
  if (firstProcessedAt.value <= 0 || elapsedSec.value <= 0) return 0
  const done = processed.value - lastProcessed.value
  if (done <= 0) return 0
  return done / elapsedSec.value
})

const etaText = computed(() => {
  if (rate.value <= 0 || remaining.value <= 0) return ''
  const seconds = Math.ceil(remaining.value / rate.value)
  if (!Number.isFinite(seconds)) return ''
  if (seconds < 60) return `${seconds} 秒`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
  return `${Math.floor(seconds / 3600)} 小时 ${Math.floor((seconds % 3600) / 60)} 分`
})

function startTicker() {
  if (tickTimer) return
  tickTimer = setInterval(() => {
    if (firstProcessedAt.value > 0) {
      elapsedSec.value = Math.max(1, Math.round((Date.now() - firstProcessedAt.value) / 1000))
    }
  }, 1000)
}

function stopTicker() {
  if (tickTimer) {
    clearInterval(tickTimer)
    tickTimer = null
  }
}

function applyMessage(msg: any) {
  // 只处理本任务的消息；审核类推送（type=REVIEW）不带 processedCount，跳过
  if (props.taskId && Number(msg?.taskId) !== Number(props.taskId)) return
  if (msg?.processedCount === undefined && msg?.progress === undefined) return

  const nextProcessed = Number(msg.processedCount ?? processed.value)
  if (nextProcessed > 0 && firstProcessedAt.value === 0) {
    firstProcessedAt.value = Date.now()
    startTicker()
  }
  if (msg.successCount !== undefined) success.value = Number(msg.successCount)
  if (msg.failedCount !== undefined) failed.value = Number(msg.failedCount)
  if (msg.totalCount !== undefined) total.value = Number(msg.totalCount)
  if (msg.progress !== undefined) serverProgress.value = Number(msg.progress)

  lastProcessed.value = processed.value
  processed.value = nextProcessed

  const nextStatus = (msg.status ?? status.value) as TaskStatus
  status.value = nextStatus

  emit('update', {
    taskId: Number(msg.taskId ?? props.taskId ?? 0),
    totalCount: total.value,
    processedCount: processed.value,
    successCount: success.value,
    failedCount: failed.value,
    progress: percent.value,
    status: status.value
  })

  if (nextStatus === 'COMPLETED' || nextStatus === 'FAILED' || nextStatus === 'CANCELED') {
    stopTicker()
    if (nextStatus === 'COMPLETED') emit('completed', Number(msg.taskId ?? props.taskId ?? 0))
  }
}

function wsUrl(): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}${props.wsPath}`
}

function connect() {
  if (closedByUnmount) return
  try {
    ws = new WebSocket(wsUrl())
  } catch {
    scheduleReconnect()
    return
  }
  ws.onmessage = (event: MessageEvent) => {
    try {
      applyMessage(JSON.parse(event.data))
    } catch {
      /* 忽略无法解析的推送 */
    }
  }
  ws.onclose = () => {
    ws = null
    scheduleReconnect()
  }
  ws.onerror = () => {
    /* onclose 会跟着触发，重连逻辑统一放那里 */
  }
}

function scheduleReconnect() {
  if (closedByUnmount) return
  if (reconnectTimer) return
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connect()
  }, 5000)
}

onMounted(() => {
  if (props.autoSubscribe) connect()
})

onBeforeUnmount(() => {
  closedByUnmount = true
  stopTicker()
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (ws) {
    ws.onclose = null
    ws.close()
    ws = null
  }
})
</script>

<style scoped>
.task-progress {
  background: var(--bg-admin-card);
  border: 1px solid var(--border-primary);
  border-radius: 10px;
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: border-color 0.3s;
}

.task-progress.s-processing { border-color: var(--border-accent); }
.task-progress.s-completed { border-color: var(--border-accent); }
.task-progress.s-failed { border-color: var(--border-danger); }

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.head-left { display: flex; align-items: center; gap: 10px; }
.task-id { font-size: 14px; font-weight: 600; color: var(--text-primary); }

.percent {
  font-size: 20px;
  font-weight: 700;
  color: var(--color-primary);
  font-variant-numeric: tabular-nums;
}

.status-badge {
  font-size: 11px;
  padding: 2px 9px;
  border-radius: 10px;
  font-weight: 500;
}
.status-badge.pending { background: var(--bg-badge-muted); color: var(--text-muted); }
.status-badge.processing { background: var(--bg-card-active); color: var(--color-primary); }
.status-badge.completed { background: var(--bg-badge-success); color: var(--color-success); }
.status-badge.failed { background: var(--bg-badge-danger); color: var(--text-danger); }
.status-badge.canceled { background: var(--bg-badge-muted); color: var(--text-dim); }

.bar-row { display: flex; align-items: center; }

.bar {
  width: 100%;
  height: 8px;
  background: var(--bg-admin-input);
  border-radius: 4px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  width: 0;
  border-radius: 4px;
  background: var(--color-primary);
  transition: width 0.35s ease;
}
.bar-fill.processing {
  background: linear-gradient(90deg, var(--color-primary) 0%, var(--color-purple) 100%);
}
.bar-fill.completed { background: var(--color-success); }
.bar-fill.failed { background: var(--color-danger); }
.bar-fill.canceled { background: var(--color-canceled); }

.stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}

.stat { display: flex; flex-direction: column; gap: 2px; }
.stat-value {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.stat-value.ok { color: var(--color-success); }
.stat-value.bad { color: var(--text-danger); }
.stat-label { font-size: 11px; color: var(--text-dim); }

.meta {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--text-muted);
}
.meta-item.bad { color: var(--text-danger); }

.compact { padding: 12px 14px; gap: 8px; }
.compact .stats { grid-template-columns: repeat(4, auto); justify-content: start; gap: 14px; }
.compact .stat-value { font-size: 13px; }
.compact .percent { font-size: 15px; }
</style>
