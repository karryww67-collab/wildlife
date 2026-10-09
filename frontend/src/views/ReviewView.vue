<template>
  <div class="page">
    <!-- 页头 -->
    <header class="page-head">
      <div class="head-left">
        <h1 class="page-title">人工复核</h1>
        <p class="page-sub">
          按置信度从低到高逐条核对模型判定：确认无误、修正物种或剔除误检，复核结果会回流为模型改进依据
        </p>
      </div>
      <div class="head-actions">
        <button class="btn-ghost" :disabled="loading" @click="reloadAll">刷新</button>
        <button class="btn-primary" :disabled="!pendingRows.length" @click="startWorkflow">
          开始复核本页
        </button>
      </div>
    </header>

    <!-- 复核统计 -->
    <section class="stat-row">
      <div class="stat-card">
        <span class="stat-value">{{ stats.total }}</span>
        <span class="stat-label">识别结果总数</span>
      </div>
      <div class="stat-card">
        <span class="stat-value ok">{{ stats.reviewed }}</span>
        <span class="stat-label">已复核</span>
      </div>
      <div class="stat-card">
        <span class="stat-value warn">{{ stats.pending }}</span>
        <span class="stat-label">待复核</span>
        <span v-if="stats.pendingMissing > 0" class="stat-note">
          其中 {{ stats.pendingMissing }} 项原图已丢失
        </span>
      </div>
      <div class="stat-card">
        <span class="stat-value">{{ stats.reviewRate.toFixed(1) }}%</span>
        <span class="stat-label">复核率</span>
      </div>
      <div class="stat-card">
        <span class="stat-value bad">{{ stats.correctRate.toFixed(1) }}%</span>
        <span class="stat-label">修正率（复核项中）</span>
      </div>
      <div class="stat-card">
        <span class="stat-value">{{ stats.rejected }}</span>
        <span class="stat-label">已剔除误检</span>
      </div>
    </section>

    <!-- 易错物种 -->
    <section v-if="stats.topCorrectedClasses.length" class="card miscard">
      <div class="card-head">
        <span>最容易被认错的物种</span>
        <span class="head-meta">Top {{ stats.topCorrectedClasses.length }} · 按被修正次数排序</span>
      </div>
      <ul class="miscard-list">
        <li v-for="item in stats.topCorrectedClasses" :key="item.className" class="miscard-item">
          <span class="miscard-name">{{ item.className }}</span>
          <span class="miscard-count">被修正 {{ item.correctedCount }} 次</span>
        </li>
      </ul>
    </section>

    <!-- 筛选 -->
    <section class="card filter-bar">
      <label class="field">
        <span class="field-label">所属任务</span>
        <select v-model.number="filters.taskId" @change="applyFilters">
          <option :value="0">全部任务</option>
          <option v-for="t in tasks" :key="t.id" :value="t.id">
            #{{ t.id }} {{ t.taskName || '未命名任务' }}
          </option>
        </select>
      </label>

      <label class="field">
        <span class="field-label">置信度上限</span>
        <select v-model.number="filters.maxConfidence" @change="applyFilters">
          <option :value="0">不限制</option>
          <option :value="0.5">≤ 50%（最不确定）</option>
          <option :value="0.6">≤ 60%</option>
          <option :value="0.7">≤ 70%</option>
          <option :value="0.8">≤ 80%</option>
          <option :value="0.9">≤ 90%</option>
        </select>
      </label>

      <label class="field field-check">
        <input
          type="checkbox"
          v-model="filters.hideMissing"
          @change="applyFilters"
        />
        <span class="check-label">隐藏原图已丢失的项</span>
      </label>

      <span class="filter-hint">
        待复核队列已按置信度升序排列，优先处理低置信度结果
      </span>

      <button class="btn-ghost" @click="resetFilters">重置</button>
    </section>

    <!-- 主体 -->
    <div class="review-body">
      <!-- 待复核队列 -->
      <section class="card list-card">
        <div class="card-head">
          <span>
            待复核队列
            <span class="sel-hint" v-if="selectedIds.length">已选 {{ selectedIds.length }} 项</span>
          </span>
          <span class="head-meta">共 {{ total }} 条</span>
        </div>

        <div v-if="loading" class="state">
          <span class="spinner"></span>
          <span>待复核队列加载中...</span>
        </div>
        <div v-else-if="!rows.length" class="state">
          <span class="state-icon">✅</span>
          <span>{{ emptyHint }}</span>
        </div>

        <template v-else>
          <div class="bulk-bar">
            <label class="check-all">
              <input type="checkbox" :checked="allSelected" @change="toggleAll" />
              <span>全选本页可复核项</span>
            </label>
            <div class="bulk-actions">
              <button class="btn-mini primary" :disabled="!selectedIds.length" @click="openBatch">
                批量复核所选{{ selectedIds.length ? ` (${selectedIds.length})` : '' }}
              </button>
              <button class="btn-mini" :disabled="!selectedIds.length" @click="selectedIds = []">清空</button>
            </div>
          </div>

          <table class="table">
            <thead>
              <tr>
                <th class="col-check"></th>
                <th class="col-id">结果 ID</th>
                <th>物种类别</th>
                <th class="col-conf">置信度</th>
                <th>检测框</th>
                <th class="col-ops">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in rows"
                :key="row.id"
                :class="{ active: activeRow?.id === row.id }"
                @click="setActive(row)"
              >
                <td class="col-check" @click.stop>
                  <input
                    type="checkbox"
                    :checked="selectedIds.includes(row.id)"
                    :disabled="row.imageAvailable === false"
                    :title="row.imageAvailable === false ? '原图已丢失，无法复核' : ''"
                    @change="toggleRow(row.id)"
                  />
                </td>
                <td class="mono">#{{ row.id }}</td>
                <td>
                  <span class="dot" :style="{ background: colorOfClass(row.classId, row.className) }"></span>
                  {{ row.className }}
                  <span
                    v-if="row.imageAvailable === false"
                    class="tag-lost"
                    title="磁盘上的原图已丢失，看不到图就无法核对"
                  >原图丢失</span>
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
                <td class="ops">
                  <button
                    class="btn-mini primary"
                    :disabled="row.imageAvailable === false"
                    :title="row.imageAvailable === false ? '原图已丢失，无法核对' : '复核该结果'"
                    @click.stop="openSingle(row)"
                  >复核</button>
                </td>
              </tr>
            </tbody>
          </table>

          <footer v-if="total > size" class="pager">
            <div class="pager-row">
              <button class="btn-mini" :disabled="page <= 1" @click="turnPage(-1)">上一页</button>
              <span class="pager-text">第 {{ page }} / {{ totalPages }} 页</span>
              <button class="btn-mini" :disabled="page >= totalPages" @click="turnPage(1)">下一页</button>
            </div>
            <div class="pager-jump">
              <span class="pager-text">跳至</span>
              <input
                v-model.number="jumpTo"
                class="pager-input"
                type="number"
                min="1"
                :max="totalPages"
                placeholder="页码"
                @keyup.enter="goToPage()"
              />
              <span class="pager-text">页</span>
              <button class="btn-mini" :disabled="!canJump" @click="goToPage()">跳转</button>
              <span class="pager-text pager-hint">共 {{ total }} 条 · 每页 {{ size }} 条</span>
            </div>
          </footer>
        </template>
      </section>

      <!-- 图像预览 -->
      <aside class="card preview-card">
        <div class="card-head">
          <span>图像预览</span>
          <span class="head-meta">
            {{ preview.image ? preview.image.fileName : '点击左侧任意结果' }}
          </span>
        </div>

        <div v-if="loadingPreview" class="state">
          <span class="spinner"></span>
          <span>图像加载中...</span>
        </div>
        <div v-else-if="!preview.image" class="state">
          <span class="state-icon">🖼</span>
          <span>这里会显示待复核结果所在的图像与全部检测框</span>
        </div>

        <div v-else class="preview-body">
          <DetectionImage
            :src="rawUrl"
            :alt="preview.image.fileName"
            :detections="preview.detections"
            :selected-id="activeRow?.id ?? null"
            :show-legend="preview.detections.length > 1"
            :color-mode="activeRow && activeRow.confidence < CONFIDENCE_LOW ? 'confidence' : 'class'"
            max-height="44vh"
            @select="onBoxSelect"
          />

          <div v-if="activeRow" class="active-block">
            <div class="active-row">
              <span class="active-label">当前结果</span>
              <span class="active-name">{{ activeRow.className }}</span>
              <span class="active-conf" :style="{ color: colorOfConfidence(activeRow.confidence) }">
                {{ (activeRow.confidence * 100).toFixed(1) }}%
              </span>
            </div>
            <p class="active-tip">{{ confidenceTip(activeRow.confidence) }}</p>
            <div class="active-actions">
              <button
                class="btn-mini primary"
                :disabled="!canReview(activeRow)"
                :title="canReview(activeRow) ? '' : '原图已丢失，无法核对'"
                @click="openSingle(activeRow)"
              >复核该结果</button>
              <button
                class="btn-mini"
                :disabled="!canReview(activeRow)"
                @click="openReviewOfImage"
              >复核该图全部待定项</button>
            </div>
          </div>
        </div>
      </aside>
    </div>

    <!-- 复核弹窗 -->
    <ReviewDialog
      v-model:visible="dialogVisible"
      :result="dialogTarget"
      :results="dialogBatch"
      :image="preview.image"
      :model-id="dialogModelId"
      @reviewed="onReviewed"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import DetectionImage from '@/components/DetectionImage.vue'
import ReviewDialog from '@/components/ReviewDialog.vue'
import {
  colorOfClass,
  colorOfConfidence,
  getImage,
  getImageResults,
  getReviewStats,
  listPendingReviews,
  listTasks,
  type DetectionBox,
  type DetectionResult,
  type RecognitionImage,
  type RecognitionTask,
  type ReviewAction,
  type ReviewStats
} from '@/api/index'
import { loadProtectedImage, revokeImageUrl } from '@/utils/imageUrl'

/**
 * 置信度分级阈值 —— 复核场景的判读口径，集中在此处，避免同一个数字散落在模板与函数里。
 * 注意：这几个值是「怎么提示复核员」的展示口径，不是后端的入库/复核判定条件，
 * 后端并没有可配置的复核阈值，所以不从这里反向约束后端。
 */
/** 低于此值视为「偏低」，预览框改用按置信度着色，提示语也最强 */
const CONFIDENCE_LOW = 0.6
/** 高于此值视为「较高」，提示可以快速确认 */
const CONFIDENCE_HIGH = 0.85

const loading = ref(false)
const rows = ref<DetectionResult[]>([])
const total = ref(0)
const page = ref(1)
const size = 20

const tasks = ref<RecognitionTask[]>([])
/**
 * hideMissing 默认开：原图已丢失（悬空引用）的结果看不到图，留着也复核不了，
 * 只会把按置信度升序的队列前排占满 —— 默认把这些脏数据挡在队列外。
 * 关掉它仍能在列表里看到并看清原因（会打「原图丢失」标记、复核按钮禁用）。
 */
const filters = reactive({ taskId: 0, maxConfidence: 0, hideMissing: true })

const selectedIds = ref<number[]>([])
const activeRow = ref<DetectionResult | null>(null)

const loadingPreview = ref(false)
const preview = ref<{ image: RecognitionImage | null; detections: DetectionResult[] }>({
  image: null,
  detections: []
})

/** 预览图的 ObjectURL：预览走 raw、列表走 thumbnail。 */
const rawUrl = ref('')

/** 释放当前预览原图地址（换行 / 清空预览 / 组件卸载时调用）。 */
function releaseRaw() {
  revokeImageUrl(rawUrl.value)
  rawUrl.value = ''
}

/**
 * 取原图：先经 axios（自动带 X-Auth-Token）拿 Blob，再转 ObjectURL 交给 `<img>`。
 * 直接把 `/api/images/{id}/raw` 当 src 用浏览器自发请求，带不上请求头，必 401。
 */
async function loadRawImage(imageId: number) {
  releaseRaw()
  try {
    const url = await loadProtectedImage(imageId, 'raw')
    // 期间可能已切到别的图：丢弃迟到的响应，避免显示错图
    if (preview.value.image?.id !== imageId) {
      revokeImageUrl(url)
      return
    }
    rawUrl.value = url
  } catch (error) {
    console.error('加载原图失败:', error)
  }
}

// 预览图像变了才换原图（换行、换筛选条件、清空选中都会走到这里）
watch(
  () => preview.value.image?.id ?? 0,
  (id) => {
    if (id) loadRawImage(id)
    else releaseRaw()
  }
)

const dialogVisible = ref(false)
const dialogTarget = ref<DetectionResult | null>(null)
const dialogBatch = ref<DetectionResult[]>([])

const stats = ref<ReviewStats>({
  total: 0,
  reviewed: 0,
  pending: 0,
  pendingMissing: 0,
  confirmed: 0,
  corrected: 0,
  rejected: 0,
  reviewRate: 0,
  correctRate: 0,
  rejectRate: 0,
  topCorrectedClasses: []
})

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

/** 跳页输入框的值；空串表示未输入。 */
const jumpTo = ref<number | string>('')

/** 目标页合法才允许跳转：必须是整数、落在 [1, totalPages] 内，且不是当前页。 */
const canJump = computed(() => {
  const n = Number(jumpTo.value)
  return Number.isInteger(n) && n >= 1 && n <= totalPages.value && n !== page.value
})

/** 跳到输入框指定的页。越界或非数字一律忽略，不改变当前页。 */
function goToPage() {
  if (!canJump.value) return
  page.value = Number(jumpTo.value)
  jumpTo.value = ''
  loadPending()
}

/** 原图已丢失的结果不可复核 —— 只有 imageAvailable === false 才算，null/undefined 一律放行。 */
function canReview(row: DetectionResult): boolean {
  return row.imageAvailable !== false
}

/** 本页真正能复核的行，全选与「开始复核本页」都以它为准。 */
const reviewableRows = computed(() => rows.value.filter(canReview))

const allSelected = computed(
  () =>
    reviewableRows.value.length > 0 &&
    reviewableRows.value.every((row) => selectedIds.value.includes(row.id))
)
const pendingRows = computed(() => reviewableRows.value)

/** 队列被清空时的文案：区分"本来就没有"和"被 hideMissing 挡掉了"。 */
const emptyHint = computed(() => {
  if (filters.hideMissing && stats.value.pendingMissing > 0) {
    return `当前筛选条件下没有可复核的结果（另有 ${stats.value.pendingMissing} 项因原图已丢失被隐藏）`
  }
  return '当前筛选条件下没有待复核的结果'
})

/** 复核弹窗读类别清单用的模型 ID。 */
const dialogModelId = computed(
  () => dialogTarget.value?.modelId ?? preview.value.detections[0]?.modelId ?? 0
)

function confPercent(item: DetectionResult): number {
  return Math.max(0, Math.min(100, item.confidence * 100))
}

function confidenceTip(confidence: number): string {
  if (confidence >= CONFIDENCE_HIGH) return '置信度较高，模型判定较可靠，快速确认即可'
  if (confidence >= CONFIDENCE_LOW) return '置信度中等，建议结合体型与毛色特征判断是否修正物种'
  return '置信度偏低，容易是误检，请重点确认是否为可辨认的动物目标'
}

/** 迟到的响应按序号丢弃 —— 切筛选条件够快时，慢的旧请求会覆盖掉新结果。 */
let tasksSeq = 0
let statsSeq = 0
let pendingSeq = 0

async function loadTasks() {
  const seq = ++tasksSeq
  try {
    // 任务下拉要尽量全的选项：直接取后端单页上限（maxLimit = 200）
    const res = await listTasks({ page: 1, size: 200 })
    if (seq !== tasksSeq) return
    tasks.value = res.data?.list ?? []
  } catch {
    if (seq !== tasksSeq) return
    tasks.value = []
  }
}

async function loadStats() {
  const seq = ++statsSeq
  try {
    const res = await getReviewStats(filters.taskId > 0 ? filters.taskId : undefined)
    // 全局统计要扫全表（约 1s），按任务统计只有几十毫秒 —— 不丢弃迟到响应的话，
    // "先看全部、再切到某任务" 会把全局数字盖回任务口径上（空态提示会跟着写错）。
    if (seq !== statsSeq) return
    const data = (res.data ?? {}) as unknown as Partial<ReviewStats>
    stats.value = {
      total: data.total ?? 0,
      reviewed: data.reviewed ?? 0,
      pending: data.pending ?? 0,
      pendingMissing: data.pendingMissing ?? 0,
      confirmed: data.confirmed ?? 0,
      corrected: data.corrected ?? 0,
      rejected: data.rejected ?? 0,
      reviewRate: data.reviewRate ?? 0,
      correctRate: data.correctRate ?? 0,
      rejectRate: data.rejectRate ?? 0,
      topCorrectedClasses: data.topCorrectedClasses ?? []
    }
  } catch {
    /* 统计失败不影响队列使用 */
  }
}

async function loadPending() {
  const seq = ++pendingSeq
  loading.value = true
  try {
    const res = await listPendingReviews({
      taskId: filters.taskId > 0 ? filters.taskId : undefined,
      maxConfidence: filters.maxConfidence > 0 ? filters.maxConfidence : undefined,
      hideMissing: filters.hideMissing || undefined,
      page: page.value,
      size
    })
    if (seq !== pendingSeq) return
    rows.value = res.data?.list ?? []
    total.value = res.data?.total ?? 0
    // 已被隐藏的行不该留在选中集合里，否则批量复核会带上它们
    selectedIds.value = selectedIds.value.filter((id) => rows.value.some((r) => r.id === id && canReview(r)))
    if (activeRow.value && !rows.value.some((r) => r.id === activeRow.value?.id)) {
      activeRow.value = null
      preview.value = { image: null, detections: [] }
    } else if (!activeRow.value && rows.value.length) {
      setActive(rows.value[0])
    }
  } catch {
    if (seq !== pendingSeq) return
    rows.value = []
    total.value = 0
  } finally {
    // 迟到的响应不该把新一轮请求的 loading 关掉
    if (seq === pendingSeq) loading.value = false
  }
}

function applyFilters() {
  page.value = 1
  activeRow.value = null
  preview.value = { image: null, detections: [] }
  loadPending()
  loadStats()
}

function resetFilters() {
  filters.taskId = 0
  filters.maxConfidence = 0
  filters.hideMissing = true
  applyFilters()
}

function turnPage(delta: number) {
  page.value = Math.max(1, Math.min(totalPages.value, page.value + delta))
  loadPending()
}

function toggleRow(id: number) {
  // 原图已丢失的行不接受勾选：批量复核里混进它只会在后端被跳过
  const row = rows.value.find((r) => r.id === id)
  if (!row || !canReview(row)) return
  const index = selectedIds.value.indexOf(id)
  if (index >= 0) selectedIds.value.splice(index, 1)
  else selectedIds.value.push(id)
}

function toggleAll() {
  selectedIds.value = allSelected.value ? [] : reviewableRows.value.map((row) => row.id)
}

async function setActive(row: DetectionResult) {
  activeRow.value = row
  if (!row.imageId) return
  loadingPreview.value = true
  try {
    const [imgRes, detRes] = await Promise.all([
      getImage(row.imageId),
      getImageResults(row.imageId)
    ])
    preview.value = { image: imgRes.data ?? null, detections: detRes.data ?? [] }
  } catch {
    preview.value = { image: null, detections: [] }
  } finally {
    loadingPreview.value = false
  }
}

function onBoxSelect(box: DetectionBox | null) {
  if (!box?.id) return
  const matched = preview.value.detections.find((item) => item.id === box.id)
  if (matched) activeRow.value = matched
}

function openSingle(row: DetectionResult) {
  dialogBatch.value = []
  dialogTarget.value = row
  dialogVisible.value = true
}

/** 批量复核勾选项：动作在弹窗内选择（确认 / 修正 / 剔除）。 */
function openBatch() {
  if (!selectedIds.value.length) return
  const items = rows.value.filter((row) => selectedIds.value.includes(row.id))
  if (!items.length) return
  dialogTarget.value = null
  dialogBatch.value = items
  dialogVisible.value = true
}

/** 复核当前图像上的全部待定项。 */
function openReviewOfImage() {
  // 当前图的原图丢了，它上面的结果同样无法复核
  if (activeRow.value && !canReview(activeRow.value)) return
  const pending = preview.value.detections.filter(
    (item) => (item.reviewStatus ?? 'PENDING') === 'PENDING'
  )
  if (!pending.length) return
  dialogTarget.value = null
  dialogBatch.value = pending
  dialogVisible.value = true
}

/** 按顺序复核本页队列：从第一条**可复核**的结果开始（跳过原图已丢失的）。 */
function startWorkflow() {
  const first = rows.value.find(canReview)
  if (!first) return
  openSingle(first)
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
  const reviewed = new Set(payload.resultIds)

  preview.value = {
    ...preview.value,
    detections: preview.value.detections.map((item) =>
      reviewed.has(item.id)
        ? {
            ...item,
            reviewStatus: next,
            className:
              payload.action === 'CORRECT' && payload.className ? payload.className : item.className
          }
        : item
    )
  }

  if (activeRow.value && reviewed.has(activeRow.value.id)) {
    activeRow.value = null
  }

  // 已复核的结果不应再出现在待复核队列里
  loadPending()
  loadStats()
}

function reloadAll() {
  loadTasks()
  loadStats()
  loadPending()
}

onMounted(reloadAll)

onBeforeUnmount(releaseRaw)
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
.page-sub {
  margin-top: 5px;
  max-width: 860px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}
.head-actions { display: flex; gap: 8px; flex-shrink: 0; }

/* ── 统计 ── */
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
  font-size: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.stat-value.ok { color: var(--color-success); }
.stat-value.warn { color: var(--color-warning); }
.stat-value.bad { color: var(--color-danger); }
.stat-label { font-size: 11.5px; color: var(--text-muted); }
/* 待复核的口径说明：pending 是全量，列表可能被 hideMissing 挡掉一部分 */
.stat-note { font-size: 11px; color: var(--text-warning); line-height: 1.4; }

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
  max-width: 55%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sel-hint {
  margin-left: 8px;
  font-size: 11.5px;
  font-weight: 400;
  color: var(--color-primary);
}

/* ── 易错物种 ── */
.miscard { margin-bottom: 14px; }
.miscard-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 16px;
  list-style: none;
}
.miscard-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 12px;
  border-radius: 14px;
  background: var(--bg-badge-danger);
  border: 1px solid var(--border-danger);
}
.miscard-name { font-size: 12.5px; color: var(--text-primary); }
.miscard-count { font-size: 11px; color: var(--text-danger); }

/* ── 筛选 ── */
.filter-bar {
  display: flex;
  align-items: flex-end;
  gap: 14px;
  padding: 14px 16px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.field { display: flex; flex-direction: column; gap: 5px; min-width: 170px; }
.field-label { font-size: 11.5px; color: var(--text-muted); }

/* 勾选框类筛选项：与下拉框底对齐，不要撑出 32px 高 */
.field-check {
  flex-direction: row;
  align-items: center;
  gap: 7px;
  min-width: 0;
  height: 32px;
}
.check-label { font-size: 12.5px; color: var(--text-secondary); white-space: nowrap; }
.filter-hint {
  flex: 1;
  min-width: 200px;
  font-size: 11.5px;
  color: var(--text-dim);
  padding-bottom: 8px;
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
input[type='checkbox'] {
  width: 14px;
  height: 14px;
  padding: 0;
  accent-color: var(--color-primary);
}

/* ── 主体 ── */
.review-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 420px;
  gap: 14px;
  align-items: start;
}
.list-card { min-width: 0; }

.bulk-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 16px;
  background: var(--bg-admin-input-alt);
  border-bottom: 1px solid var(--border-admin-table);
}
.check-all {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
}
.bulk-actions { display: flex; gap: 6px; }

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
.col-check { width: 42px; }
.col-id { width: 84px; }
.col-conf { width: 150px; }
.col-ops { width: 84px; }
.mono { font-family: Consolas, Monaco, monospace; color: var(--text-muted); font-size: 11.5px; }
.mono.small { font-size: 11px; }
.ops { display: flex; gap: 6px; }
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}

/* 原图已丢失（悬空引用）：警示色小标签，紧跟在物种名后面 */
.tag-lost {
  margin-left: 6px;
  padding: 1px 6px;
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--text-warning);
  background: var(--bg-badge-warning);
  border: 1px solid var(--color-warning);
  border-radius: 9px;
  white-space: nowrap;
}

.conf-cell { position: relative; display: flex; align-items: center; }
.conf-cell::before {
  content: '';
  position: absolute;
  left: 0;
  width: 58px;
  height: 6px;
  border-radius: 3px;
  background: var(--bg-track);
}
.conf-fill {
  position: relative;
  height: 6px;
  border-radius: 3px;
  max-width: 58px;
  min-width: 2px;
}
.conf-text { margin-left: 64px; font-size: 11.5px; font-variant-numeric: tabular-nums; }

/* ── 预览 ── */
.preview-body { padding: 12px; }
.active-block {
  margin-top: 12px;
  padding: 12px;
  border-radius: 6px;
  background: var(--bg-liability-item);
  border: 1px solid var(--border-subtle);
}
.active-row { display: flex; align-items: center; gap: 8px; }
.active-label { font-size: 11.5px; color: var(--text-muted); }
.active-name { font-size: 13.5px; font-weight: 600; }
.active-conf { margin-left: auto; font-size: 13px; font-weight: 600; font-variant-numeric: tabular-nums; }
.active-tip { margin-top: 7px; font-size: 11.5px; line-height: 1.6; color: var(--text-muted); }
.active-actions { display: flex; gap: 8px; margin-top: 11px; }

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
.btn-mini.primary { background: var(--color-primary); border-color: var(--color-primary); color: var(--text-white); }
.btn-mini.primary:hover:not(:disabled) { filter: brightness(1.1); color: var(--text-white); }
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
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 12px;
  border-top: 1px solid var(--border-admin-table);
}
.pager-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}
.pager-jump {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.pager-text { font-size: 12px; color: var(--text-muted); }
.pager-hint { opacity: 0.75; }
.pager-input {
  width: 72px;
  text-align: center;
}
/* 数字输入框的上下箭头在窄宽度下会挤掉数字，隐藏之；仍可用键盘上下键调值 */
.pager-input::-webkit-outer-spin-button,
.pager-input::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}
.pager-input[type='number'] { -moz-appearance: textfield; appearance: textfield; }

@media (max-width: 1440px) {
  .review-body { grid-template-columns: 1fr; }
  .stat-row { grid-template-columns: repeat(3, 1fr); }
}
</style>
