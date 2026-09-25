<template>
  <Teleport to="body">
    <div v-if="visible" class="overlay" @click.self="onBackdrop">
      <div class="dialog" role="dialog" aria-modal="true">
        <header class="dialog-head">
          <div class="title-wrap">
            <span class="title">人工复核</span>
            <span v-if="isBatch" class="batch-tag">批量 {{ targets.length }} 项</span>
          </div>
          <button class="btn-close" :disabled="submitting" @click="close">×</button>
        </header>

        <div class="dialog-body">
          <!-- 左：图像 + 框 -->
          <div class="pane pane-left">
            <DetectionImage
              v-if="displaySrc"
              :src="displaySrc"
              :detections="boxList"
              :selected-id="primary?.id ?? null"
              :show-toolbar="false"
              :show-legend="false"
              :show-labels="true"
              max-height="52vh"
            />
            <div v-else class="no-image">{{ previewPlaceholder }}</div>
          </div>

          <!-- 右：复核表单 -->
          <div class="pane pane-right">
            <section class="block">
              <h4 class="block-title">原识别结果</h4>
              <div v-if="isBatch" class="batch-list">
                <div v-for="item in targets" :key="item.id" class="batch-item">
                  <span class="dot" :style="{ background: colorOfClass(item.classId, item.className) }"></span>
                  <span class="bi-name">{{ item.className }}</span>
                  <span class="bi-conf">{{ (item.confidence * 100).toFixed(1) }}%</span>
                </div>
              </div>
              <div v-else-if="primary" class="single-info">
                <div class="info-row">
                  <span class="info-label">物种类别</span>
                  <span class="info-value">{{ primary.className }}</span>
                </div>
                <div class="info-row">
                  <span class="info-label">置信度</span>
                  <span class="info-value" :style="{ color: colorOfConfidence(primary.confidence) }">
                    {{ (primary.confidence * 100).toFixed(1) }}%
                  </span>
                </div>
                <div class="info-row">
                  <span class="info-label">检测框</span>
                  <span class="info-value mono">
                    {{ primary.x1 }},{{ primary.y1 }} → {{ primary.x2 }},{{ primary.y2 }}
                  </span>
                </div>
                <div v-if="image" class="info-row">
                  <span class="info-label">所属图像</span>
                  <span class="info-value" :title="image.fileName">{{ image.fileName }}</span>
                </div>
              </div>
              <p v-else class="tip">没有待复核的结果</p>
            </section>

            <section class="block">
              <h4 class="block-title">复核动作</h4>
              <div class="action-group">
                <button
                  v-for="opt in actionOptions"
                  :key="opt.value"
                  class="action-btn"
                  :class="[opt.value.toLowerCase(), { active: action === opt.value }]"
                  :disabled="submitting"
                  @click="action = opt.value"
                >
                  <span class="ab-icon">{{ opt.icon }}</span>
                  <span class="ab-text">
                    <b>{{ opt.label }}</b>
                    <em>{{ opt.hint }}</em>
                  </span>
                </button>
              </div>
            </section>

            <section v-if="action === 'CORRECT'" class="block">
              <h4 class="block-title">
                修正为
                <span class="required">*</span>
              </h4>
              <input
                v-model="correctedClass"
                class="class-input"
                placeholder="输入或从下方选择物种类别"
                :disabled="submitting"
              />
              <div v-if="filteredOptions.length" class="class-options">
                <button
                  v-for="name in filteredOptions"
                  :key="name"
                  class="class-option"
                  :class="{ active: name === correctedClass }"
                  :disabled="submitting"
                  @click="correctedClass = name"
                >{{ name }}</button>
              </div>
              <p v-else class="tip">无可选类别（可在模型 classConfig 中配置）</p>
            </section>

            <section class="block">
              <h4 class="block-title">备注</h4>
              <textarea
                v-model="remark"
                class="remark"
                rows="3"
                placeholder="可填写判断依据，如：夜间红外图像，体型与毛色更接近小麂"
                :disabled="submitting"
              ></textarea>
            </section>

            <div v-if="errorMsg" class="message error">{{ errorMsg }}</div>
            <div v-if="successMsg" class="message success">{{ successMsg }}</div>
          </div>
        </div>

        <footer class="dialog-foot">
          <span class="foot-hint">{{ actionHint }}</span>
          <div class="foot-actions">
            <button class="btn-ghost" :disabled="submitting" @click="close">取消</button>
            <button class="btn-primary" :disabled="!canSubmit" @click="submit">
              {{ submitting ? '提交中...' : isBatch ? `提交复核 (${targets.length})` : '提交复核' }}
            </button>
          </div>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import DetectionImage from './DetectionImage.vue'
import {
  colorOfClass,
  colorOfConfidence,
  getModel,
  parseClassConfig,
  submitBatchReview,
  submitReview,
  type DetectionBox,
  type DetectionResult,
  type RecognitionImage,
  type ReviewAction
} from '@/api/index'
import { loadProtectedImage, revokeImageUrl } from '@/utils/imageUrl'

const props = withDefaults(
  defineProps<{
    visible: boolean
    /** 单条复核对象。 */
    result?: DetectionResult | null
    /** 批量复核对象；给了它就走批量通道。 */
    results?: DetectionResult[]
    /** 结果所属图像（用于预览与信息展示）。 */
    image?: RecognitionImage | null
    /** 直接指定预览图地址；本地/blob/data/http 一律原样使用。缺省时按鉴权方式拉取原图。 */
    imageSrc?: string
    /** 可修正的类别清单；缺省时从模型 classConfig 读取。 */
    classOptions?: string[]
    /** 用于读取 classConfig 的模型 ID。 */
    modelId?: number
  }>(),
  {
    result: null,
    results: () => [],
    image: null,
    imageSrc: '',
    classOptions: () => [],
    modelId: 0
  }
)

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (
    e: 'reviewed',
    payload: { resultIds: number[]; action: ReviewAction; className?: string; remark?: string }
  ): void
  (e: 'closed'): void
}>()

const action = ref<ReviewAction>('CONFIRM')
const correctedClass = ref('')
const remark = ref('')
const submitting = ref(false)
const errorMsg = ref('')
const successMsg = ref('')
const remoteOptions = ref<string[]>([])

const isBatch = computed(() => props.results.length > 0)

const targets = computed<DetectionResult[]>(() => {
  if (isBatch.value) return props.results
  return props.result ? [props.result] : []
})

const primary = computed<DetectionResult | null>(() => targets.value[0] ?? null)

/**
 * 待审核图像的 ObjectURL。
 * 弹窗取 raw 而不是缩略图：复核要看原始像素细节，且 detection_result 的框坐标
 * （x1..y2）是原图坐标系，DetectionImage 按 naturalWidth/naturalHeight 定位。
 * 后端 thumbnail 目前与 raw 同字节，故这不是额外带宽。
 */
const displaySrc = ref('')
const srcFailed = ref(false)

/** 无图可预览 / 加载中 / 加载失败 —— 三者文案不同。 */
const previewPlaceholder = computed(() => {
  if (!props.image && !props.imageSrc) return '该结果未关联可预览图像'
  if (srcFailed.value) return '图像加载失败'
  return '图像加载中...'
})

/** 迟到的响应按序号丢弃，避免关掉弹窗后仍写入（ObjectURL 会泄漏）。 */
let requestSeq = 0

function releaseDisplay() {
  if (displaySrc.value.startsWith('blob:')) {
    revokeImageUrl(displaySrc.value)
  }
  displaySrc.value = ''
}

async function resolveImage() {
  const seq = ++requestSeq
  releaseDisplay()
  srcFailed.value = false

  // 父组件明确传入的本地/blob/data/http 地址，直接使用
  const given = props.imageSrc
  if (given && !given.startsWith('/api/images/')) {
    displaySrc.value = given
    return
  }

  const imageId = props.visible ? props.image?.id : undefined
  if (!imageId) return

  try {
    const url = await loadProtectedImage(imageId, 'raw')
    if (seq !== requestSeq) {
      revokeImageUrl(url)
      return
    }
    displaySrc.value = url
  } catch (error) {
    if (seq !== requestSeq) return
    srcFailed.value = true
    console.error('加载审核图片失败:', error)
  }
}

/** 组件常驻挂载（只切 overlay 的 v-if），故「重新打开」也必须触发重取。 */
watch(() => [props.visible, props.image?.id, props.imageSrc], resolveImage, { immediate: true })

onBeforeUnmount(releaseDisplay)

/** 预览时只画当前复核的框，避免批量复核时视觉干扰。 */
const boxList = computed<DetectionBox[]>(() => targets.value.filter((t) => t.id != null))

const optionPool = computed<string[]>(() => {
  if (props.classOptions.length) return props.classOptions
  if (remoteOptions.value.length) return remoteOptions.value
  // 兜底：把当前结果里出现过的类别名去重后作为候选
  const names = Array.from(new Set(targets.value.map((t) => t.className))).filter(Boolean)
  return names
})

const filteredOptions = computed(() => {
  const keyword = correctedClass.value.trim()
  const pool = optionPool.value
  if (!keyword || pool.includes(keyword)) return pool.slice(0, 40)
  return pool.filter((n) => n.includes(keyword)).slice(0, 40)
})

const actionOptions: Array<{ value: ReviewAction; label: string; hint: string; icon: string }> = [
  { value: 'CONFIRM', label: '确认无误', hint: '物种判定正确，直接入库', icon: '✓' },
  { value: 'CORRECT', label: '修正物种', hint: '改为正确类别，计入修正率', icon: '✎' },
  { value: 'REJECT', label: '误检剔除', hint: '该框不是动物或不可辨认', icon: '✕' }
]

const actionHint = computed(() => {
  if (action.value === 'CONFIRM') return '确认后该结果状态置为「已确认」'
  if (action.value === 'CORRECT') {
    if (!correctedClass.value.trim()) return '请先填写或选择正确的物种类别'
    return `将 ${isBatch.value ? '所选 ' + targets.value.length + ' 项' : primary.value?.className ?? ''} 修正为「${correctedClass.value.trim()}」`
  }
  return '剔除后该结果不计入物种统计'
})

const canSubmit = computed(() => {
  if (submitting.value) return false
  if (!targets.value.length) return false
  if (action.value === 'CORRECT' && !correctedClass.value.trim()) return false
  if (action.value === 'CORRECT' && !isBatch.value && correctedClass.value.trim() === primary.value?.className) {
    return false
  }
  return true
})

/** 打开时重置表单，并拉取可修正类别清单。 */
watch(
  () => props.visible,
  async (open) => {
    if (!open) return
    action.value = 'CONFIRM'
    correctedClass.value = ''
    remark.value = ''
    errorMsg.value = ''
    successMsg.value = ''
    submitting.value = false

    if (props.classOptions.length || !props.modelId) return
    try {
      const res = await getModel(props.modelId)
      remoteOptions.value = parseClassConfig(res.data?.classConfig)
    } catch {
      remoteOptions.value = []
    }
  }
)

function close() {
  if (submitting.value) return
  emit('update:visible', false)
  emit('closed')
}

function onBackdrop() {
  close()
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  errorMsg.value = ''
  successMsg.value = ''

  const resultIds = targets.value.map((t) => t.id)
  const className = action.value === 'CORRECT' ? correctedClass.value.trim() : undefined
  const payload = { action: action.value, className, remark: remark.value.trim() || undefined }

  try {
    if (isBatch.value) {
      const res = await submitBatchReview({
        resultIds,
        action: payload.action,
        // 后端字段名为 correctedClass —— 修正后的物种名
        correctedClass: payload.className,
        remark: payload.remark
      })
      successMsg.value = `已复核 ${res.data.reviewed} 项`
    } else {
      await submitReview(resultIds[0], {
        action: payload.action,
        correctedClass: payload.className,
        remark: payload.remark
      })
      successMsg.value = '复核已提交'
    }
    emit('reviewed', { resultIds, action: payload.action, className: payload.className, remark: payload.remark })
    // 短暂展示成功提示后自动关闭
    setTimeout(() => {
      submitting.value = false
      emit('update:visible', false)
      emit('closed')
    }, 500)
  } catch (err: any) {
    submitting.value = false
    errorMsg.value = `提交失败：${err?.response?.data?.error || err?.message || '未知错误'}`
  }
}
</script>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.72);
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.dialog {
  width: min(1080px, 96vw);
  max-height: 92vh;
  background: var(--bg-admin-card);
  border: 1px solid var(--border-primary);
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-shadow: var(--shadow-panel);
}

.dialog-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border-primary);
}

.title-wrap { display: flex; align-items: center; gap: 10px; }
.title { font-size: 15px; font-weight: 600; color: var(--text-primary); }

.batch-tag {
  font-size: 11px;
  color: var(--color-primary);
  background: var(--bg-card-active);
  border: 1px solid var(--border-accent);
  padding: 1px 8px;
  border-radius: 10px;
}

.btn-close {
  background: none;
  border: none;
  color: var(--text-dim);
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
  padding: 0 4px;
}
.btn-close:hover { color: var(--text-danger); }
.btn-close:disabled { opacity: 0.4; cursor: not-allowed; }

.dialog-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 1.25fr 1fr;
  gap: 0;
}

.pane {
  padding: 16px 18px;
  overflow-y: auto;
}

.pane-left {
  border-right: 1px solid var(--border-primary);
  background: var(--bg-admin-input-alt);
}

.no-image {
  font-size: 13px;
  color: var(--text-dim);
  padding: 40px 0;
  text-align: center;
}

.pane-right { display: flex; flex-direction: column; gap: 16px; }

.block { display: flex; flex-direction: column; gap: 8px; }

.block-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  letter-spacing: 0.5px;
}

.required { color: var(--text-danger); }

.single-info,
.batch-list { display: flex; flex-direction: column; gap: 6px; }

.batch-list { max-height: 168px; overflow-y: auto; }

.batch-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  padding: 4px 6px;
  border-radius: 4px;
  background: var(--bg-admin-input-alt);
}

.bi-name { color: var(--text-primary); flex: 1; }
.bi-conf { color: var(--text-muted); font-variant-numeric: tabular-nums; }

.info-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 13px;
}

.info-label { color: var(--text-dim); font-size: 12px; min-width: 60px; flex-shrink: 0; }

.info-value {
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.info-value.mono { font-variant-numeric: tabular-nums; font-size: 12px; }

.action-group { display: flex; flex-direction: column; gap: 6px; }

.action-btn {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: var(--bg-admin-input-alt);
  border: 1px solid var(--border-admin-input);
  border-radius: 6px;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.2s, background 0.2s;
}

.action-btn:hover:not(:disabled) { border-color: var(--color-primary); }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.action-btn.active { background: var(--bg-card-active); }
.action-btn.confirm.active { border-color: #4caf50; }
.action-btn.correct.active { border-color: var(--color-purple); }
.action-btn.reject.active { border-color: var(--color-danger); }

.ab-icon { font-size: 15px; width: 18px; text-align: center; flex-shrink: 0; }
.ab-text { display: flex; flex-direction: column; gap: 1px; }
.ab-text b { font-size: 13px; font-weight: 600; color: var(--text-primary); }
.ab-text em { font-size: 11px; font-style: normal; color: var(--text-dim); }

.class-input,
.remark {
  width: 100%;
  padding: 7px 10px;
  background: var(--bg-admin-input-alt);
  border: 1px solid var(--border-admin-input);
  border-radius: 4px;
  color: var(--text-primary);
  font-size: 13px;
  outline: none;
  font-family: inherit;
  resize: vertical;
}

.class-input:focus,
.remark:focus { border-color: var(--color-admin-focus); }

.class-options {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 132px;
  overflow-y: auto;
  padding: 2px;
}

.class-option {
  padding: 3px 10px;
  font-size: 12px;
  background: var(--bg-admin-input-alt);
  color: var(--text-secondary);
  border: 1px solid var(--border-admin-input);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.15s;
}

.class-option:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
.class-option.active {
  background: var(--bg-card-active);
  border-color: var(--color-primary);
  color: var(--color-primary);
}
.class-option:disabled { opacity: 0.5; cursor: not-allowed; }

.tip { font-size: 12px; color: var(--text-dim); }

.message { padding: 8px 12px; border-radius: 4px; font-size: 12px; }
.message.success { background: rgba(46, 125, 50, 0.15); color: #81c784; border: 1px solid #2e7d32; }
.message.error { background: rgba(198, 40, 40, 0.15); color: var(--text-danger); border: 1px solid #c62828; }

.dialog-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 18px;
  border-top: 1px solid var(--border-primary);
  background: var(--bg-admin-input-alt);
}

.foot-hint { font-size: 12px; color: var(--text-dim); }
.foot-actions { display: flex; gap: 10px; }

.btn-primary {
  padding: 7px 18px;
  background: #1565c0;
  color: #fff;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-ghost {
  padding: 7px 14px;
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border-admin-input);
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}
.btn-ghost:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
.btn-ghost:disabled { opacity: 0.5; cursor: not-allowed; }

.dot { width: 8px; height: 8px; border-radius: 2px; flex-shrink: 0; }

@media (max-width: 900px) {
  .dialog-body { grid-template-columns: 1fr; }
  .pane-left { border-right: none; border-bottom: 1px solid var(--border-primary); }
}
</style>
