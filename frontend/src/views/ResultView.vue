<template>
  <div class="page">
    <!-- 页头 -->
    <header class="page-head">
      <div class="head-left">
        <h1 class="page-title">识别结果</h1>
        <p class="page-sub">
          按任务、物种、置信度、复核状态与检出时间检索每条识别结果，并查看该图像上叠加检测框的完整结果
        </p>
      </div>
      <div class="head-actions">
        <button class="btn-ghost" :disabled="loading" @click="loadResults">刷新</button>
        <button class="btn-primary" :disabled="exporting" @click="doExport">
          {{ exporting ? '导出中...' : '导出 CSV' }}
        </button>
      </div>
    </header>

    <!-- 概览 -->
    <section class="stat-row">
      <div class="stat-card">
        <span class="stat-value">{{ total.toLocaleString('zh-CN') }}</span>
        <span class="stat-label">符合条件的结果数</span>
      </div>
      <div class="stat-card">
        <span class="stat-value">{{ speciesOptions.length }}</span>
        <span class="stat-label">库内物种数</span>
      </div>
      <div class="stat-card">
        <span class="stat-value" :class="avgTone">{{ pageAvgConfidence.toFixed(1) }}%</span>
        <span class="stat-label">本页平均置信度</span>
      </div>
      <div class="stat-card">
        <span class="stat-value" :class="pendingCount ? 'warn' : 'ok'">{{ pendingCount }}</span>
        <span class="stat-label">本页待复核</span>
      </div>
    </section>

    <!-- 筛选 -->
    <section class="card filter-bar">
      <label class="field">
        <span class="field-label">所属任务</span>
        <select v-model.number="filters.taskId">
          <option :value="0">全部任务</option>
          <option v-for="t in tasks" :key="t.id" :value="t.id">
            #{{ t.id }} {{ t.taskName || '未命名任务' }}
          </option>
        </select>
      </label>

      <label class="field">
        <span class="field-label">物种类别</span>
        <select v-model="filters.className">
          <option value="">全部物种</option>
          <option v-for="name in speciesOptions" :key="name" :value="name">{{ name }}</option>
        </select>
      </label>

      <label class="field">
        <span class="field-label">复核状态</span>
        <select v-model="filters.status">
          <option value="">全部状态</option>
          <option v-for="(label, key) in REVIEW_STATUS_LABEL" :key="key" :value="key">{{ label }}</option>
        </select>
      </label>

      <label class="field conf-field">
        <span class="field-label">
          最低置信度
          <b class="conf-value">{{ (filters.minConfidence * 100).toFixed(0) }}%</b>
        </span>
        <input v-model.number="filters.minConfidence" type="range" min="0" max="1" step="0.05" />
      </label>

      <label class="field">
        <span class="field-label">检出时间（起）</span>
        <input v-model="filters.startTime" type="datetime-local" />
      </label>

      <label class="field">
        <span class="field-label">检出时间（止）</span>
        <input v-model="filters.endTime" type="datetime-local" />
      </label>

      <div class="filter-actions">
        <button class="btn-ghost" @click="resetFilters">重置</button>
        <button class="btn-primary" @click="applyFilters">查询</button>
      </div>
    </section>

    <!-- 主体 -->
    <div class="result-body">
      <!-- 结果表 -->
      <section class="card table-card">
        <div class="card-head">
          <span>结果明细</span>
          <span class="head-meta">共 {{ total }} 条</span>
        </div>

        <div v-if="loading" class="state">
          <span class="spinner"></span>
          <span>识别结果加载中...</span>
        </div>
        <div v-else-if="!rows.length" class="state">
          <span class="state-icon">🔍</span>
          <span>没有符合条件的结果，试试放宽筛选条件</span>
        </div>

        <table v-else class="table">
          <thead>
            <tr>
              <th class="col-id">结果 ID</th>
              <th>物种类别</th>
              <th class="col-conf">置信度</th>
              <th>检测框</th>
              <th class="col-review">复核状态</th>
              <th class="col-time">识别时间</th>
              <th class="col-ops">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in rows"
              :key="row.id"
              :class="{ active: activeId === row.id }"
              @click="selectRow(row)"
            >
              <td class="mono">#{{ row.id }}</td>
              <td>
                <span class="dot" :style="{ background: colorOfClass(row.classId, row.className) }"></span>
                {{ row.className }}
              </td>
              <td>
                <div class="conf-cell">
                  <span
                    class="conf-fill"
                    :style="{ width: confPercent(row) + '%', background: colorOfConfidence(row.confidence) }"
                  ></span>
                  <span class="conf-text">{{ (row.confidence * 100).toFixed(1) }}%</span>
                </div>
              </td>
              <td class="mono small">{{ row.x1 }},{{ row.y1 }} → {{ row.x2 }},{{ row.y2 }}</td>
              <td>
                <span class="badge" :class="reviewClass(row.reviewStatus)">
                  {{ REVIEW_STATUS_LABEL[row.reviewStatus] || row.reviewStatus }}
                </span>
              </td>
              <td class="time">{{ formatTime(row.createTime) }}</td>
              <td class="ops">
                <button class="btn-mini" @click.stop="openReviewSingle(row)">复核</button>
                <button class="btn-mini danger" @click.stop="doDelete(row)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>

        <footer v-if="total > size" class="pager">
          <button class="btn-mini" :disabled="page <= 1" @click="turnPage(-1)">上一页</button>
          <span class="pager-text">第 {{ page }} / {{ totalPages }} 页</span>
          <button class="btn-mini" :disabled="page >= totalPages" @click="turnPage(1)">下一页</button>
        </footer>
      </section>

      <!-- 图像结果卡 -->
      <aside class="card detail-card">
        <div class="card-head">
          <span>图像与检测框</span>
          <span class="head-meta">
            {{ imageDetail.image ? imageDetail.image.fileName : '选择左侧任意结果' }}
          </span>
        </div>

        <div v-if="loadingDetail" class="state">
          <span class="spinner"></span>
          <span>图像加载中...</span>
        </div>
        <div v-else-if="!imageDetail.image" class="state">
          <span class="state-icon">🖼</span>
          <span>点击左侧结果行，这里会显示该图像的全部检出目标</span>
        </div>

        <div v-else class="detail-body">
          <ImageResultCard
            :image="imageDetail.image"
            :detections="imageDetail.detections"
            :selected-id="activeId"
            :show-actions="true"
            image-max-height="300px"
            @select="onCardSelect"
            @review="openReviewSingle"
            @review-all="openReviewBatch"
            @view="openRawImage"
          />

          <div class="detail-foot">
            <button class="btn-ghost" @click="goTask">查看所属任务</button>
            <button class="btn-ghost" @click="filterByImage">只看该图结果</button>
          </div>
        </div>
      </aside>
    </div>

    <ReviewDialog
      v-model:visible="reviewVisible"
      :result="reviewTarget"
      :results="reviewBatch"
      :image="imageDetail.image"
      :model-id="reviewModelId"
      @reviewed="onReviewed"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import ImageResultCard from '@/components/ImageResultCard.vue'
import ReviewDialog from '@/components/ReviewDialog.vue'
import {
  colorOfClass,
  colorOfConfidence,
  deleteResult,
  exportResultsCsv,
  getClassStatistics,
  getImage,
  getImageBlob,
  getImageResults,
  listResults,
  listTasks,
  REVIEW_STATUS_LABEL,
  type DetectionResult,
  type RecognitionImage,
  type RecognitionTask,
  type ReviewAction
} from '@/api/index'

const router = useRouter()

const loading = ref(false)
const exporting = ref(false)
const rows = ref<DetectionResult[]>([])
const total = ref(0)
const page = ref(1)
const size = 20

const tasks = ref<RecognitionTask[]>([])
const speciesOptions = ref<string[]>([])

const filters = reactive({
  taskId: 0,
  className: '',
  status: '' as '' | keyof typeof REVIEW_STATUS_LABEL,
  minConfidence: 0,
  imageId: 0,
  /** 检出时间区间，取 <input type="datetime-local"> 的值（yyyy-MM-ddTHH:mm）；留空表示不限 */
  startTime: '',
  endTime: ''
})

const activeId = ref<number | null>(null)
const loadingDetail = ref(false)
const imageDetail = ref<{ image: RecognitionImage | null; detections: DetectionResult[] }>({
  image: null,
  detections: []
})

const reviewVisible = ref(false)
const reviewTarget = ref<DetectionResult | null>(null)
const reviewBatch = ref<DetectionResult[]>([])

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

const pageAvgConfidence = computed(() => {
  if (!rows.value.length) return 0
  const sum = rows.value.reduce((acc, r) => acc + (r.confidence ?? 0), 0)
  return (sum / rows.value.length) * 100
})

const avgTone = computed(() => {
  const value = pageAvgConfidence.value
  if (value >= 85) return 'ok'
  if (value >= 60) return 'warn'
  return value > 0 ? 'bad' : ''
})

const pendingCount = computed(
  () => rows.value.filter((r) => (r.reviewStatus ?? 'PENDING') === 'PENDING').length
)

const reviewModelId = computed(
  () => reviewTarget.value?.modelId ?? imageDetail.value.detections[0]?.modelId ?? 0
)

function confPercent(item: DetectionResult): number {
  return Math.max(0, Math.min(100, item.confidence * 100))
}

function reviewClass(status: string): string {
  return `rv-${String(status || 'PENDING').toLowerCase()}`
}

function formatTime(value?: string): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

async function loadTasks() {
  try {
    // 任务下拉要尽量全的选项：直接取后端单页上限（maxLimit = 200）
    const res = await listTasks({ page: 1, size: 200 })
    tasks.value = res.data?.list ?? []
  } catch {
    tasks.value = []
  }
}

/** 物种下拉：取库内出现过的物种（按检出次数排序）。 */
async function loadSpecies() {
  try {
    const res = await getClassStatistics({ top: 200 })
    speciesOptions.value = (res.data?.list ?? []).map((item) => item.name)
  } catch {
    speciesOptions.value = []
  }
}

async function loadResults() {
  loading.value = true
  try {
    const res = await listResults({
      taskId: filters.taskId > 0 ? filters.taskId : undefined,
      imageId: filters.imageId > 0 ? filters.imageId : undefined,
      className: filters.className || undefined,
      minConfidence: filters.minConfidence > 0 ? filters.minConfidence : undefined,
      // 后端参数名是 reviewStatus。此前这里写成了 status，而构建又跳过了 vue-tsc，
      // 类型错误没被拦住 —— 结果是复核状态筛选静默失效（查了等于没查）。
      reviewStatus: filters.status || undefined,
      startTime: filters.startTime || undefined,
      endTime: filters.endTime || undefined,
      page: page.value,
      size
    })
    rows.value = res.data?.list ?? []
    total.value = res.data?.total ?? 0
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  page.value = 1
  loadResults()
}

function resetFilters() {
  filters.taskId = 0
  filters.className = ''
  filters.status = ''
  filters.minConfidence = 0
  filters.imageId = 0
  filters.startTime = ''
  filters.endTime = ''
  page.value = 1
  loadResults()
}

function turnPage(delta: number) {
  page.value = Math.max(1, Math.min(totalPages.value, page.value + delta))
  loadResults()
}

async function selectRow(row: DetectionResult) {
  activeId.value = row.id
  if (!row.imageId) return
  loadingDetail.value = true
  try {
    const [imgRes, detRes] = await Promise.all([
      getImage(row.imageId),
      getImageResults(row.imageId)
    ])
    imageDetail.value = {
      image: imgRes.data ?? null,
      detections: detRes.data ?? []
    }
  } catch {
    imageDetail.value = { image: null, detections: [] }
  } finally {
    loadingDetail.value = false
  }
}

function onCardSelect(item: DetectionResult | null) {
  activeId.value = item?.id ?? null
}

/** 只看当前选中图像的结果。 */
function filterByImage() {
  const imageId = imageDetail.value.image?.id
  if (!imageId) return
  filters.imageId = imageId
  filters.taskId = 0
  page.value = 1
  loadResults()
}

/** 跳转到当前图像所属的任务详情。 */
function goTask() {
  const taskId = imageDetail.value.image?.taskId
  if (!taskId) return
  router.push(`/tasks/${taskId}`)
}

/**
 * 「查看原图」：不能把 `/api/images/{id}/raw` 直接交给 `window.open`
 * —— 那是浏览器自发 GET，带不上 `X-Auth-Token`，必 401。
 * 走 axios 取 Blob → ObjectURL → 新标签页打开。
 */
async function openRawImage(image: RecognitionImage) {
  try {
    const blob = await getImageBlob(image.id, 'raw')
    const url = URL.createObjectURL(blob)
    const win = window.open(url, '_blank')
    // 给新窗口足够时间读取 Blob URL，再释放
    window.setTimeout(() => {
      URL.revokeObjectURL(url)
    }, 60_000)
    if (!win) {
      URL.revokeObjectURL(url)
      console.warn('浏览器拦截了新窗口，请允许本站点弹窗')
    }
  } catch (error) {
    console.error('打开原图失败:', error)
  }
}

function openReviewSingle(item: DetectionResult) {
  reviewBatch.value = []
  reviewTarget.value = item
  reviewVisible.value = true
}

function openReviewBatch(items: DetectionResult[]) {
  if (!items.length) return
  reviewTarget.value = null
  reviewBatch.value = [...items]
  reviewVisible.value = true
}

function onReviewed(payload: {
  resultIds: number[]
  action: ReviewAction
  className?: string
  remark?: string
}) {
  const map: Record<ReviewAction, DetectionResult['reviewStatus']> = {
    CONFIRM: 'CONFIRMED',
    CORRECT: 'CORRECTED',
    REJECT: 'REJECTED'
  }
  const next = map[payload.action]
  const patch = (item: DetectionResult): DetectionResult => {
    if (!payload.resultIds.includes(item.id)) return item
    return {
      ...item,
      reviewStatus: next,
      className: payload.action === 'CORRECT' && payload.className ? payload.className : item.className
    }
  }
  rows.value = rows.value.map(patch)
  imageDetail.value = {
    ...imageDetail.value,
    detections: imageDetail.value.detections.map(patch)
  }
}

async function doDelete(row: DetectionResult) {
  if (!window.confirm(`删除结果 #${row.id}（${row.className}）？该操作不可撤销。`)) return
  try {
    await deleteResult(row.id)
    if (activeId.value === row.id) {
      activeId.value = null
      imageDetail.value = { image: null, detections: [] }
    }
    await loadResults()
    await loadSpecies()
  } catch (err: any) {
    window.alert(`删除失败：${err?.response?.data?.error || err?.message || '未知错误'}`)
  }
}

async function doExport() {
  exporting.value = true
  try {
    await exportResultsCsv({
      taskId: filters.taskId > 0 ? filters.taskId : undefined,
      className: filters.className || undefined,
      minConfidence: filters.minConfidence > 0 ? filters.minConfidence : undefined,
      reviewStatus: filters.status || undefined,
      startTime: filters.startTime || undefined,
      endTime: filters.endTime || undefined
    })
  } catch (err: any) {
    window.alert(`导出失败：${err?.response?.data?.error || err?.message || '未知错误'}`)
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  loadTasks()
  loadSpecies()
  loadResults()
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
  margin-bottom: 16px;
}
.page-title { font-size: 19px; font-weight: 700; letter-spacing: 0.5px; }
.page-sub { margin-top: 5px; font-size: 12px; color: var(--text-muted); }
.head-actions { display: flex; gap: 8px; flex-shrink: 0; }

/* ── 概览 ── */
.stat-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
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
  font-size: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.stat-value.ok { color: var(--color-success); }
.stat-value.warn { color: var(--color-warning); }
.stat-value.bad { color: var(--color-danger); }
.stat-label { font-size: 11.5px; color: var(--text-muted); }

/* ── 卡片 ── */
.card {
  background: var(--bg-admin-card);
  border: 1px solid var(--border-primary);
  border-radius: 8px;
  overflow: hidden;
}
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
  max-width: 60%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── 筛选 ── */
.filter-bar {
  display: flex;
  align-items: flex-end;
  gap: 14px;
  padding: 14px 16px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 160px;
}
.field-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--text-muted);
}
.conf-field { min-width: 220px; }
.conf-value { color: var(--color-primary); font-variant-numeric: tabular-nums; }
input[type='range'] {
  width: 100%;
  height: 22px;
  accent-color: var(--color-primary);
  background: transparent;
  border: none;
  padding: 0;
}
input,
select {
  height: 32px;
  padding: 0 9px;
  background: var(--bg-admin-input);
  border: 1px solid var(--border-admin-input);
  border-radius: 4px;
  color: var(--text-primary);
  font-size: 12.5px;
  outline: none;
}
input:focus,
select:focus { border-color: var(--color-admin-focus); }
.filter-actions { display: flex; gap: 8px; margin-left: auto; }

/* ── 主体布局 ── */
.result-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 400px;
  gap: 14px;
  align-items: start;
}
.table-card { min-width: 0; }
.detail-body { padding: 12px; }
.detail-foot {
  display: flex;
  gap: 8px;
  margin-top: 12px;
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
  padding: 9px 12px;
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border-admin-table);
}
.table tbody tr { cursor: pointer; }
.table tbody tr:hover { background: var(--bg-card-hover); }
.table tbody tr.active { background: var(--bg-card-active); }
.col-id { width: 82px; }
.col-conf { width: 140px; }
.col-review { width: 96px; }
.col-time { width: 150px; }
.col-ops { width: 120px; }
.mono { font-family: Consolas, Monaco, monospace; font-size: 11.5px; color: var(--text-muted); }
.mono.small { font-size: 11px; }
.time { color: var(--text-muted); font-size: 12px; }
.ops { display: flex; gap: 6px; }
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}

.conf-cell { position: relative; display: flex; align-items: center; }
.conf-cell::before {
  content: '';
  position: absolute;
  left: 0;
  width: 54px;
  height: 6px;
  border-radius: 3px;
  background: var(--bg-track);
}
.conf-fill {
  position: relative;
  height: 6px;
  border-radius: 3px;
  max-width: 54px;
  min-width: 2px;
}
.conf-text {
  margin-left: 60px;
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

/* ── 徽标 ── */
.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 9px;
  font-size: 11px;
  white-space: nowrap;
}
.rv-pending { background: var(--bg-badge-warning); color: var(--text-warning); }
.rv-confirmed { background: var(--bg-badge-success); color: var(--color-success); }
.rv-corrected { background: var(--bg-badge-purple); color: var(--text-purple); }
.rv-rejected { background: var(--bg-badge-danger-alt); color: var(--text-danger); }

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
.btn-mini {
  padding: 3px 10px;
  font-size: 11.5px;
  background: var(--bg-admin-input);
  color: var(--text-secondary);
  border: 1px solid var(--border-admin-input);
}
.btn-mini:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
.btn-mini.danger:hover:not(:disabled) { border-color: var(--color-danger); color: var(--text-danger); }
.btn-primary:disabled,
.btn-ghost:disabled,
.btn-mini:disabled { opacity: 0.45; cursor: not-allowed; }

/* ── 空态 ── */
.state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 46px 16px;
  font-size: 12.5px;
  color: var(--text-muted);
  text-align: center;
}
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

/* ── 分页 ── */
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 12px;
  border-top: 1px solid var(--border-admin-table);
}
.pager-text { font-size: 12px; color: var(--text-muted); }

@media (max-width: 1440px) {
  .result-body { grid-template-columns: 1fr; }
}
</style>
