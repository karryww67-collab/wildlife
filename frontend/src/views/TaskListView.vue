<template>
  <div class="page">
    <!-- 页头 -->
    <header class="page-head">
      <div class="head-left">
        <h1 class="page-title">识别任务</h1>
        <p class="page-sub">
          上传红外相机采集的图像并创建批量识别任务，一个任务对应一批图像的一次识别过程
        </p>
      </div>
      <div class="head-actions">
        <button class="btn-ghost" :disabled="loading" @click="refresh">刷新</button>
        <button class="btn-ghost" @click="uploadOpen = true">上传图像</button>
        <button class="btn-primary" @click="openCreate()">新建识别任务</button>
      </div>
    </header>

    <!-- 概览 -->
    <section class="stat-row">
      <div v-for="item in summary" :key="item.label" class="stat-card">
        <span class="stat-value" :class="item.tone">{{ item.value }}</span>
        <span class="stat-label">{{ item.label }}</span>
      </div>
    </section>

    <!-- 筛选 -->
    <section class="card filter-bar">
      <label class="field">
        <span class="field-label">任务状态</span>
        <select v-model="statusFilter" @change="applyFilter">
          <option value="">全部状态</option>
          <option v-for="(label, key) in TASK_STATUS_LABEL" :key="key" :value="key">{{ label }}</option>
        </select>
      </label>
      <button class="btn-ghost" @click="resetFilter">重置</button>
    </section>

    <!-- 任务表 -->
    <section class="card">
      <div class="card-head">
        <span>任务列表</span>
        <span class="head-meta">共 {{ total }} 条</span>
      </div>

      <div v-if="loading" class="state">
        <span class="spinner"></span>
        <span>任务加载中...</span>
      </div>
      <div v-else-if="!tasks.length" class="state">
        <span class="state-icon">🗂</span>
        <span>暂无任务，点击右上角「新建识别任务」开始</span>
      </div>

      <table v-else class="table">
        <thead>
          <tr>
            <th class="col-id">ID</th>
            <th>任务名称</th>
            <th class="col-status">状态</th>
            <th class="col-progress">识别进度</th>
            <th class="col-num">图像</th>
            <th class="col-num">成功</th>
            <th class="col-num">失败</th>
            <th class="col-time">创建时间</th>
            <th class="col-ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in tasks" :key="task.id">
            <td class="mono">#{{ task.id }}</td>
            <td>
              <a class="link" @click="goDetail(task)">{{ task.taskName || '未命名任务' }}</a>
            </td>
            <td>
              <span class="badge" :class="statusClass(task.status)">
                {{ TASK_STATUS_LABEL[task.status] || task.status }}
              </span>
            </td>
            <td>
              <div class="progress-cell">
                <div class="bar">
                  <div
                    class="bar-fill"
                    :class="statusClass(task.status)"
                    :style="{ width: percentOf(task) + '%' }"
                  ></div>
                </div>
                <span class="progress-text">{{ percentOf(task).toFixed(1) }}%</span>
              </div>
            </td>
            <td class="num">{{ task.totalCount ?? 0 }}</td>
            <td class="num ok">{{ task.successCount ?? 0 }}</td>
            <td class="num bad">{{ task.failedCount ?? 0 }}</td>
            <td class="time">{{ formatTime(task.createTime) }}</td>
            <td class="ops">
              <button class="btn-mini" @click="goDetail(task)">详情</button>
              <button
                v-if="task.status === 'PENDING' || task.status === 'PROCESSING'"
                class="btn-mini"
                :disabled="busyId === task.id"
                @click="doCancel(task)"
              >取消</button>
              <button
                v-else-if="task.status === 'FAILED' || task.status === 'CANCELED'"
                class="btn-mini"
                :disabled="busyId === task.id"
                @click="doRetry(task)"
              >重试</button>
              <button
                class="btn-mini danger"
                :disabled="busyId === task.id || task.status === 'PROCESSING'"
                @click="doDelete(task)"
              >删除</button>
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

    <!-- 上传图像 -->
    <Teleport to="body">
      <div v-if="uploadOpen" class="modal" @click.self="uploadOpen = false">
        <div class="modal-box wide">
          <header class="modal-head">
            <span class="modal-title">上传图像</span>
            <button class="btn-close" @click="uploadOpen = false">×</button>
          </header>
          <div class="modal-body">
            <UploadPanel @uploaded="onUploaded" />
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 新建任务 -->
    <Teleport to="body">
      <div v-if="createOpen" class="modal" @click.self="closeCreate">
        <div class="modal-box wide">
          <header class="modal-head">
            <span class="modal-title">新建识别任务</span>
            <button class="btn-close" @click="closeCreate">×</button>
          </header>

          <div class="modal-body">
            <div class="form-row">
              <label class="field grow">
                <span class="field-label">任务名称</span>
                <input v-model.trim="newTaskName" placeholder="例如：2026 春季红外相机批次一" />
              </label>
              <label class="field">
                <span class="field-label">识别模型</span>
                <ModelSelector v-model="newTaskModelId" compact :auto-select-active="true" />
              </label>
            </div>

            <div class="pick-head">
              <span class="pick-title">
                选择待识别图像
                <span class="pick-count">已选 {{ selectedImageIds.length }} 张</span>
              </span>
              <div class="pick-actions">
                <button class="btn-mini" :disabled="!candidates.length" @click="selectAllCandidates">
                  全选本页（{{ candidates.length }}）
                </button>
                <button class="btn-mini" :disabled="!selectedImageIds.length" @click="selectedImageIds = []">
                  清空选择
                </button>
              </div>
            </div>

            <div v-if="loadingCandidates" class="state small">
              <span class="spinner"></span>
              <span>待识别图像加载中...</span>
            </div>
            <div v-else-if="!candidates.length" class="state small">
              <span>没有待识别的图像，请先上传（已创建任务的图像不会重复出现）</span>
            </div>
            <ul v-else class="pick-grid">
              <li
                v-for="img in candidates"
                :key="img.id"
                class="pick-item"
                :class="{ on: selectedImageIds.includes(img.id) }"
                @click="toggleImage(img.id)"
              >
                <img
                  v-if="thumbnailUrls[img.id]"
                  :src="thumbnailUrls[img.id]"
                  :alt="img.fileName"
                  loading="lazy"
                />
                <span class="pick-name" :title="img.fileName">{{ img.fileName }}</span>
                <span class="pick-id mono">#{{ img.id }}</span>
                <span class="tick" v-if="selectedImageIds.includes(img.id)">✓</span>
              </li>
            </ul>

            <div v-if="createError" class="message error">{{ createError }}</div>
          </div>

          <footer class="modal-foot">
            <span class="foot-hint">
              将创建一个包含 {{ selectedImageIds.length }} 张图像的识别任务，创建后立即排队等待识别
            </span>
            <div class="foot-actions">
              <button class="btn-ghost" :disabled="submitting" @click="closeCreate">取消</button>
              <button class="btn-primary" :disabled="!canCreate" @click="submitCreate">
                {{ submitting ? '创建中...' : '创建任务' }}
              </button>
            </div>
          </footer>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import UploadPanel from '@/components/UploadPanel.vue'
import ModelSelector from '@/components/ModelSelector.vue'
import { loadProtectedImage, revokeImageUrl } from '@/utils/imageUrl'
import {
  cancelTask,
  createTask,
  deleteTask,
  getTaskStatusStatistics,
  listImages,
  listTasks,
  retryTask,
  TASK_STATUS_LABEL,
  type RecognitionImage,
  type RecognitionTask,
  type TaskStatus,
  type TaskStatusStatistics
} from '@/api/index'

const router = useRouter()

const loading = ref(false)
const tasks = ref<RecognitionTask[]>([])
const statusFilter = ref<TaskStatus | ''>('')
const busyId = ref(0)

// 服务端分页：LIMIT/OFFSET 由后端执行，这里只保存当前页与总条数
const page = ref(1)
const size = 20
const total = ref(0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

// 上传
const uploadOpen = ref(false)

// 新建任务
const createOpen = ref(false)
const createError = ref('')
const submitting = ref(false)
const newTaskName = ref('')
const newTaskModelId = ref(0)
const candidates = ref<RecognitionImage[]>([])
const loadingCandidates = ref(false)
const selectedImageIds = ref<number[]>([])

/** 候选图缩略图：图像 id → ObjectURL（经 axios 取 blob 后创建，见 utils/imageUrl）。 */
const thumbnailUrls = ref<Record<number, string>>({})

/** 加载一张候选图缩略图；已加载过则复用，避免同一张图重复取流。 */
async function loadThumbnail(imageId: number) {
  if (thumbnailUrls.value[imageId]) return
  try {
    thumbnailUrls.value[imageId] = await loadProtectedImage(imageId, 'thumbnail')
  } catch (error) {
    console.error('加载缩略图失败:', error)
  }
}

/** 释放全部缩略图（弹窗关闭 / 组件卸载时调用）。 */
function revokeThumbnails() {
  Object.values(thumbnailUrls.value).forEach((url) => revokeImageUrl(url))
  thumbnailUrls.value = {}
}

/** 让缩略图缓存与当前候选列表对齐：释放已不在列表里的，取新出现的。 */
function syncThumbnails(ids: number[]) {
  const keep = new Set(ids)
  Object.keys(thumbnailUrls.value).forEach((key) => {
    const id = Number(key)
    if (!keep.has(id)) {
      revokeImageUrl(thumbnailUrls.value[id])
      delete thumbnailUrls.value[id]
    }
  })
  ids.forEach((id) => loadThumbnail(id))
}

/** 任务状态分布（全局口径），来自统计接口，不受当前页影响。 */
const statusStats = ref<TaskStatusStatistics | null>(null)

const summary = computed(() => {
  const countOf = (status: TaskStatus) =>
    statusStats.value?.list.find((item) => item.name === status)?.value ?? 0
  return [
    { label: '任务总数', value: statusStats.value?.total ?? 0, tone: '' },
    { label: '排队中', value: countOf('PENDING'), tone: 'warn' },
    { label: '识别中', value: countOf('PROCESSING'), tone: 'info' },
    { label: '已完成', value: countOf('COMPLETED'), tone: 'ok' },
    { label: '失败 / 取消', value: countOf('FAILED') + countOf('CANCELED'), tone: 'bad' }
  ]
})

const canCreate = computed(
  () => !submitting.value && selectedImageIds.value.length > 0 && !loadingCandidates.value
)

function statusClass(status: TaskStatus | string): string {
  return `st-${String(status || 'PENDING').toLowerCase()}`
}

/** 后端 progress 已是百分数，缺失时按已处理张数自算。 */
function percentOf(task: RecognitionTask): number {
  if (typeof task.progress === 'number' && task.progress > 0) {
    return Math.min(100, task.progress)
  }
  const imageTotal = task.totalCount ?? 0
  if (!imageTotal) return 0
  return Math.min(100, Math.round(((task.processedCount ?? 0) * 10000) / imageTotal) / 100)
}

function formatTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  loading.value = true
  try {
    const res = await listTasks({
      status: statusFilter.value || undefined,
      page: page.value,
      size
    })
    // 删除末页最后一条后当前页会越界（后端 overflow=false，不自动回退），退回最后一页重查
    const pages = res.data?.pages ?? 1
    if (pages >= 1 && page.value > pages) {
      page.value = pages
      return await load()
    }
    tasks.value = res.data?.list ?? []
    total.value = res.data?.total ?? 0
  } catch {
    tasks.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 概览统计：走统计接口取全局计数；失败时保留上一次的数字，不影响列表。 */
async function loadSummary() {
  try {
    const res = await getTaskStatusStatistics()
    statusStats.value = res.data ?? null
  } catch {
    /* 统计失败不阻断任务列表 */
  }
}

/** 列表 + 概览一起刷新。 */
async function refresh() {
  await Promise.all([load(), loadSummary()])
}

/** 状态筛选变化：回到第 1 页重新查询。 */
function applyFilter() {
  page.value = 1
  load()
}

function turnPage(delta: number) {
  page.value = Math.max(1, Math.min(totalPages.value, page.value + delta))
  load()
}

function resetFilter() {
  statusFilter.value = ''
  page.value = 1
  refresh()
}

function goDetail(task: RecognitionTask) {
  router.push(`/tasks/${task.id}`)
}

async function doCancel(task: RecognitionTask) {
  if (!window.confirm(`确定取消任务 #${task.id}？未识别的图像将保持未处理状态。`)) return
  busyId.value = task.id
  try {
    await cancelTask(task.id)
    await refresh()
  } catch (err: any) {
    window.alert(`取消失败：${err?.response?.data?.error || err?.message || '未知错误'}`)
  } finally {
    busyId.value = 0
  }
}

async function doRetry(task: RecognitionTask) {
  busyId.value = task.id
  try {
    await retryTask(task.id)
    await refresh()
  } catch (err: any) {
    window.alert(`重试失败：${err?.response?.data?.error || err?.message || '未知错误'}`)
  } finally {
    busyId.value = 0
  }
}

async function doDelete(task: RecognitionTask) {
  if (!window.confirm(`删除任务 #${task.id} 会同时清除该任务的识别结果，确定继续？`)) return
  busyId.value = task.id
  try {
    await deleteTask(task.id)
    await refresh()
  } catch (err: any) {
    window.alert(`删除失败：${err?.response?.data?.error || err?.message || '未知错误'}`)
  } finally {
    busyId.value = 0
  }
}

/** 上传成功后自动进入建任务流程，并预选刚上传的图像。 */
function onUploaded(images: RecognitionImage[]) {
  uploadOpen.value = false
  openCreate(images.map((img) => img.id))
}

async function openCreate(preselect: number[] = []) {
  createOpen.value = true
  createError.value = ''
  submitting.value = false
  newTaskName.value = newTaskName.value || defaultTaskName()
  selectedImageIds.value = [...preselect]
  await loadCandidates()
}

function defaultTaskName(): string {
  const date = new Date()
  const stamp = `${date.getFullYear()}${String(date.getMonth() + 1).padStart(2, '0')}${String(
    date.getDate()
  ).padStart(2, '0')}`
  return `识别批次 ${stamp}`
}

async function loadCandidates() {
  loadingCandidates.value = true
  try {
    // 只取尚未归属任何任务的图像作为可选项（上传后默认就是 WAITING 状态）
    const res = await listImages({ status: 'WAITING', page: 1, size: 200 })
    candidates.value = (res.data?.list ?? []).filter((img) => !img.taskId)
    syncThumbnails(candidates.value.map((img) => img.id))
  } catch {
    candidates.value = []
    syncThumbnails([])
  } finally {
    loadingCandidates.value = false
  }
}

function toggleImage(id: number) {
  const index = selectedImageIds.value.indexOf(id)
  if (index >= 0) {
    selectedImageIds.value.splice(index, 1)
  } else {
    selectedImageIds.value.push(id)
  }
}

function selectAllCandidates() {
  const ids = candidates.value.map((img) => img.id)
  const allSelected = ids.every((id) => selectedImageIds.value.includes(id))
  selectedImageIds.value = allSelected ? [] : ids
}

function closeCreate() {
  createOpen.value = false
  createError.value = ''
  revokeThumbnails()
}

async function submitCreate() {
  if (!canCreate.value) return
  submitting.value = true
  createError.value = ''
  try {
    const res = await createTask({
      taskName: newTaskName.value,
      imageIds: [...selectedImageIds.value],
      modelId: newTaskModelId.value > 0 ? newTaskModelId.value : undefined
    })
    // 后端返回创建好的整个任务对象，取其 id 跳详情
    const taskId = res.data?.id
    closeCreate()
    if (taskId) {
      router.push(`/tasks/${taskId}`)
    } else {
      await refresh()
    }
  } catch (err: any) {
    createError.value = `创建失败：${err?.response?.data?.error || err?.message || '未知错误'}`
  } finally {
    submitting.value = false
  }
}

onMounted(refresh)

// 离开页面时释放全部图片 ObjectURL，避免内存常驻
onBeforeUnmount(revokeThumbnails)
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

/* ── 概览卡 ── */
.stat-row {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
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

/* ── 筛选 ── */
.filter-bar {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 14px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 150px;
}
.field.grow { flex: 1; }
.field-label {
  font-size: 11.5px;
  color: var(--text-muted);
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
  transition: border-color 0.2s;
}
input:focus,
select:focus { border-color: var(--color-admin-focus); }

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
.col-progress { width: 170px; }
.col-num, .num { width: 72px; text-align: right; font-variant-numeric: tabular-nums; }
.col-time { width: 160px; }
.col-ops { width: 200px; }
.num.ok { color: var(--color-success); }
.num.bad { color: var(--color-danger); }
.time { color: var(--text-muted); font-size: 12px; }
.mono { font-family: Consolas, Monaco, monospace; color: var(--text-muted); }
.ops { display: flex; gap: 6px; flex-wrap: wrap; }

.link {
  color: var(--color-primary);
  cursor: pointer;
  text-decoration: none;
}
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

/* ── 进度 ── */
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
  width: 46px;
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
.btn-primary:disabled { opacity: 0.45; cursor: not-allowed; }
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
.btn-mini:disabled { opacity: 0.45; cursor: not-allowed; }
.btn-mini.danger:hover:not(:disabled) { border-color: var(--color-danger); color: var(--text-danger); }

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
.state.small { padding: 24px 16px; }
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

/* ── 弹窗 ── */
.modal {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(3px);
}
.modal-box {
  width: 100%;
  max-width: 520px;
  max-height: 88vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-admin-card);
  border: 1px solid var(--border-primary);
  border-radius: 10px;
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.35);
  overflow: hidden;
}
.modal-box.wide { max-width: 860px; }
.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border-primary);
}
.modal-title { font-size: 14px; font-weight: 600; }
.btn-close {
  width: 26px;
  height: 26px;
  background: transparent;
  border: none;
  color: var(--text-muted);
  font-size: 19px;
  line-height: 1;
  cursor: pointer;
  border-radius: 4px;
}
.btn-close:hover { color: var(--text-primary); background: var(--bg-card-hover); }
.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 18px;
}
.modal-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 18px;
  border-top: 1px solid var(--border-primary);
  background: var(--bg-admin-input-alt);
}
.foot-hint { font-size: 11.5px; color: var(--text-muted); }
.foot-actions { display: flex; gap: 8px; }

.form-row {
  display: flex;
  gap: 12px;
  align-items: flex-end;
  margin-bottom: 16px;
}
.form-row .field.grow { flex: 1; min-width: 0; }

/* ── 图像选择 ── */
.pick-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.pick-title { font-size: 12.5px; color: var(--text-secondary); font-weight: 600; }
.pick-count { margin-left: 8px; font-weight: 400; color: var(--color-primary); }
.pick-actions { display: flex; gap: 6px; }
.pick-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(112px, 1fr));
  gap: 10px;
  list-style: none;
  max-height: 320px;
  overflow-y: auto;
  padding: 2px;
}
.pick-item {
  position: relative;
  border: 1px solid var(--border-admin-input);
  border-radius: 6px;
  overflow: hidden;
  cursor: pointer;
  background: var(--bg-admin-input);
  transition: border-color 0.2s, box-shadow 0.2s;
}
.pick-item:hover { border-color: var(--color-primary); }
.pick-item.on {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 1px var(--color-primary) inset;
}
.pick-item img {
  display: block;
  width: 100%;
  height: 78px;
  object-fit: cover;
  background: var(--bg-track);
}
.pick-name {
  display: block;
  padding: 4px 6px 0;
  font-size: 11px;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.pick-id {
  display: block;
  padding: 1px 6px 5px;
  font-size: 10px;
}
.tick {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 17px;
  height: 17px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--color-primary);
  color: var(--text-white);
  font-size: 11px;
}

.message {
  margin-top: 12px;
  padding: 8px 10px;
  border-radius: 4px;
  font-size: 12px;
}
.message.error { background: var(--bg-badge-danger); color: var(--text-danger); }
.message.success { background: var(--bg-badge-success); color: var(--color-success); }
</style>
