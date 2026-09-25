<template>
  <section class="upload-panel">
    <header class="panel-head">
      <div class="panel-title">
        <span class="icon">📤</span>
        <span>批量图像上传</span>
        <span v-if="files.length" class="counter">{{ files.length }} / {{ maxCount }}</span>
      </div>
      <div class="head-actions">
        <button class="btn-ghost" :disabled="disabled || uploading" @click="openPicker">选择图像</button>
      </div>
    </header>

    <!-- 拖拽 / 点击选择区 -->
    <div
      class="drop-zone"
      :class="{ over: dragging, busy: uploading, disabled }"
      @click="openPicker"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
    >
      <input
        ref="inputRef"
        type="file"
        :accept="accept"
        multiple
        hidden
        @change="onPick"
      />
      <div class="drop-inner">
        <span class="drop-icon">🖼</span>
        <p class="drop-main">把图像拖到这里，或点击选择文件</p>
        <p class="drop-sub">{{ acceptHint }}</p>
      </div>
    </div>

    <!-- 待上传清单 -->
    <div v-if="files.length" class="file-list">
      <div class="list-head">
        <span>待上传 {{ files.length }} 张</span>
        <div class="list-actions">
          <button class="btn-mini" :disabled="uploading" @click="clearFiles">清空</button>
        </div>
      </div>
      <ul class="thumbs">
        <li v-for="(f, i) in files" :key="f.key" class="thumb">
          <img :src="f.preview" :alt="f.file.name" />
          <button
            class="thumb-remove"
            :title="'移除 ' + f.file.name"
            :disabled="uploading"
            @click="removeAt(i)"
          >×</button>
          <span class="thumb-name" :title="f.file.name">{{ f.file.name }}</span>
          <span class="thumb-size">{{ formatSize(f.file.size) }}</span>
        </li>
      </ul>
    </div>

    <!-- 上传进度 -->
    <div v-if="uploading" class="progress-block">
      <div class="progress-bar">
        <div class="progress-fill" :style="{ width: progress + '%' }"></div>
      </div>
      <span class="progress-text">上传中 {{ progress }}%</span>
    </div>

    <!-- 结果提示 -->
    <div v-if="message" class="message" :class="isError ? 'error' : 'success'">{{ message }}</div>

    <details v-if="failures.length" class="failures">
      <summary>{{ failures.length }} 张上传失败（点击查看原因）</summary>
      <ul>
        <li v-for="(f, i) in failures" :key="i">
          <span class="fail-name">{{ f.fileName }}</span>
          <span class="fail-reason">{{ f.reason }}</span>
        </li>
      </ul>
    </details>

    <!-- 底部操作 -->
    <footer class="panel-foot">
      <span class="hint">
        单张上限 {{ maxSizeMb }} MB，单批上限 {{ maxCount }} 张
      </span>
      <div class="foot-actions">
        <button class="btn-ghost" :disabled="uploading" @click="clearFiles">清空</button>
        <button class="btn-primary" :disabled="!canUpload" @click="handleUpload">
          {{ uploading ? '上传中...' : `开始上传${files.length ? ' (' + files.length + ')' : ''}` }}
        </button>
      </div>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { uploadImagesBatch, type RecognitionImage } from '@/api/index'

/** 拍一张待上传项的本地状态：文件 + 预览地址（卸载时需回收）。 */
interface PendingFile {
  key: string
  file: File
  preview: string
}

const props = withDefaults(
  defineProps<{
    /** 允许的扩展名，逗号分隔。 */
    accept?: string
    /** 单批最大张数，与后端 MAX_BATCH_SIZE 一致。 */
    maxCount?: number
    /** 单张体积上限（MB）。 */
    maxSizeMb?: number
    /** 上传完成后是否自动清空列表。 */
    clearOnDone?: boolean
    disabled?: boolean
  }>(),
  {
    accept: '.jpg,.jpeg,.png,.bmp,.webp,.tif,.tiff',
    maxCount: 200,
    maxSizeMb: 50,
    clearOnDone: true,
    disabled: false
  }
)

const emit = defineEmits<{
  (e: 'uploaded', images: RecognitionImage[]): void
  (e: 'failed', failures: Array<{ fileName: string; reason: string }>): void
  (e: 'clear'): void
}>()

const inputRef = ref<HTMLInputElement | null>(null)
const files = ref<PendingFile[]>([])
const dragging = ref(false)
const uploading = ref(false)
const progress = ref(0)
const message = ref('')
const isError = ref(false)
const failures = ref<Array<{ fileName: string; reason: string }>>([])

const acceptExts = computed(() =>
  props.accept
    .split(',')
    .map((s) => s.trim().toLowerCase().replace(/^\./, ''))
    .filter(Boolean)
)

const acceptHint = computed(() => {
  const exts = acceptExts.value.map((e) => e.toUpperCase())
  return `支持 ${exts.join(' / ')}，单张 ≤ ${props.maxSizeMb} MB`
})

const canUpload = computed(() => files.value.length > 0 && !uploading.value && !props.disabled)

function openPicker() {
  if (props.disabled || uploading.value) return
  inputRef.value?.click()
}

function extOf(name: string): string {
  const idx = name.lastIndexOf('.')
  return idx < 0 ? '' : name.slice(idx + 1).toLowerCase()
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 校验并加入待上传清单，返回被拒绝的文件与原因。 */
function addFiles(incoming: File[]) {
  const rejected: string[] = []
  const accepted: PendingFile[] = []
  const overflow = files.value.length + incoming.length - props.maxCount

  incoming.forEach((file, index) => {
    if (index >= incoming.length - Math.max(overflow, 0)) {
      rejected.push(`${file.name}：超出单批 ${props.maxCount} 张上限`)
      return
    }
    if (!acceptExts.value.includes(extOf(file.name))) {
      rejected.push(`${file.name}：不支持的格式`)
      return
    }
    if (file.size > props.maxSizeMb * 1024 * 1024) {
      rejected.push(`${file.name}：超过 ${props.maxSizeMb} MB`)
      return
    }
    // 同名文件按"名称+大小+修改时间"去重，避免重复上传同一张
    const dup = files.value.some(
      (f) => f.file.name === file.name && f.file.size === file.size && f.file.lastModified === file.lastModified
    )
    if (dup) {
      rejected.push(`${file.name}：已在待上传列表中`)
      return
    }
    accepted.push({
      key: `${file.name}-${file.lastModified}-${Math.random().toString(36).slice(2, 8)}`,
      file,
      preview: URL.createObjectURL(file)
    })
  })

  files.value = files.value.concat(accepted)

  if (rejected.length) {
    isError.value = true
    message.value = `已跳过 ${rejected.length} 个文件：${rejected.slice(0, 3).join('；')}${rejected.length > 3 ? ' 等' : ''}`
  } else if (accepted.length) {
    isError.value = false
    message.value = ''
  }
}

function onPick(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files?.length) addFiles(Array.from(input.files))
  input.value = ''
}

function onDrop(e: DragEvent) {
  dragging.value = false
  if (props.disabled || uploading.value) return
  const dropped = e.dataTransfer?.files
  if (dropped?.length) addFiles(Array.from(dropped))
}

function removeAt(index: number) {
  const target = files.value[index]
  if (!target) return
  URL.revokeObjectURL(target.preview)
  files.value.splice(index, 1)
}

function clearFiles() {
  files.value.forEach((f) => URL.revokeObjectURL(f.preview))
  files.value = []
  progress.value = 0
  message.value = ''
  isError.value = false
  failures.value = []
  emit('clear')
}

async function handleUpload() {
  if (!canUpload.value) return
  uploading.value = true
  progress.value = 0
  message.value = ''
  isError.value = false
  failures.value = []

  try {
    const res = await uploadImagesBatch(files.value.map((f) => f.file), (p) => {
      progress.value = p
    })

    const data = res.data
    failures.value = data.failures ?? []

    if (data.successCount > 0) {
      emit('uploaded', data.images ?? [])
    }
    if (failures.value.length) {
      emit('failed', failures.value)
    }

    if (data.failedCount === 0) {
      isError.value = false
      message.value = `上传完成：成功 ${data.successCount} 张`
      if (props.clearOnDone) {
        files.value.forEach((f) => URL.revokeObjectURL(f.preview))
        files.value = []
      }
    } else if (data.successCount === 0) {
      isError.value = true
      message.value = `全部上传失败（${data.failedCount} 张），请查看下方原因`
    } else {
      isError.value = true
      message.value = `部分成功：成功 ${data.successCount} 张，失败 ${data.failedCount} 张`
      if (props.clearOnDone) {
        files.value.forEach((f) => URL.revokeObjectURL(f.preview))
        files.value = []
      }
    }
  } catch (err: any) {
    isError.value = true
    message.value = `上传失败：${err?.response?.data?.error || err?.message || '未知错误'}`
  } finally {
    uploading.value = false
  }
}

onBeforeUnmount(() => {
  files.value.forEach((f) => URL.revokeObjectURL(f.preview))
})
</script>

<style scoped>
.upload-panel {
  background: var(--bg-admin-card);
  border: 1px solid var(--border-primary);
  border-radius: 10px;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-secondary);
}

.panel-title .icon { font-size: 16px; }

.counter {
  font-size: 12px;
  font-weight: 400;
  color: var(--color-primary);
  background: var(--bg-card-active);
  border: 1px solid var(--border-accent);
  padding: 1px 8px;
  border-radius: 10px;
}

.drop-zone {
  border: 1.5px dashed var(--border-admin-input);
  border-radius: 8px;
  background: var(--bg-admin-input-alt);
  padding: 26px 16px;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}

.drop-zone:hover { border-color: var(--color-primary); }
.drop-zone.over {
  border-color: var(--color-primary);
  background: var(--bg-card-active);
}
.drop-zone.busy,
.drop-zone.disabled { cursor: not-allowed; opacity: 0.6; }

.drop-inner { display: flex; flex-direction: column; gap: 6px; align-items: center; }
.drop-icon { font-size: 26px; }
.drop-main { font-size: 13px; color: var(--text-secondary); }
.drop-sub { font-size: 12px; color: var(--text-dim); }

.file-list { display: flex; flex-direction: column; gap: 8px; }
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--text-muted);
}
.list-actions { display: flex; gap: 8px; }

.thumbs {
  list-style: none;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(104px, 1fr));
  gap: 10px;
  max-height: 268px;
  overflow-y: auto;
  padding: 2px;
}

.thumb {
  position: relative;
  border: 1px solid var(--border-primary);
  border-radius: 6px;
  overflow: hidden;
  background: var(--bg-admin-input-alt);
  display: flex;
  flex-direction: column;
}

.thumb img {
  width: 100%;
  height: 72px;
  object-fit: cover;
  display: block;
  background: #000;
}

.thumb-name {
  font-size: 11px;
  color: var(--text-secondary);
  padding: 3px 5px 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.thumb-size {
  font-size: 10px;
  color: var(--text-dim);
  padding: 0 5px 4px;
}

.thumb-remove {
  position: absolute;
  top: 3px;
  right: 3px;
  width: 18px;
  height: 18px;
  line-height: 16px;
  text-align: center;
  border: none;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  padding: 0;
}
.thumb-remove:hover { background: var(--color-danger); }
.thumb-remove:disabled { opacity: 0.4; cursor: not-allowed; }

.progress-block { display: flex; align-items: center; gap: 10px; }
.progress-bar {
  flex: 1;
  height: 6px;
  background: var(--bg-admin-input);
  border-radius: 3px;
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  background: var(--color-primary);
  transition: width 0.2s;
}
.progress-text { font-size: 12px; color: var(--color-primary); min-width: 88px; text-align: right; }

.message { padding: 8px 14px; border-radius: 4px; font-size: 13px; }
.message.success { background: rgba(46, 125, 50, 0.15); color: #81c784; border: 1px solid #2e7d32; }
.message.error { background: rgba(198, 40, 40, 0.15); color: var(--text-danger); border: 1px solid #c62828; }

.failures { font-size: 12px; color: var(--text-muted); }
.failures summary { cursor: pointer; color: var(--text-warning); }
.failures ul { list-style: none; margin-top: 6px; display: flex; flex-direction: column; gap: 4px; }
.failures li { display: flex; gap: 8px; }
.fail-name { color: var(--text-secondary); flex-shrink: 0; }
.fail-reason { color: var(--text-danger); }

.panel-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-primary);
}
.hint { font-size: 12px; color: var(--text-dim); }
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

.btn-mini {
  padding: 3px 10px;
  font-size: 12px;
  background: transparent;
  color: var(--text-muted);
  border: 1px solid var(--border-admin-input);
  border-radius: 4px;
  cursor: pointer;
}
.btn-mini:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
