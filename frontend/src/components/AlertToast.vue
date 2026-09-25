<template>
  <!-- 全局提示条：右上角浮动，跟随 alertStore 里最新的若干条消息 -->
  <div class="toast-stack" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="item in visible" :key="item.id" class="toast" :class="toneOf(item)">
        <span class="toast-icon">{{ iconOf(item) }}</span>
        <div class="toast-body">
          <p class="toast-text">{{ item.description || '收到一条通知' }}</p>
          <span class="toast-meta">
            <span v-if="item.taskId">任务 #{{ item.taskId }}</span>
            <span v-if="item.totalCount">{{ item.processedCount ?? 0 }}/{{ item.totalCount }} 张</span>
            <span v-if="typeof item.progress === 'number'">{{ item.progress }}%</span>
            <span v-if="item.createdAt">{{ item.createdAt }}</span>
          </span>
        </div>
        <button class="toast-close" type="button" title="关闭" @click="dismiss(item.id)">×</button>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useAlertStore, type Alert } from '@/stores/alertStore'

/** 同屏最多显示条数，超出只保留最新的几条。 */
const MAX_VISIBLE = 3
/** 单条自动消失时间（毫秒）。 */
const AUTO_DISMISS_MS = 6000

const alertStore = useAlertStore()

/** 已手动关闭或已超时的 id，只增不减（store 自身也最多留 50 条）。 */
const dismissed = ref<Set<number>>(new Set())
const timers = new Map<number, ReturnType<typeof setTimeout>>()

const visible = computed(() =>
  alertStore.alerts.filter((item) => !dismissed.value.has(item.id)).slice(0, MAX_VISIBLE)
)

function dismiss(id: number) {
  const next = new Set(dismissed.value)
  next.add(id)
  dismissed.value = next
  const timer = timers.get(id)
  if (timer) {
    clearTimeout(timer)
    timers.delete(id)
  }
}

function schedule(alert: Alert) {
  if (timers.has(alert.id)) return
  timers.set(
    alert.id,
    setTimeout(() => dismiss(alert.id), AUTO_DISMISS_MS)
  )
}

// 只用 id 序列化做依赖，避免 alertStore.alerts 内部对象被替换时反复触发
watch(
  () => alertStore.alerts.map((item) => item.id).join(','),
  () => {
    alertStore.alerts.slice(0, MAX_VISIBLE).forEach(schedule)
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  timers.forEach((timer) => clearTimeout(timer))
  timers.clear()
})

/** 严重级别 -> 视觉色调。App.vue 只会给出 INFO / SUCCESS / ERROR 三种。 */
function toneOf(alert: Alert): string {
  const severity = String(alert.severity || '').toUpperCase()
  if (severity === 'ERROR' || severity === 'DANGER') return 'bad'
  if (severity === 'WARNING' || severity === 'WARN') return 'warn'
  if (severity === 'SUCCESS' || alert.rawStatus === 'COMPLETED') return 'ok'
  return 'info'
}

function iconOf(alert: Alert): string {
  switch (toneOf(alert)) {
    case 'bad':
      return '⛔'
    case 'warn':
      return '⚠'
    case 'ok':
      return '✓'
    default:
      return 'ℹ'
  }
}
</script>

<style scoped>
.toast-stack {
  position: fixed;
  top: 16px;
  right: 16px;
  z-index: 3000;
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 320px;
  pointer-events: none;
}

.toast {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 10px 12px;
  background: var(--bg-admin-card);
  border: 1px solid var(--border-primary);
  border-left-width: 3px;
  border-radius: 6px;
  box-shadow: var(--shadow-panel);
  pointer-events: auto;
}
.toast.info { border-left-color: var(--color-primary); }
.toast.ok { border-left-color: var(--color-success); }
.toast.warn { border-left-color: var(--color-warning); }
.toast.bad { border-left-color: var(--color-danger); }

.toast-icon {
  flex-shrink: 0;
  font-size: 14px;
  line-height: 1.4;
}
.toast.info .toast-icon { color: var(--color-primary); }
.toast.ok .toast-icon { color: var(--color-success); }
.toast.warn .toast-icon { color: var(--color-warning); }
.toast.bad .toast-icon { color: var(--color-danger); }

.toast-body {
  flex: 1;
  min-width: 0;
}
.toast-text {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--text-primary);
  word-break: break-word;
}
.toast-meta {
  display: flex;
  gap: 10px;
  margin-top: 3px;
  font-size: 11px;
  color: var(--text-muted);
}

.toast-close {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  padding: 0;
  background: transparent;
  border: none;
  border-radius: 3px;
  color: var(--text-muted);
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
}
.toast-close:hover {
  background: var(--bg-card-hover);
  color: var(--text-primary);
}

/* 进出场动画 */
.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateX(16px);
}
</style>
