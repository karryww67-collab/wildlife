<template>
  <article class="result-card" :class="{ selected: isCardSelected }">
    <!-- 头部 -->
    <header class="card-head">
      <div class="head-left">
        <span class="file-name" :title="image?.fileName">{{ image?.fileName || '未命名图像' }}</span>
        <span v-if="image" class="status-badge" :class="imageStatusKey">
          {{ imageStatusLabel }}
        </span>
      </div>
      <div class="head-right">
        <span v-if="detections.length" class="hit-count">
          {{ detections.length }} 个目标 · {{ speciesCount }} 种
        </span>
        <span v-else class="hit-count empty">无检出</span>
      </div>
    </header>

    <!-- 图像 + 框 -->
    <DetectionImage
      :src="resolvedSrc"
      :alt="image?.fileName"
      :detections="detections"
      :selected-id="selectedId"
      :show-toolbar="false"
      :show-legend="detections.length > 1"
      color-mode="class"
      :max-height="imageMaxHeight"
      @select="onBoxSelect"
    />

    <!-- 失败原因 -->
    <p v-if="image?.status === 'FAILED' && image?.errorMessage" class="error-line">
      识别失败：{{ image.errorMessage }}
    </p>

    <!-- 识别结果列表 -->
    <ul v-if="detections.length" class="det-list">
      <li
        v-for="item in detections"
        :key="item.id"
        class="det-row"
        :class="{ active: item.id === selectedId }"
        @click="emit('select', item)"
      >
        <span class="dot" :style="{ background: colorOfClass(item.classId, item.className) }"></span>

        <span class="det-name" :title="item.className">{{ item.className }}</span>

        <span class="conf-bar">
          <span class="conf-fill" :style="{ width: confPercent(item) + '%', background: colorOfConfidence(item.confidence) }"></span>
        </span>
        <span class="conf-text">{{ (item.confidence * 100).toFixed(1) }}%</span>

        <span class="bbox-text">{{ item.x1 }},{{ item.y1 }} → {{ item.x2 }},{{ item.y2 }}</span>

        <span class="review-badge" :class="reviewKey(item)">{{ reviewLabel(item) }}</span>

        <button
          v-if="showActions"
          class="btn-review"
          :title="'复核：' + item.className"
          @click.stop="emit('review', item)"
        >复核</button>
      </li>
    </ul>

    <p v-else-if="!loading" class="empty-line">该图像未检出任何目标</p>

    <!-- 底部操作 -->
    <footer v-if="showActions" class="card-foot">
      <button class="btn-mini" @click="viewImage">查看原图</button>
      <button
        class="btn-mini"
        :disabled="!pendingCount"
        @click="emit('review-all', detections.filter((d) => d.reviewStatus === 'PENDING'))"
      >
        复核待定项{{ pendingCount ? ` (${pendingCount})` : '' }}
      </button>
    </footer>
  </article>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import DetectionImage from './DetectionImage.vue'
import {
  colorOfClass,
  colorOfConfidence,
  IMAGE_STATUS_LABEL,
  REVIEW_STATUS_LABEL,
  type DetectionBox,
  type DetectionResult,
  type RecognitionImage
} from '@/api/index'
import { loadProtectedImage, revokeImageUrl } from '@/utils/imageUrl'

const props = withDefaults(
  defineProps<{
    image: RecognitionImage
    detections?: DetectionResult[]
    selectedId?: number | null
    /**
     * 父组件明确给定的图像地址（本地 / `blob:` / `data:` / `http`）。
     * 留空则按 `image.id` 走受保护通道；传 `/api/images/**` 会被忽略，同样改走受保护通道。
     */
    thumbnailSrc?: string
    loading?: boolean
    showActions?: boolean
    /** 卡片内图像最大高度。 */
    imageMaxHeight?: string
  }>(),
  {
    detections: () => [],
    selectedId: null,
    thumbnailSrc: '',
    loading: false,
    showActions: true,
    imageMaxHeight: '260px'
  }
)

const emit = defineEmits<{
  (e: 'select', result: DetectionResult | null): void
  (e: 'review', result: DetectionResult): void
  (e: 'review-all', results: DetectionResult[]): void
  (e: 'view', image: RecognitionImage): void
}>()

/**
 * 卡片图像的最终地址。
 * 不能直接把 `/api/images/{id}/thumbnail` 放上去 —— `<img>` 由浏览器自发请求，
 * 带不上 `X-Auth-Token`，后端一律 401。故异步解析：axios 取 Blob → ObjectURL。
 */
const resolvedSrc = ref('')

/** 释放上一个地址；只回收 `blob:` 的，父组件给的本地/data/http 地址不归本组件管。 */
function releaseResolvedSrc() {
  if (resolvedSrc.value.startsWith('blob:')) revokeImageUrl(resolvedSrc.value)
  resolvedSrc.value = ''
}

async function resolveImage() {
  releaseResolvedSrc()

  // 父组件明确传入的本地 / blob: / data: / http 地址，直接使用；
  // 但 `/api/images/**` 是受鉴权路径，不能交给浏览器自发请求，继续往下走受保护通道。
  if (props.thumbnailSrc && !props.thumbnailSrc.startsWith('/api/images/')) {
    resolvedSrc.value = props.thumbnailSrc
    return
  }

  const imageId = props.image?.id
  if (!imageId) return

  try {
    const url = await loadProtectedImage(imageId, 'thumbnail')
    // 期间可能已切到别的图：丢弃迟到的响应，避免显示错图
    if (props.image?.id !== imageId) {
      revokeImageUrl(url)
      return
    }
    resolvedSrc.value = url
  } catch (error) {
    console.error('加载结果缩略图失败:', error)
  }
}

// image.id / thumbnailSrc 任一变化就重新解析（immediate 顺带覆盖首次挂载）
watch(() => [props.image?.id, props.thumbnailSrc], resolveImage, { immediate: true })

onBeforeUnmount(releaseResolvedSrc)

const imageStatusKey = computed(() => (props.image?.status ?? 'WAITING').toLowerCase())
const imageStatusLabel = computed(() => IMAGE_STATUS_LABEL[props.image?.status ?? 'WAITING'])

const isCardSelected = computed(
  () => props.selectedId != null && props.detections.some((d) => d.id === props.selectedId)
)

const speciesCount = computed(() => new Set(props.detections.map((d) => d.className)).size)

const pendingCount = computed(() => props.detections.filter((d) => d.reviewStatus === 'PENDING').length)

function confPercent(item: DetectionResult): number {
  return Math.max(0, Math.min(100, item.confidence * 100))
}

function reviewKey(item: DetectionResult): string {
  return (item.reviewStatus ?? 'PENDING').toLowerCase()
}

function reviewLabel(item: DetectionResult): string {
  return REVIEW_STATUS_LABEL[item.reviewStatus ?? 'PENDING']
}

function onBoxSelect(box: DetectionBox | null) {
  if (!box) {
    emit('select', null)
    return
  }
  const matched = props.detections.find((d) => d.id === box.id) ?? null
  emit('select', matched)
}

function viewImage() {
  if (!props.image) return
  emit('view', props.image)
}
</script>

<style scoped>
.result-card {
  background: var(--bg-admin-card);
  border: 1px solid var(--border-primary);
  border-radius: 10px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.result-card.selected {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 1px var(--color-primary-glow);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.head-left { display: flex; align-items: center; gap: 8px; min-width: 0; }

.file-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 220px;
}

.status-badge { font-size: 11px; padding: 2px 8px; border-radius: 10px; flex-shrink: 0; }
.status-badge.waiting { background: rgba(120, 144, 156, 0.2); color: var(--text-muted); }
.status-badge.processing { background: rgba(0, 229, 255, 0.15); color: var(--color-primary); }
.status-badge.success { background: rgba(76, 175, 80, 0.18); color: #81c784; }
.status-badge.failed { background: rgba(244, 67, 54, 0.18); color: var(--text-danger); }

.hit-count { font-size: 12px; color: var(--text-secondary); flex-shrink: 0; }
.hit-count.empty { color: var(--text-dim); }

.error-line {
  font-size: 12px;
  color: var(--text-danger);
  background: rgba(198, 40, 40, 0.1);
  border: 1px solid rgba(198, 40, 40, 0.35);
  border-radius: 4px;
  padding: 6px 10px;
}

.det-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 200px;
  overflow-y: auto;
}

.det-row {
  display: grid;
  grid-template-columns: 8px minmax(52px, 1fr) 68px 44px minmax(96px, 1.2fr) auto auto;
  align-items: center;
  gap: 8px;
  padding: 5px 6px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  transition: background 0.15s;
}

.det-row:hover { background: var(--bg-card-hover); }
.det-row.active { background: var(--bg-card-active); }

.dot { width: 8px; height: 8px; border-radius: 2px; }

.det-name {
  color: var(--text-primary);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conf-bar {
  height: 5px;
  background: var(--bg-admin-input);
  border-radius: 3px;
  overflow: hidden;
}

.conf-fill { display: block; height: 100%; border-radius: 3px; }

.conf-text {
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.bbox-text {
  color: var(--text-dim);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.review-badge { font-size: 11px; padding: 1px 7px; border-radius: 9px; white-space: nowrap; }
.review-badge.pending { background: rgba(255, 152, 0, 0.15); color: var(--text-warning); }
.review-badge.confirmed { background: rgba(76, 175, 80, 0.15); color: #81c784; }
.review-badge.corrected { background: rgba(124, 77, 255, 0.18); color: var(--text-purple); }
.review-badge.rejected { background: rgba(120, 144, 156, 0.18); color: var(--text-dim); }

.btn-review {
  padding: 2px 9px;
  font-size: 11px;
  background: transparent;
  color: var(--color-primary);
  border: 1px solid var(--border-accent);
  border-radius: 4px;
  cursor: pointer;
}
.btn-review:hover { background: var(--bg-card-active); }

.empty-line {
  font-size: 12px;
  color: var(--text-dim);
  padding: 6px 0;
}

.card-foot {
  display: flex;
  gap: 8px;
  padding-top: 10px;
  border-top: 1px solid var(--border-primary);
}

.btn-mini {
  padding: 4px 11px;
  font-size: 12px;
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border-admin-input);
  border-radius: 4px;
  cursor: pointer;
}
.btn-mini:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
.btn-mini:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
