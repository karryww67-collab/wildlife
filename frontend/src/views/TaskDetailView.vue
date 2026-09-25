<template>
  <div class="page">
    <!-- 页头 -->
    <header class="page-head">
      <div class="head-left">
        <button class="btn-back" @click="router.push('/tasks')">← 返回任务列表</button>
        <h1 class="page-title">
          {{ task?.taskName || '识别任务详情' }}
          <span v-if="task" class="id-tag mono">#{{ task.id }}</span>
        </h1>
        <p class="page-sub">查看本批图像的识别进度、逐张识别结果，并对结果进行人工复核</p>
      </div>
      <div class="head-actions">
        <button class="btn-ghost" :disabled="loading" @click="reloadAll">刷新</button>
        <button class="btn-ghost" :disabled="!task" @click="doExport">导出 CSV</button>
        <button
          v-if="task && (task.status === 'PENDING' || task.status === 'PROCESSING')"
          class="btn-ghost"
          :disabled="busy"
          @click="doCancel"
        >取消任务</button>
        <button
          v-else-if="task && (task.status === 'FAILED' || task.status === 'CANCELED')"
          class="btn-primary"
          :disabled="busy"
          @click="doRetry"
        >重新识别</button>
      </div>
    </header>

    <div v-if="!task && loading" class="state">
      <span class="spinner"></span>
      <span>任务信息加载中...</span>
    </div>
    <div v-else-if="!task" class="state">
      <span class="state-icon">⚠</span>
      <span>任务不存在或已被删除</span>
    </div>

    <template v-else>
      <!-- 进度（内部自行订阅 /ws/recognition 实时更新） -->
      <TaskProgress
        :task-id="task.id"
        :total-count="task.totalCount ?? 0"
        :processed-count="task.processedCount ?? 0"
        :success-count="task.successCount ?? 0"
        :failed-count="task.failedCount ?? 0"
        :progress="task.progress ?? 0"
        :status="task.status"
        @update="onProgressUpdate"
        @completed="onCompleted"
      />

      <!-- 任务信息 -->
      <section class="card info-card">
        <div class="info-grid">
          <div class="info-item">
            <span class="info-label">识别模型</span>
            <span class="info-value">{{ modelLabel }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">图像总数</span>
            <span class="info-value">{{ task.totalCount ?? 0 }} 张</span>
          </div>
          <div class="info-item">
            <span class="info-label">成功 / 失败</span>
            <span class="info-value">
              <b class="ok">{{ task.successCount ?? 0 }}</b> /
              <b class="bad">{{ task.failedCount ?? 0 }}</b>
            </span>
          </div>
          <div class="info-item">
            <span class="info-label">创建时间</span>
            <span class="info-value">{{ formatTime(task.createTime) }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">开始时间</span>
            <span class="info-value">{{ formatTime(task.startTime) }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">完成时间</span>
            <span class="info-value">{{ formatTime(task.finishTime) }}</span>
          </div>
        </div>
      </section>

      <!-- 主体：图像清单 + 结果查看 -->
      <div class="detail-body">
        <!-- 左：图像清单 -->
        <section class="card img-panel">
          <div class="card-head">
            <span>图像清单</span>
            <span class="head-meta">共 {{ imageTotal }} 张</span>
          </div>

          <div v-if="loadingImages" class="state small">
            <span class="spinner"></span>
            <span>图像加载中...</span>
          </div>
          <div v-else-if="!images.length" class="state small">该任务下暂无图像</div>
          <ul v-else class="img-grid">
            <li
              v-for="img in images"
              :key="img.id"
              class="img-item"
              :class="{ on: selectedImage?.id === img.id }"
              @click="selectImage(img)"
            >
              <img
                v-if="thumbnailUrls[img.id]"
                :src="thumbnailUrls[img.id]"
                :alt="img.fileName"
                loading="lazy"
              />
              <span v-else class="image-placeholder">加载中...</span>
              <span class="img-name" :title="img.fileName">{{ img.fileName }}</span>
              <span class="badge tiny" :class="imageStatusClass(img.status)">
                {{ IMAGE_STATUS_LABEL[img.status] || img.status }}
              </span>
            </li>
          </ul>

          <footer v-if="imageTotal > imageSize" class="pager">
            <button class="btn-mini" :disabled="imagePage <= 1" @click="turnImagePage(-1)">上一页</button>
            <span class="pager-text">{{ imagePage }} / {{ Math.ceil(imageTotal / imageSize) }}</span>
            <button
              class="btn-mini"
              :disabled="imagePage * imageSize >= imageTotal"
              @click="turnImagePage(1)"
            >下一页</button>
          </footer>
        </section>

        <!-- 右：结果查看 -->
        <section class="card result-panel">
          <div class="card-head">
            <span>识别结果</span>
            <span class="head-meta">
              {{ selectedImage ? selectedImage.fileName : '请在左侧选择一张图像' }}
            </span>
          </div>

          <div v-if="!selectedImage" class="state">
            <span class="state-icon">🖼</span>
            <span>选择左侧任意图像查看叠加了检测框的识别结果</span>
          </div>

          <div v-else class="result-body">
            <div class="toolbar">
              <div class="tool-left">
                <span class="hit-count" v-if="detections.length">
                  {{ detections.length }} 个检出目标 · {{ speciesCount }} 种
                </span>
                <span class="hit-count empty" v-else>该图像未检出任何目标</span>
              </div>
              <div class="tool-right">
                <button class="btn-mini" @click="openRawImage">查看原图</button>
                <button
                  class="btn-mini"
                  :disabled="!pendingDetections.length"
                  @click="openReviewBatch"
                >批量复核待定项{{ pendingDetections.length ? ` (${pendingDetections.length})` : '' }}</button>
              </div>
            </div>

            <DetectionImage
              :src="rawUrl"
              :alt="selectedImage.fileName"
              :detections="detections"
              :selected-id="selectedResultId"
              :show-legend="detections.length > 1"
              max-height="46vh"
              @select="onBoxSelect"
            />

            <p v-if="selectedImage.status === 'FAILED' && selectedImage.errorMessage" class="error-line">
              识别失败：{{ selectedImage.errorMessage }}
            </p>

            <div v-if="loadingDetections" class="state small">
              <span class="spinner"></span>
              <span>识别结果加载中...</span>
            </div>

            <table v-else-if="detections.length" class="table">
              <thead>
                <tr>
                  <th class="col-name">物种类别</th>
                  <th class="col-conf">置信度</th>
                  <th>检测框 (x1,y1 → x2,y2)</th>
                  <th class="col-review">复核状态</th>
                  <th class="col-ops">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="item in detections"
                  :key="item.id"
                  :class="{ active: item.id === selectedResultId }"
                  @click="selectedResultId = item.id"
                >
                  <td>
                    <span class="dot" :style="{ background: colorOfClass(item.classId, item.className) }"></span>
                    {{ item.className }}
                  </td>
                  <td>
                    <div class="conf-cell">
                      <span class="conf-fill" :style="{ width: confPercent(item) + '%', background: colorOfConfidence(item.confidence) }"></span>
                      <span class="conf-text">{{ (item.confidence * 100).toFixed(1) }}%</span>
                    </div>
                  </td>
                  <td class="mono">{{ item.x1 }},{{ item.y1 }} → {{ item.x2 }},{{ item.y2 }}</td>
                  <td>
                    <span class="badge tiny" :class="reviewClass(item.reviewStatus)">
                      {{ REVIEW_STATUS_LABEL[item.reviewStatus] || item.reviewStatus }}
                    </span>
                  </td>
                  <td>
                    <button class="btn-mini" @click.stop="openReviewSingle(item)">复核</button>
                  </td>
                </tr>
              </tbody>
            </table>

            <p v-else class="empty-line">该图像未检出任何目标，可尝试更换模型版本后重新识别</p>
          </div>
        </section>
      </div>
    </template>

    <!-- 复核弹窗 -->
    <ReviewDialog
      v-model:visible="reviewVisible"
      :result="reviewTarget"
      :results="reviewBatch"
      :image="selectedImage"
      :model-id="task?.modelId ?? 0"
      @reviewed="onReviewed"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TaskProgress from '@/components/TaskProgress.vue'
import DetectionImage from '@/components/DetectionImage.vue'
import ReviewDialog from '@/components/ReviewDialog.vue'
import {
  cancelTask,
  colorOfClass,
  colorOfConfidence,
  exportResultsCsv,
  getImageResults,
  getModel,
  getTask,
  IMAGE_STATUS_LABEL,
  listImages,
  retryTask,
  REVIEW_STATUS_LABEL,
  type DetectionBox,
  type DetectionResult,
  type ImageStatus,
  type RecognitionImage,
  type RecognitionTask,
  type ReviewAction
} from '@/api/index'
import { loadProtectedImage, revokeImageUrl } from '@/utils/imageUrl'

const route = useRoute()
const router = useRouter()

const taskId = computed(() => Number(route.params.id))

const loading = ref(false)
const busy = ref(false)
const task = ref<RecognitionTask | null>(null)
const modelVersion = ref('')

const images = ref<RecognitionImage[]>([])
const loadingImages = ref(false)
const imageTotal = ref(0)
const imagePage = ref(1)
const imageSize = 24

/**
 * 图像清单的缩略图 ObjectURL，按图像 id 登记。
 * 清单只取 thumbnail（原图只在右侧预览取），一页 24 张；翻页 / 换任务时释放已离开的。
 */
const thumbnailUrls = ref<Record<number, string>>({})

/** 取缩略图：与取原图同一条鉴权链路（axios 自动带 X-Auth-Token → Blob → ObjectURL）。 */
async function loadThumbnail(imageId: number) {
  if (thumbnailUrls.value[imageId]) return
  try {
    thumbnailUrls.value[imageId] = await loadProtectedImage(imageId, 'thumbnail')
  } catch (error) {
    console.error(`加载图片 ${imageId} 缩略图失败:`, error)
  }
}

/** 只释放已不在当前页的缩略图地址（翻页 / 换任务 / 刷新时调用）。 */
function syncThumbnails(ids: number[]) {
  const keep = new Set(ids)
  Object.keys(thumbnailUrls.value).forEach((key) => {
    const id = Number(key)
    if (keep.has(id)) return
    revokeImageUrl(thumbnailUrls.value[id])
    delete thumbnailUrls.value[id]
  })
}

/** 释放全部缩略图地址。 */
function cleanupThumbnails() {
  Object.values(thumbnailUrls.value).forEach((url) => {
    revokeImageUrl(url)
  })
  thumbnailUrls.value = {}
}

const selectedImage = ref<RecognitionImage | null>(null)
const detections = ref<DetectionResult[]>([])

/**
 * 当前预览图的 ObjectURL。
 * 详情/预览用 raw、图像清单用 thumbnail —— 不给清单里每张图都拉原图。
 */
const rawUrl = ref('')

/** 释放当前原图地址（切图 / 清空选择 / 组件卸载时调用）。 */
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
    if (selectedImage.value?.id !== imageId) {
      revokeImageUrl(url)
      return
    }
    rawUrl.value = url
  } catch (error) {
    console.error('加载原图失败:', error)
  }
}

// 选中项变了才换原图：翻页自动选中、切换任务清空、点选另一张都会走到这里
watch(
  () => selectedImage.value?.id ?? 0,
  (id) => {
    if (id) loadRawImage(id)
    else releaseRaw()
  }
)
const loadingDetections = ref(false)
const selectedResultId = ref<number | null>(null)

const reviewVisible = ref(false)
const reviewTarget = ref<DetectionResult | null>(null)
const reviewBatch = ref<DetectionResult[]>([])

const modelLabel = computed(() => {
  if (!task.value?.modelId) return '当前启用模型'
  return modelVersion.value || `模型 #${task.value.modelId}`
})

const speciesCount = computed(() => new Set(detections.value.map((d) => d.className)).size)

const pendingDetections = computed(() =>
  detections.value.filter((d) => (d.reviewStatus ?? 'PENDING') === 'PENDING')
)

function formatTime(value?: string): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

function imageStatusClass(status: ImageStatus): string {
  return `st-${String(status || 'WAITING').toLowerCase()}`
}

function reviewClass(status: string): string {
  return `rv-${String(status || 'PENDING').toLowerCase()}`
}

function confPercent(item: DetectionResult): number {
  return Math.max(0, Math.min(100, item.confidence * 100))
}

async function loadTask() {
  loading.value = true
  try {
    const res = await getTask(taskId.value)
    task.value = res.data ?? null
    if (task.value?.modelId) {
      loadModelLabel(task.value.modelId)
    }
  } catch {
    task.value = null
  } finally {
    loading.value = false
  }
}

async function loadModelLabel(modelId: number) {
  try {
    const res = await getModel(modelId)
    modelVersion.value = res.data?.version ?? ''
  } catch {
    modelVersion.value = ''
  }
}

async function loadImages() {
  loadingImages.value = true
  try {
    const res = await listImages({
      taskId: taskId.value,
      page: imagePage.value,
      size: imageSize
    })
    images.value = res.data?.list ?? []
    imageTotal.value = res.data?.total ?? 0
    // 清单换了：先释放已离开当前页的缩略图地址，再并发取当前页的（一页 24 张，不会一次拉十万张）
    syncThumbnails(images.value.map((img) => img.id))
    void Promise.all(images.value.map((img) => loadThumbnail(img.id)))
    if (!selectorStillValid()) {
      selectImage(images.value[0] ?? null)
    }
  } catch {
    images.value = []
    imageTotal.value = 0
    cleanupThumbnails()
  } finally {
    loadingImages.value = false
  }
}

function selectorStillValid(): boolean {
  if (!selectedImage.value) return images.value.length === 0
  return images.value.some((img) => img.id === selectedImage.value?.id)
}

async function selectImage(image: RecognitionImage | null) {
  selectedImage.value = image
  selectedResultId.value = null
  detections.value = []
  if (!image) return
  loadingDetections.value = true
  try {
    const res = await getImageResults(image.id)
    detections.value = res.data ?? []
  } catch {
    detections.value = []
  } finally {
    loadingDetections.value = false
  }
}

function turnImagePage(delta: number) {
  imagePage.value = Math.max(1, imagePage.value + delta)
  loadImages()
}

function onProgressUpdate(payload: {
  totalCount: number
  processedCount: number
  successCount: number
  failedCount: number
  progress: number
  status: RecognitionTask['status']
}) {
  if (!task.value) return
  task.value = {
    ...task.value,
    totalCount: payload.totalCount,
    processedCount: payload.processedCount,
    successCount: payload.successCount,
    failedCount: payload.failedCount,
    progress: payload.progress,
    status: payload.status
  }
}

/** 任务跑完后把刚产生的结果拉回来（当前页图像状态与检测框都会变）。 */
function onCompleted() {
  loadTask()
  loadImages()
  if (selectedImage.value) selectImage(selectedImage.value)
}

function onBoxSelect(box: DetectionBox | null) {
  selectedResultId.value = box?.id ?? null
}

function openRawImage() {
  // 复用已经取回的原图 blob：不再发第二次请求，也就不会再 401
  if (!rawUrl.value) return
  window.open(rawUrl.value, '_blank')
}

function openReviewSingle(item: DetectionResult) {
  reviewBatch.value = []
  reviewTarget.value = item
  reviewVisible.value = true
}

function openReviewBatch() {
  if (!pendingDetections.value.length) return
  reviewTarget.value = null
  reviewBatch.value = [...pendingDetections.value]
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
  detections.value = detections.value.map((item) => {
    if (!payload.resultIds.includes(item.id)) return item
    return {
      ...item,
      reviewStatus: next,
      className: payload.action === 'CORRECT' && payload.className ? payload.className : item.className
    }
  })
}

async function doCancel() {
  if (!task.value) return
  if (!window.confirm(`确定取消任务 #${task.value.id}？`)) return
  busy.value = true
  try {
    await cancelTask(task.value.id)
    await loadTask()
  } catch (err: any) {
    window.alert(`取消失败：${err?.response?.data?.error || err?.message || '未知错误'}`)
  } finally {
    busy.value = false
  }
}

async function doRetry() {
  if (!task.value) return
  busy.value = true
  try {
    const res = await retryTask(task.value.id)
    const newId = res.data?.id
    if (newId && newId !== task.value.id) {
      // ⑨-C：retry 派生新任务（原任务保持终态），跳到新任务页。
      // 本视图已有 watch(taskId) → 路由参数一变会自动 loadTask() + loadImages()，无需手动刷新。
      await router.push(`/tasks/${newId}`)
    } else {
      // 幂等命中已有派生任务时理论上不会走到这里；保底走原刷新路径
      await loadTask()
      await loadImages()
    }
  } catch (err: any) {
    window.alert(`重试失败：${err?.response?.data?.error || err?.message || '未知错误'}`)
  } finally {
    busy.value = false
  }
}

async function doExport() {
  try {
    await exportResultsCsv({ taskId: taskId.value })
  } catch (err: any) {
    window.alert(`导出失败：${err?.response?.data?.error || err?.message || '未知错误'}`)
  }
}

function reloadAll() {
  loadTask()
  loadImages()
  if (selectedImage.value) selectImage(selectedImage.value)
}

// 从其它页面跳到另一个任务详情时重新加载
watch(taskId, () => {
  if (!taskId.value) return
  imagePage.value = 1
  selectedImage.value = null
  detections.value = []
  loadTask()
  loadImages()
})

onMounted(() => {
  if (!taskId.value) return
  loadTask()
  loadImages()
})

onBeforeUnmount(() => {
  releaseRaw()
  cleanupThumbnails()
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
.btn-back {
  background: none;
  border: none;
  padding: 0;
  margin-bottom: 6px;
  color: var(--text-muted);
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
}
.btn-back:hover { color: var(--color-primary); }
.page-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 19px;
  font-weight: 700;
}
.id-tag {
  padding: 2px 8px;
  font-size: 12px;
  font-weight: 400;
  border-radius: 10px;
  background: var(--bg-badge-success);
  color: var(--color-primary);
}
.page-sub {
  margin-top: 5px;
  font-size: 12px;
  color: var(--text-muted);
}
.head-actions { display: flex; gap: 8px; flex-shrink: 0; }

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

/* ── 信息区 ── */
.info-card { margin: 14px 0; }
.info-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
}
.info-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 14px 16px;
  border-right: 1px solid var(--border-divider);
}
.info-item:last-child { border-right: none; }
.info-label { font-size: 11.5px; color: var(--text-muted); }
.info-value { font-size: 13px; color: var(--text-secondary); }
.info-value .ok { color: var(--color-success); }
.info-value .bad { color: var(--color-danger); }

/* ── 主体 ── */
.detail-body {
  display: grid;
  grid-template-columns: 340px 1fr;
  gap: 14px;
  align-items: start;
}

/* 图像清单 */
.img-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  gap: 8px;
  padding: 12px;
  list-style: none;
  max-height: 620px;
  overflow-y: auto;
}
.img-item {
  position: relative;
  border: 1px solid var(--border-admin-input);
  border-radius: 6px;
  overflow: hidden;
  cursor: pointer;
  background: var(--bg-admin-input);
  transition: border-color 0.2s;
}
.img-item:hover { border-color: var(--color-primary); }
.img-item.on { border-color: var(--color-primary); box-shadow: 0 0 0 1px var(--color-primary) inset; }
.img-item img {
  display: block;
  width: 100%;
  height: 64px;
  object-fit: cover;
  background: var(--bg-track);
}
/* 缩略图未就绪时的占位：与 .img-item img 同高同底色，避免网格跳动 */
.image-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 64px;
  font-size: 10.5px;
  color: var(--text-muted);
  background: var(--bg-track);
}
.img-name {
  display: block;
  padding: 4px 6px 0;
  font-size: 10.5px;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.img-item .badge { margin: 3px 6px 6px; }

/* 结果面板 */
.result-body { padding: 12px 16px 16px; }
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.tool-left { font-size: 12px; color: var(--text-secondary); }
.tool-right { display: flex; gap: 6px; }
.hit-count { color: var(--text-secondary); }
.hit-count.empty { color: var(--text-muted); }

/* ── 表格 ── */
.table {
  width: 100%;
  margin-top: 12px;
  border-collapse: collapse;
  font-size: 12.5px;
}
.table th {
  padding: 9px 10px;
  text-align: left;
  font-weight: 500;
  color: var(--text-muted);
  background: var(--bg-admin-input);
  border-bottom: 1px solid var(--border-admin-table);
}
.table td {
  padding: 9px 10px;
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border-admin-table);
}
.table tbody tr { cursor: pointer; }
.table tbody tr:hover { background: var(--bg-card-hover); }
.table tbody tr.active { background: var(--bg-card-active); }
.col-name { width: 150px; }
.col-conf { width: 150px; }
.col-review { width: 96px; }
.col-ops { width: 80px; }
.mono { font-family: Consolas, Monaco, monospace; font-size: 11.5px; color: var(--text-muted); }
.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}

.conf-cell {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
}
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
.conf-text {
  margin-left: 64px;
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
}

/* ── 徽标 ── */
.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 9px;
  font-size: 11px;
  white-space: nowrap;
  border: 1px solid transparent;
}
.badge.tiny { font-size: 10.5px; padding: 1px 7px; }
.st-waiting, .rv-pending { background: var(--bg-badge-warning); color: var(--text-warning); }
.st-processing { background: var(--bg-badge-success); color: var(--color-primary); }
.st-success, .rv-confirmed { background: var(--bg-badge-success); color: var(--color-success); }
.st-failed { background: var(--bg-badge-danger); color: var(--text-danger); }
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
}
.state.small { padding: 22px 16px; }
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

.empty-line {
  padding: 22px 0;
  text-align: center;
  font-size: 12.5px;
  color: var(--text-muted);
}
.error-line {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 4px;
  font-size: 12px;
  background: var(--bg-badge-danger);
  color: var(--text-danger);
}

/* ── 分页 ── */
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 10px;
  border-top: 1px solid var(--border-admin-table);
}
.pager-text { font-size: 12px; color: var(--text-muted); }

@media (max-width: 1280px) {
  .detail-body { grid-template-columns: 1fr; }
  .info-grid { grid-template-columns: repeat(3, 1fr); }
}
</style>
