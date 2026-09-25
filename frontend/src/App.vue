<template>
  <div class="app-shell">
    <NavBar v-if="!isLoginPage" />
    <main class="app-main">
      <router-view />
    </main>
  </div>
  <AlertToast />
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { useAlertStore } from '@/stores/alertStore'
import { connectWebSocket } from '@/utils/websocket'
import AlertToast from '@/components/AlertToast.vue'
import NavBar from '@/components/NavBar.vue'

const route = useRoute()
const alertStore = useAlertStore()

/** 登录页不显示导航壳（它自带整屏布局）。 */
const isLoginPage = computed(() => route.path === '/login')

let ws: WebSocket | null = null

/** 同一毫秒可能到达多条推送，用自增序号保证 id 唯一。 */
let seq = 0
function nextId(): number {
  seq += 1
  return Date.now() * 1000 + (seq % 1000)
}

function now(): string {
  return new Date().toLocaleString('zh-CN')
}

/** 任务状态 -> 视觉色调（AlertToast 只认 INFO / SUCCESS / ERROR）。 */
function severityOf(status?: string): string {
  if (status === 'COMPLETED') return 'SUCCESS'
  if (status === 'FAILED') return 'ERROR'
  return 'INFO'
}

/**
 * 任务进度消息 -> 文案。
 * 形状：{taskId, processedCount, totalCount, progress, status}
 * 来源：RecognitionWebSocket.sendTaskProgress / sendTaskCompleted
 */
function describeProgress(data: any): string {
  const taskId = data.taskId
  const total = data.totalCount ?? 0
  const done = data.processedCount ?? 0
  switch (data.status) {
    case 'COMPLETED':
      return `任务 #${taskId} 识别完成，共 ${total} 张`
    case 'FAILED':
      return `任务 #${taskId} 识别失败，请到任务详情查看`
    case 'CANCELED':
      return `任务 #${taskId} 已取消`
    case 'PROCESSING':
      return `任务 #${taskId} 识别中 ${done}/${total}`
    default:
      return `任务 #${taskId} 已排队，共 ${total} 张`
  }
}

/**
 * 复核消息 -> 文案。
 * 形状：{type:'REVIEW', resultId, imageId, className, correctedClass, reviewStatus, reviewer, reviewTime}
 * 来源：RecognitionWebSocket.sendReviewStatus
 */
function describeReview(data: any): string {
  const label = data.correctedClass || data.className || '未知物种'
  switch (data.reviewStatus) {
    case 'CORRECTED':
      return `结果 #${data.resultId} 已修正为「${label}」`
    case 'REJECTED':
      return `结果 #${data.resultId} 已剔除（原识别「${data.className || '未知'}」）`
    default:
      return `结果 #${data.resultId} 已确认「${label}」`
  }
}

function handleMessage(data: any) {
  if (!data) return

  // 复核推送带 type=REVIEW，其余按任务进度处理
  if (data.type === 'REVIEW') {
    alertStore.addAlert({
      id: nextId(),
      taskId: 0,
      severity: 'INFO',
      description: describeReview(data),
      createdAt: now(),
      rawStatus: data.reviewStatus
    })
    return
  }

  // 没有 taskId 的消息不是任务进度，直接忽略，避免弹出无意义提示
  if (!data.taskId) return

  alertStore.addAlert({
    id: nextId(),
    taskId: data.taskId,
    severity: severityOf(data.status),
    description: describeProgress(data),
    createdAt: now(),
    rawStatus: data.status,
    progress: typeof data.progress === 'number' ? data.progress : undefined,
    processedCount: data.processedCount ?? 0,
    totalCount: data.totalCount ?? 0
  })
}

onMounted(() => {
  const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  // 后端唯一端点是 /ws/recognition（@ServerEndpoint），无需 token
  const wsUrl = `${wsProtocol}//${window.location.host}/ws/recognition`

  ws = connectWebSocket(
    wsUrl,
    handleMessage,
    () => { alertStore.wsConnected = true },
    () => { alertStore.wsConnected = false }
  )
})

onUnmounted(() => {
  if (ws) {
    ws.close()
    ws = null
  }
})
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body, #app {
  width: 100%;
  height: 100%;
  font-family: 'Microsoft YaHei', sans-serif;
}

/* 顶栏 + 内容区：内容区独立滚动，页面内统一用 min-height:100% */
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.app-main {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
</style>
