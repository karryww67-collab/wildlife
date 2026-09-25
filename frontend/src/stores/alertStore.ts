import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

/**
 * 实时通知。
 *
 * 数据来源只有一个：后端 WebSocket `/ws/recognition` 的推送
 * （见 backend RecognitionWebSocket.sendTaskProgress / sendReviewStatus）。
 * 两种消息形态分别映射为：
 *
 *   任务进度  {taskId, processedCount, totalCount, progress, status}
 *   复核状态  {type:'REVIEW', resultId, imageId, className, correctedClass,
 *             reviewStatus, reviewer, reviewTime}
 */
export interface Alert {
  id: number
  /** 任务进度消息为任务 ID；复核消息无任务概念，固定 0（表示不参与合并）。 */
  taskId: number
  /** 视觉色调依据：INFO / SUCCESS / ERROR。 */
  severity: string
  description: string
  createdAt: string
  /** 任务状态 PENDING / PROCESSING / COMPLETED / FAILED / CANCELED */
  rawStatus?: string
  /** 以下三个仅任务进度消息携带。 */
  progress?: number
  processedCount?: number
  totalCount?: number
}

export const useAlertStore = defineStore('alert', () => {
  const alerts = ref<Alert[]>([])
  const wsConnected = ref(false)
  const taskCompletedSignal = ref(0)

  function addAlert(alert: Alert) {
    // 同一任务只保留最新一条（进度推送逐张到达，覆盖而不是堆叠）
    const idx = alerts.value.findIndex(a => a.taskId === alert.taskId && alert.taskId !== 0)
    if (idx >= 0) {
      alerts.value.splice(idx, 1, alert)
    } else {
      alerts.value.unshift(alert)
    }
    // 保持最多 50 条
    if (alerts.value.length > 50) alerts.value.splice(50)
    // COMPLETED 状态时触发刷新信号，供页面自行监听后重新拉取数据
    if (alert.rawStatus === 'COMPLETED') {
      taskCompletedSignal.value++
    }
  }

  function clearAlerts() {
    alerts.value = []
  }

  const todayAlerts = computed(() => {
    const todayStr = new Date().toLocaleDateString('zh-CN')
    return alerts.value.filter(a => a.createdAt && a.createdAt.startsWith(todayStr))
  })

  return { alerts, todayAlerts, wsConnected, taskCompletedSignal, addAlert, clearAlerts }
})
