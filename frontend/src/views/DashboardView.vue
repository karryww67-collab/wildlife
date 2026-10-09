<template>
  <div class="page">
    <!-- 页头 -->
    <header class="page-head">
      <div class="head-left">
        <h1 class="page-title">监控总览</h1>
        <p class="page-sub">
          野生动物图像批量识别的整体运行状态：任务进度、检出构成与近期趋势
        </p>
      </div>
      <div class="head-actions">
        <button class="btn-ghost" :disabled="loading" @click="load">刷新</button>
        <button class="btn-primary" @click="router.push('/tasks')">进入识别任务</button>
      </div>
    </header>

    <!-- 快捷入口 -->
    <nav class="quick-nav">
      <button
        v-for="link in quickLinks"
        :key="link.path"
        class="quick-link"
        type="button"
        @click="router.push(link.path)"
      >
        <span class="quick-icon">{{ link.icon }}</span>
        <span class="quick-label">{{ link.label }}</span>
      </button>
    </nav>

    <!-- 概览卡 -->
    <section class="stat-row">
      <div v-for="item in overviewCards" :key="item.label" class="stat-card">
        <span class="stat-value" :class="item.tone">{{ item.value }}</span>
        <span class="stat-label">{{ item.label }}</span>
      </div>
    </section>

    <!-- 进度 + 运行中任务 -->
    <section class="grid-2">
      <div class="card">
        <div class="card-head">
          <span>整体识别进度</span>
          <span class="head-meta">{{ statusMeta }}</span>
        </div>

        <div v-if="loading" class="state small">
          <span class="spinner"></span>
          <span>统计加载中...</span>
        </div>
        <div v-else-if="!taskStatus" class="state small">
          <span class="state-icon">📈</span>
          <span>暂无统计数据</span>
        </div>
        <div v-else class="progress-block">
          <div class="progress-head">
            <span class="progress-percent">{{ taskStatus.progress.toFixed(1) }}%</span>
            <span class="progress-fraction">
              已处理 {{ taskStatus.processedImages }} / {{ taskStatus.totalImages }} 张
            </span>
          </div>
          <div class="bar big">
            <div
              class="bar-fill"
              :class="taskStatus.progress >= 100 ? 'st-completed' : 'st-processing'"
              :style="{ width: Math.max(0, Math.min(100, taskStatus.progress)) + '%' }"
            ></div>
          </div>
          <div class="chip-row">
            <span
              v-for="item in statusChips"
              :key="item.name"
              class="badge"
              :class="statusClass(item.name)"
            >
              {{ labelOf(item.name) }} {{ item.value }}
            </span>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <span>运行中的任务</span>
          <span class="head-meta">共 {{ activeTasks.length }} 个</span>
        </div>

        <div v-if="loading" class="state small">
          <span class="spinner"></span>
          <span>任务加载中...</span>
        </div>
        <div v-else-if="!activeTasks.length" class="state small">
          <span class="state-icon">✅</span>
          <span>当前没有排队或识别中的任务</span>
        </div>
        <div v-else class="active-list">
          <TaskProgress
            v-for="task in activeTasks"
            :key="task.id"
            class="active-item"
            compact
            :task-id="task.id"
            :total-count="task.totalCount"
            :processed-count="task.processedCount"
            :success-count="task.successCount"
            :failed-count="task.failedCount"
            :progress="task.progress"
            :status="task.status"
          />
        </div>
      </div>
    </section>

    <!-- 类别分布 + 趋势 -->
    <section class="grid-2">
      <div class="card">
        <div class="card-head">
          <span>检出类别构成</span>
          <span class="head-meta">共 {{ classTotal }} 个检出目标</span>
        </div>
        <div class="chart-wrap">
          <StatisticsChart
            v-if="classChartData.length"
            type="donut"
            :data="classChartData"
            :height="272"
            unit="次"
            color-by="palette"
            :show-legend="true"
            empty-text="暂无检出记录"
          />
          <div v-else class="state small">
            <span class="state-icon">🐾</span>
            <span>暂无检出记录，先创建一次识别任务</span>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <span>近 7 日识别趋势</span>
          <span class="head-meta">按天统计检出的目标数</span>
        </div>
        <div class="chart-wrap">
          <StatisticsChart
            v-if="trendChartData.length"
            type="area"
            :data="trendChartData"
            :height="272"
            unit="次"
            smooth
            :show-value-summary="true"
            empty-text="暂无趋势数据"
          />
          <div v-else class="state small">
            <span class="state-icon">📉</span>
            <span>暂无趋势数据</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 最近任务 -->
    <section class="card">
      <div class="card-head">
        <span>最近识别任务</span>
        <span class="head-meta">共 {{ taskTotal }} 条</span>
      </div>

      <div v-if="loading && !tasks.length" class="state">
        <span class="spinner"></span>
        <span>任务加载中...</span>
      </div>
      <div v-else-if="!tasks.length" class="state">
        <span class="state-icon">🗂</span>
        <span>还没有识别任务，去「识别任务」页上传图像并创建</span>
      </div>
      <table v-else class="table">
        <thead>
          <tr>
            <th class="col-id">任务</th>
            <th>名称</th>
            <th class="col-num">图像</th>
            <th class="col-num">成功</th>
            <th class="col-num">失败</th>
            <th class="col-progress">进度</th>
            <th class="col-status">状态</th>
            <th class="col-time">创建时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in recentTasks" :key="task.id">
            <td><span class="mono">#{{ task.id }}</span></td>
            <td>
              <a class="link" @click="router.push(`/tasks/${task.id}`)">
                {{ task.taskName || `任务 ${task.id}` }}
              </a>
            </td>
            <td class="col-num">{{ task.totalCount }}</td>
            <td class="col-num num ok">{{ task.successCount }}</td>
            <td class="col-num" :class="task.failedCount ? 'num bad' : 'num'">{{ task.failedCount }}</td>
            <td class="col-progress">
              <div class="progress-cell">
                <div class="bar">
                  <div
                    class="bar-fill"
                    :class="statusClass(task.status)"
                    :style="{ width: Math.max(0, Math.min(100, task.progress)) + '%' }"
                  ></div>
                </div>
                <span class="progress-text">{{ task.progress.toFixed(0) }}%</span>
              </div>
            </td>
            <td class="col-status">
              <span class="badge" :class="statusClass(task.status)">{{ labelOf(task.status) }}</span>
            </td>
            <td class="col-time"><span class="time">{{ formatTime(task.createTime) }}</span></td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- 启用模型 -->
    <section class="card model-card">
      <div class="card-head">
        <span>当前启用的模型</span>
        <button class="btn-mini" type="button" @click="router.push('/models')">模型管理</button>
      </div>
      <div v-if="activeModel" class="model-body">
        <div class="model-item">
          <span class="model-label">名称</span>
          <span class="model-value">{{ activeModel.modelName }}</span>
        </div>
        <div class="model-item">
          <span class="model-label">版本</span>
          <span class="model-value mono">{{ activeModel.version || '—' }}</span>
        </div>
        <div class="model-item">
          <span class="model-label">权重路径</span>
          <span class="model-value mono">{{ activeModel.modelPath }}</span>
        </div>
        <div class="model-item">
          <span class="model-label">状态</span>
          <span class="badge" :class="activeModel.status === 'ENABLED' ? 'st-completed' : 'st-canceled'">
            {{ activeModel.status === 'ENABLED' ? '启用中' : '已停用' }}
          </span>
        </div>
      </div>
      <div v-else class="state small">
        <span class="state-icon">🧠</span>
        <span>没有启用中的模型，识别任务会因找不到权重而失败</span>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import StatisticsChart from '@/components/StatisticsChart.vue'
import TaskProgress from '@/components/TaskProgress.vue'
import {
  getActiveModel,
  getClassStatistics,
  getOverview,
  getTaskStatusStatistics,
  getTrendStatistics,
  listTasks,
  verifyToken,
  TASK_STATUS_LABEL,
  type ClassStat,
  type ModelVersion,
  type NameValue,
  type RecognitionTask,
  type StatisticsOverview,
  type TaskStatus,
  type TaskStatusStatistics
} from '@/api/index'

const router = useRouter()

const loading = ref(false)
const overview = ref<StatisticsOverview | null>(null)
const taskStatus = ref<TaskStatusStatistics | null>(null)
const classStats = ref<ClassStat[]>([])
const trendList = ref<Array<{ time: string; value: number }>>([])
const tasks = ref<RecognitionTask[]>([])
const taskTotal = ref(0)
const activeModel = ref<ModelVersion | null>(null)
const role = ref<'ADMIN' | 'USER'>('USER')

/** 概览卡：字段与后端 /api/statistics/overview 一一对应。 */
const overviewCards = computed(() => {
  const data = overview.value
  return [
    { label: '图像总数', value: data ? data.imageCount : '—', tone: '' },
    { label: '识别任务', value: data ? data.taskCount : '—', tone: 'info' },
    { label: '检出目标', value: data ? data.resultCount : '—', tone: '' },
    { label: '涉及物种', value: data ? data.speciesCount : '—', tone: '' },
    { label: '待复核', value: data ? data.pendingReviewCount : '—', tone: 'warn' },
    {
      label: '平均置信度',
      value: data ? data.avgConfidence.toFixed(3) : '—',
      tone: 'ok'
    }
  ]
})

const statusMeta = computed(() => {
  const data = taskStatus.value
  return data ? `任务总数 ${data.total}` : '—'
})

const statusChips = computed<NameValue[]>(() => taskStatus.value?.list ?? [])

/** 排队中 + 识别中的任务，最多展示 3 条，避免总览被刷满。 */
const activeTasks = computed(() =>
  tasks.value
    .filter((task) => task.status === 'PENDING' || task.status === 'PROCESSING')
    .slice(0, 3)
)

/** 最近 8 条任务，按 id 倒序（后端按创建顺序返回）。 */
const recentTasks = computed(() =>
  [...tasks.value].sort((a, b) => b.id - a.id).slice(0, 8)
)

const classTotal = computed(() =>
  classStats.value.reduce((sum, item) => sum + item.value, 0)
)

const classChartData = computed(() =>
  classStats.value.map((item) => ({ name: item.name, value: item.value }))
)

const trendChartData = computed(() =>
  trendList.value.map((point) => ({ name: point.time, value: point.value }))
)

/** 快捷入口；仅管理员可见的页面放在 adminOnly 里，按角色过滤。 */
const quickLinks = computed(() => {
  const links = [
    { path: '/tasks', label: '识别任务', icon: '🗂', adminOnly: false },
    { path: '/results', label: '识别结果', icon: '🐾', adminOnly: false },
    { path: '/reviews', label: '人工复核', icon: '🔍', adminOnly: false },
    { path: '/models', label: '模型管理', icon: '🧠', adminOnly: true },
    { path: '/statistics', label: '统计分析', icon: '📊', adminOnly: true },
    { path: '/users', label: '用户管理', icon: '👥', adminOnly: true }
  ]
  return links.filter((link) => !link.adminOnly || role.value === 'ADMIN')
})

function labelOf(status: string): string {
  return TASK_STATUS_LABEL[status as TaskStatus] ?? status
}

function statusClass(status: string): string {
  return 'st-' + String(status || '').toLowerCase()
}

/** 后端时间形如 2026-09-22T12:27:00，这里统一成 2026-09-22 12:27。 */
function formatTime(value?: string | null): string {
  if (!value) return '—'
  const text = String(value).replace('T', ' ')
  return text.length >= 16 ? text.slice(0, 16) : text
}

async function load() {
  loading.value = true
  // 单个接口失败不应让整页空白，因此用 allSettled 逐项落库
  const [overviewRes, statusRes, classRes, trendRes, taskRes, modelRes] = await Promise.allSettled([
    getOverview('all'),
    getTaskStatusStatistics(),
    getClassStatistics({ range: 'all', top: 8 }),
    getTrendStatistics({ granularity: 'day', range: '7d' }),
    listTasks(),
    getActiveModel()
  ])

  overview.value = overviewRes.status === 'fulfilled' ? overviewRes.value.data : null
  taskStatus.value = statusRes.status === 'fulfilled' ? statusRes.value.data : null
  classStats.value = classRes.status === 'fulfilled' ? classRes.value.data?.list ?? [] : []
  trendList.value = trendRes.status === 'fulfilled' ? trendRes.value.data?.list ?? [] : []
  const taskPage = taskRes.status === 'fulfilled' ? taskRes.value.data : null
  tasks.value = taskPage?.list ?? []
  taskTotal.value = taskPage?.total ?? 0
  const modelData = modelRes.status === 'fulfilled' ? modelRes.value.data : null
  // 后端在没有启用模型时返回 {model:{},message:"..."}，不是 ModelVersion —— 按 modelName 判别
  activeModel.value = modelData && 'modelName' in modelData ? modelData : null

  loading.value = false
}

onMounted(async () => {
  // 角色只决定快捷入口显示哪些，取不到就按普通用户处理
  try {
    const session = await verifyToken()
    role.value = String(session.data.role).toUpperCase() === 'ADMIN' ? 'ADMIN' : 'USER'
  } catch {
    role.value = 'USER'
  }
  load()
})
</script>

<style scoped>
.page {
  min-height: 100%;
  padding: 20px 24px 32px;
  background: var(--bg-admin-page);
  color: var(--text-primary);
}

/* ── 页头 ── */
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}
.page-title {
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.5px;
}
.page-sub {
  margin-top: 5px;
  font-size: 12px;
  color: var(--text-muted);
}
.head-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

/* ── 快捷入口 ── */
.quick-nav {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}
.quick-link {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  background: var(--bg-admin-card);
  border: 1px solid var(--border-primary);
  border-radius: 6px;
  color: var(--text-secondary);
  font-family: inherit;
  font-size: 12.5px;
  cursor: pointer;
  transition: all 0.2s;
}
.quick-link:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
  background: var(--bg-card-hover);
}
.quick-icon {
  font-size: 13px;
}

/* ── 概览卡 ── */
.stat-row {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}
.stat-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 13px 16px;
  background: var(--bg-admin-card);
  border: 1px solid var(--border-primary);
  border-radius: 8px;
}
.stat-value {
  font-size: 22px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--text-primary);
}
.stat-value.ok { color: var(--color-success); }
.stat-value.bad { color: var(--color-danger); }
.stat-value.warn { color: var(--color-warning); }
.stat-value.info { color: var(--color-primary); }
.stat-label {
  font-size: 11.5px;
  color: var(--text-muted);
}

/* ── 卡片 ── */
.card {
  background: var(--bg-admin-card);
  border: 1px solid var(--border-primary);
  border-radius: 8px;
  overflow: hidden;
}
.card + .card { margin-top: 14px; }
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 16px;
  font-size: 13px;
  font-weight: 600;
  border-bottom: 1px solid var(--border-primary);
}
.head-meta {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--text-muted);
}

/* ── 两列布局 ── */
.grid-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}
.grid-2 + .grid-2,
.grid-2 + .card {
  margin-top: 14px;
}
/* 网格内左右两张卡是兄弟节点，不能吃到 .card + .card 的间距 */
.grid-2 > .card + .card {
  margin-top: 0;
}

/* ── 整体进度 ── */
.progress-block {
  padding: 16px;
}
.progress-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}
.progress-percent {
  font-size: 26px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--color-primary);
}
.progress-fraction {
  font-size: 12px;
  color: var(--text-muted);
}
.bar.big {
  height: 10px;
  border-radius: 5px;
}
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 14px;
}

/* ── 运行中任务 ── */
.active-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
}
.active-item + .active-item {
  border-top: 1px solid var(--border-divider);
  padding-top: 10px;
}

/* ── 图表 ── */
.chart-wrap {
  padding: 12px 16px 16px;
}

/* ── 表格 ── */
.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}
.table th {
  padding: 9px 12px;
  text-align: left;
  font-weight: 500;
  color: var(--text-muted);
  background: var(--bg-admin-input);
  border-bottom: 1px solid var(--border-admin-table);
  white-space: nowrap;
}
.table td {
  padding: 10px 12px;
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border-admin-table);
  vertical-align: middle;
}
.table tbody tr:hover { background: var(--bg-card-hover); }
.col-id { width: 72px; }
.col-status { width: 92px; }
.col-progress { width: 150px; }
.col-num, .num { width: 70px; text-align: right; font-variant-numeric: tabular-nums; }
.col-time { width: 150px; }

.num.ok { color: var(--color-success); }
.num.bad { color: var(--color-danger); }
.time { color: var(--text-muted); font-size: 12px; }
.mono { font-family: Consolas, Monaco, monospace; color: var(--text-muted); }
.link { color: var(--color-primary); cursor: pointer; text-decoration: none; }
.link:hover { text-decoration: underline; }

/* ── 状态徽标 ── */
.badge {
  display: inline-block;
  padding: 2px 9px;
  border-radius: 10px;
  font-size: 11.5px;
  border: 1px solid transparent;
  white-space: nowrap;
}
.st-pending { background: var(--bg-badge-warning); color: var(--text-warning); border-color: var(--border-subtle); }
.st-processing { background: var(--bg-badge-success); color: var(--color-primary); border-color: var(--border-accent); }
.st-completed { background: var(--bg-badge-success); color: var(--color-success); border-color: var(--border-subtle); }
.st-failed { background: var(--bg-badge-danger); color: var(--text-danger); border-color: var(--border-danger); }
.st-canceled { background: var(--bg-badge-purple); color: var(--text-purple); border-color: var(--border-purple); }

/* ── 进度条 ── */
.progress-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bar {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: var(--bg-track);
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: 3px;
  background: var(--color-primary);
  transition: width 0.4s ease;
}
.bar-fill.st-completed { background: var(--color-success); }
.bar-fill.st-failed { background: var(--color-danger); }
.bar-fill.st-canceled { background: var(--color-purple); }
.bar-fill.st-pending { background: var(--color-warning); }
.progress-text {
  width: 44px;
  text-align: right;
  font-size: 11.5px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

/* ── 按钮 ── */
.btn-primary,
.btn-ghost,
.btn-mini {
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
  font-family: inherit;
}
.btn-primary {
  padding: 7px 16px;
  font-size: 12.5px;
  background: var(--color-primary);
  color: var(--text-white);
  border: 1px solid var(--color-primary);
}
.btn-primary:hover:not(:disabled) { filter: brightness(1.1); }
.btn-ghost {
  padding: 7px 14px;
  font-size: 12.5px;
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border-admin-input);
}
.btn-ghost:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
.btn-ghost:disabled { opacity: 0.45; cursor: not-allowed; }
.btn-mini {
  padding: 3px 10px;
  font-size: 11.5px;
  background: var(--bg-admin-input);
  color: var(--text-secondary);
  border: 1px solid var(--border-admin-input);
}
.btn-mini:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }

/* ── 启用模型 ── */
.model-body {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  padding: 14px 16px;
}
.model-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 5px;
  min-width: 0;
}
.model-label {
  font-size: 11.5px;
  color: var(--text-muted);
}
.model-value {
  font-size: 12.5px;
  color: var(--text-secondary);
  word-break: break-all;
}

/* ── 空态 / 加载 ── */
.state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 42px 16px;
  font-size: 12.5px;
  color: var(--text-muted);
}
.state.small { padding: 26px 16px; }
.state-icon { font-size: 20px; }
.spinner {
  width: 13px;
  height: 13px;
  border: 2px solid var(--border-admin-input);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* ── 窄屏退化 ── */
@media (max-width: 1180px) {
  .stat-row { grid-template-columns: repeat(3, 1fr); }
  .grid-2 { grid-template-columns: minmax(0, 1fr); }
  .model-body { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
