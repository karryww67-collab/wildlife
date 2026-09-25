<template>
  <div class="detection-image">
    <!-- 工具栏 -->
    <div v-if="showToolbar" class="toolbar">
      <div class="toolbar-left">
        <span class="count" v-if="detections.length">{{ detections.length }} 个检出目标</span>
        <span class="count empty" v-else>未检出目标</span>
      </div>
      <div class="toolbar-right">
        <label class="tool-toggle">
          <input v-model="boxesVisible" type="checkbox" />
          <span>显示检测框</span>
        </label>
        <label class="tool-toggle">
          <input v-model="labelsVisible" type="checkbox" :disabled="!boxesVisible" />
          <span>显示标签</span>
        </label>
      </div>
    </div>

    <!-- 图像 + 检测框覆盖层 -->
    <div class="stage" :style="stageStyle">
      <img
        v-if="src"
        :src="src"
        :alt="alt"
        class="photo"
        :class="{ faded: loading }"
        @load="onLoad"
        @error="onError"
        @click="clearSelection"
      />

      <div v-if="loading" class="placeholder">
        <span class="spinner"></span>
        <span>图像加载中...</span>
      </div>
      <div v-else-if="failed" class="placeholder error">
        <span class="ph-icon">⚠</span>
        <span>图像加载失败</span>
        <span class="ph-path">{{ src }}</span>
      </div>
      <div v-else-if="!src" class="placeholder">
        <span class="ph-icon">🖼</span>
        <span>暂无图像</span>
      </div>

      <!-- 覆盖层：与 img 元素完全同尺寸（inset:0），保证框位置精确 -->
      <div v-if="ready && boxesVisible" class="overlay">
        <div
          v-for="box in boxes"
          :key="boxKey(box)"
          class="bbox"
          :class="{ selected: isSelected(box), dimmed: hasSelection && !isSelected(box) }"
          :style="boxStyle(box)"
          :title="`${box.className} ${(box.confidence * 100).toFixed(1)}%`"
          @click.stop="select(box)"
        >
          <span v-if="labelsVisible" class="bbox-label" :style="{ background: boxColor(box) }">
            {{ box.className }}
            <b>{{ (box.confidence * 100).toFixed(1) }}%</b>
          </span>
        </div>
      </div>
    </div>

    <!-- 图例：按物种聚合 -->
    <ul v-if="showLegend && legend.length" class="legend">
      <li v-for="item in legend" :key="item.name" class="legend-item">
        <span class="legend-dot" :style="{ background: item.color }"></span>
        <span class="legend-name">{{ item.name }}</span>
        <span class="legend-count">×{{ item.count }}</span>
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { colorOfClass, colorOfConfidence, type DetectionBox } from '@/api/index'

const props = withDefaults(
  defineProps<{
    /** 图像地址（原图 / 缩略图 / 标注图均可）。 */
    src?: string
    alt?: string
    /** 检测框列表。 */
    detections?: DetectionBox[]
    /** 当前选中的框（按 id 匹配；无 id 时按索引不选中）。 */
    selectedId?: number | null
    /** 框颜色依据：按物种类别，或按置信度高低。 */
    colorMode?: 'class' | 'confidence'
    showToolbar?: boolean
    showLegend?: boolean
    /** 框上是否默认显示物种标签。 */
    showLabels?: boolean
    /** 框是否默认可见。 */
    showBoxes?: boolean
    /** 舞台最大高度；超高时容器内滚动，不影响框对齐。 */
    maxHeight?: string
  }>(),
  {
    src: '',
    alt: '识别图像',
    detections: () => [],
    selectedId: null,
    colorMode: 'class',
    showToolbar: true,
    showLegend: false,
    showLabels: true,
    showBoxes: true,
    maxHeight: ''
  }
)

const emit = defineEmits<{
  (e: 'select', box: DetectionBox | null): void
  (e: 'loaded', size: { width: number; height: number }): void
  (e: 'error'): void
}>()

const naturalW = ref(0)
const naturalH = ref(0)
const loading = ref(false)
const failed = ref(false)

const boxesVisible = ref(props.showBoxes)
const labelsVisible = ref(props.showLabels)
const innerSelectedId = ref<number | null>(props.selectedId)

watch(
  () => props.src,
  () => {
    naturalW.value = 0
    naturalH.value = 0
    failed.value = false
    loading.value = !!props.src
  },
  { immediate: true }
)

watch(
  () => props.selectedId,
  (v) => {
    innerSelectedId.value = v ?? null
  }
)

const boxes = computed<DetectionBox[]>(() => props.detections ?? [])
const ready = computed(() => naturalW.value > 0 && naturalH.value > 0)
const hasSelection = computed(() => innerSelectedId.value != null)
const stageStyle = computed(() => (props.maxHeight ? { maxHeight: props.maxHeight, overflowY: 'auto' as const } : {}))

function onLoad(e: Event) {
  const img = e.target as HTMLImageElement
  naturalW.value = img.naturalWidth
  naturalH.value = img.naturalHeight
  loading.value = false
  failed.value = false
  emit('loaded', { width: img.naturalWidth, height: img.naturalHeight })
}

function onError() {
  loading.value = false
  failed.value = true
  emit('error')
}

function boxKey(box: DetectionBox): string {
  if (box.id != null) return `id-${box.id}`
  return `${box.classId ?? 'x'}-${box.x1}-${box.y1}-${box.x2}-${box.y2}`
}

function boxColor(box: DetectionBox): string {
  if (props.colorMode === 'confidence') return colorOfConfidence(box.confidence)
  return colorOfClass(box.classId, box.className)
}

function isSelected(box: DetectionBox): boolean {
  if (innerSelectedId.value == null) return false
  if (box.id != null) return box.id === innerSelectedId.value
  return false
}

/**
 * 框位置按原图尺寸换算成百分比。
 * overlay 用 inset:0 与 img 完全重合，所以百分比即等比缩放后的真实位置。
 */
function boxStyle(box: DetectionBox) {
  const w = Math.max(0, box.x2 - box.x1)
  const h = Math.max(0, box.y2 - box.y1)
  return {
    left: `${(box.x1 / naturalW.value) * 100}%`,
    top: `${(box.y1 / naturalH.value) * 100}%`,
    width: `${(w / naturalW.value) * 100}%`,
    height: `${(h / naturalH.value) * 100}%`,
    borderColor: boxColor(box),
    boxShadow: isSelected(box) ? `0 0 0 2px ${boxColor(box)}, 0 0 12px ${boxColor(box)}` : 'none'
  }
}

function select(box: DetectionBox) {
  // 无 id 的框（如实时预览）不上报选中，避免父组件无法定位
  if (box.id == null) return
  innerSelectedId.value = box.id
  emit('select', box)
}

function clearSelection() {
  if (innerSelectedId.value == null) return
  innerSelectedId.value = null
  emit('select', null)
}

/** 按物种聚合，用于图例与"共几种物种"。 */
const legend = computed(() => {
  const map = new Map<string, { name: string; count: number; color: string }>()
  boxes.value.forEach((b) => {
    const existing = map.get(b.className)
    if (existing) {
      existing.count += 1
    } else {
      map.set(b.className, { name: b.className, count: 1, color: boxColor(b) })
    }
  })
  return Array.from(map.values()).sort((a, b) => b.count - a.count)
})

defineExpose({ naturalWidth: naturalW, naturalHeight: naturalH })
</script>

<style scoped>
.detection-image {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--text-muted);
}

.toolbar-right { display: flex; gap: 14px; }

.count { color: var(--text-secondary); }
.count.empty { color: var(--text-dim); }

.tool-toggle {
  display: flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
  user-select: none;
}

.tool-toggle input { accent-color: var(--color-primary); }

.stage {
  position: relative;
  width: 100%;
  line-height: 0;
  background: #050a10;
  border: 1px solid var(--border-primary);
  border-radius: 6px;
  overflow: hidden;
}

.photo {
  display: block;
  width: 100%;
  height: auto;
  transition: opacity 0.2s;
}

.photo.faded { opacity: 0.25; }

.placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-dim);
  line-height: 1.5;
  min-height: 160px;
  padding: 16px;
}

.placeholder.error { color: var(--text-danger); }
.ph-icon { font-size: 22px; }
.ph-path {
  font-size: 11px;
  color: var(--text-dark);
  word-break: break-all;
  text-align: center;
  max-width: 90%;
}

.spinner {
  width: 20px;
  height: 20px;
  border: 2px solid var(--border-admin-input);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.overlay {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.bbox {
  position: absolute;
  border: 2px solid;
  border-radius: 2px;
  box-sizing: border-box;
  cursor: pointer;
  pointer-events: auto;
  transition: opacity 0.2s, box-shadow 0.2s;
}

.bbox:hover { background: rgba(255, 255, 255, 0.06); }
.bbox.dimmed { opacity: 0.35; }

.bbox-label {
  position: absolute;
  left: -2px;
  top: -19px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  line-height: 1.5;
  color: #04121c;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 2px 2px 0 0;
  white-space: nowrap;
  pointer-events: none;
}

.bbox-label b { font-weight: 500; opacity: 0.85; }

.legend {
  list-style: none;
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  font-size: 12px;
  color: var(--text-muted);
}

.legend-item { display: flex; align-items: center; gap: 5px; }
.legend-dot { width: 8px; height: 8px; border-radius: 2px; flex-shrink: 0; }
.legend-name { color: var(--text-secondary); }
.legend-count { color: var(--text-dim); }
</style>
