<template>
  <div class="page">
    <!-- 页头 -->
    <header class="page-head">
      <div class="head-left">
        <h1 class="page-title">模型管理</h1>
        <p class="page-sub">
          登记多版本 YOLO 权重并切换启用的识别模型，指标用于对比不同版本在同一数据集上的表现
        </p>
      </div>
      <div class="head-actions">
        <button class="btn-ghost" :disabled="loading" @click="reloadAll">刷新</button>
        <button class="btn-primary" @click="openForm()">新增模型版本</button>
      </div>
    </header>

    <!--
      权重一致性告警。
      后端 model_version 表说的是"声称启用哪个版本"，而实际加载的是哪份权重
      只有 AI 引擎知道 —— 两者不一致时必须显式告知，而不是让页面继续显示
      "当前模型 wildlife-v1.0" 而底下跑着另一份权重。
    -->
    <div v-if="engineError" class="weight-alert warn">
      <div class="alert-head">⚠️ {{ engineError }}</div>
      <div class="alert-body">
        <p>无法确认 AI 引擎当前实际加载的是哪份权重，页面上显示的版本号仅供参考。</p>
      </div>
    </div>

    <div v-else-if="engineMismatch && engineStatus" class="weight-alert warn">
      <div class="alert-head">⚠️ 页面显示的模型版本与 AI 引擎实际加载的权重不一致</div>
      <div class="alert-body">
        <p>
          数据库登记的启用版本是 <b>{{ current?.version || '（未选中）' }}</b>，
          引擎实际加载的权重是 <b>{{ engineStatus.effectiveWeight.path }}</b>
          <template v-if="engineStatus.effectiveWeight.sizeBytes">
            （{{ (engineStatus.effectiveWeight.sizeBytes / 1048576).toFixed(1) }} MB，
            md5 <code>{{ engineStatus.effectiveWeight.md5.slice(0, 12) }}</code>）
          </template>
          <template v-if="engineStatus.usingFallback">
            ：该版本目录下没有 <code>best.pt</code>，引擎回退到了备用权重。
          </template>
          <template v-else>。</template>
        </p>
        <p class="alert-consequence">
          后果：识别结果的类别与 <b>{{ current?.version }}</b> 声明的类别无关，
          本页的 mAP 等指标也不能代表实际推理所用的权重。
        </p>
        <p v-if="engineStatus.declaredClasses.length" class="alert-classes">
          <b>{{ current?.version }}</b> 声明要识别的类别
          （{{ engineStatus.declaredClassCount }} 个）：
          {{ engineStatus.declaredClasses.join('、') }}
        </p>
        <ul v-if="engineStatus.issues.length" class="alert-issues">
          <li v-for="(issue, index) in engineStatus.issues" :key="index">{{ issue }}</li>
        </ul>
      </div>
    </div>

    <div v-else-if="engineStatus && engineStatus.ok" class="weight-alert ok">
      <div class="alert-head">✅ 权重自检通过</div>
      <div class="alert-body">
        <p>
          引擎实际加载 <b>{{ engineStatus.effectiveWeight.path }}</b>
          （md5 <code>{{ engineStatus.effectiveWeight.md5.slice(0, 12) }}</code>），
          与登记的启用版本一致。
        </p>
      </div>
    </div>

    <div class="model-body">
      <!-- 左：版本列表 -->
      <section class="card selector-card">
        <ModelSelector
          v-model="currentId"
          :models="models"
          label="模型版本"
          :allow-activate="true"
          :show-metrics="true"
          :auto-select-active="false"
          @change="onModelChange"
          @activated="onActivated"
        />
      </section>

      <!-- 右：指标 -->
      <section class="card metric-card">
        <div class="card-head">
          <span>版本指标</span>
          <span class="head-meta">{{ current ? current.version : '未选择版本' }}</span>
        </div>

        <div v-if="!current" class="state">
          <span class="state-icon">📈</span>
          <span>在左侧选择一个模型版本查看指标</span>
        </div>

        <div v-else class="metric-body">
          <div class="metric-grid">
            <div class="metric-item">
              <span class="metric-value">{{ fmt(current.map50) }}</span>
              <span class="metric-label">mAP@0.5</span>
            </div>
            <div class="metric-item">
              <span class="metric-value">{{ fmt(current.map5095) }}</span>
              <span class="metric-label">mAP@0.5:0.95</span>
            </div>
            <div class="metric-item">
              <span class="metric-value">{{ fmt(current.precisionValue) }}</span>
              <span class="metric-label">Precision</span>
            </div>
            <div class="metric-item">
              <span class="metric-value">{{ fmt(current.recallValue) }}</span>
              <span class="metric-label">Recall</span>
            </div>
            <div class="metric-item">
              <span class="metric-value">{{ fmt(metrics?.f1) }}</span>
              <span class="metric-label">F1</span>
            </div>
            <div class="metric-item">
              <span class="metric-value">{{ metrics?.classCount ?? classNames.length }}</span>
              <span class="metric-label">类别数</span>
            </div>
            <div class="metric-item">
              <span class="metric-value">{{ (metrics?.detectedCount ?? 0).toLocaleString('zh-CN') }}</span>
              <span class="metric-label">累计检出目标</span>
            </div>
            <div class="metric-item">
              <span class="metric-value">{{ ((metrics?.avgConfidence ?? 0) * 100).toFixed(1) }}%</span>
              <span class="metric-label">平均置信度</span>
            </div>
          </div>

          <div class="kv-list">
            <div class="kv">
              <span class="kv-label">模型名称</span>
              <span class="kv-value">{{ current.modelName || '—' }}</span>
            </div>
            <div class="kv">
              <span class="kv-label">权重路径</span>
              <span class="kv-value mono" :title="current.modelPath">{{ current.modelPath || '—' }}</span>
            </div>
            <div class="kv">
              <span class="kv-label">状态</span>
              <span class="badge" :class="current.status === 'ENABLED' ? 'st-enabled' : 'st-disabled'">
                {{ current.status === 'ENABLED' ? '启用中' : '未启用' }}
              </span>
            </div>
            <div class="kv">
              <span class="kv-label">登记时间</span>
              <span class="kv-value">{{ formatTime(current.createTime) }}</span>
            </div>
          </div>

          <div class="op-row">
            <button class="btn-ghost" @click="openForm(current)">编辑信息</button>
            <button
              v-if="current.status !== 'ENABLED'"
              class="btn-primary"
              :disabled="busy"
              @click="doActivate(current)"
            >设为启用模型</button>
            <button v-else class="btn-ghost" :disabled="busy" @click="doDeactivate(current)">
              取消启用
            </button>
            <button class="btn-ghost danger" :disabled="busy" @click="doDelete(current)">删除版本</button>
          </div>

          <div v-if="metricsError" class="message error">{{ metricsError }}</div>
        </div>
      </section>
    </div>

    <!-- 类别清单 + 类别分布 -->
    <div v-if="current" class="bottom-body">
      <section class="card class-card">
        <div class="card-head">
          <span>可识别物种类别</span>
          <span class="head-meta">共 {{ classNames.length }} 类</span>
        </div>
        <div v-if="!classNames.length" class="state small">该版本未配置类别清单（classConfig 为空）</div>
        <ul v-else class="class-list">
          <li v-for="(name, index) in classNames" :key="name" class="class-item">
            <span class="class-idx mono">{{ index }}</span>
            <span class="class-name">{{ name }}</span>
          </li>
        </ul>
      </section>

      <section class="card chart-card">
        <div class="card-head">
          <span>该版本的检出物种分布</span>
          <span class="head-meta">来自识别结果统计</span>
        </div>
        <div class="chart-wrap">
          <StatisticsChart
            v-if="classDistribution.length"
            type="hbar"
            :data="classDistribution"
            :height="300"
            unit="次"
          />
          <div v-else class="state small">该版本还没有产生识别结果</div>
        </div>
      </section>
    </div>

    <!-- 新增 / 编辑 -->
    <Teleport to="body">
      <div v-if="formVisible" class="modal" @click.self="closeForm">
        <div class="modal-box">
          <header class="modal-head">
            <span class="modal-title">{{ editing ? '编辑模型版本' : '新增模型版本' }}</span>
            <button class="btn-close" @click="closeForm">×</button>
          </header>

          <div class="modal-body">
            <div class="form-grid">
              <label class="field">
                <span class="field-label">模型名称</span>
                <input v-model.trim="form.modelName" placeholder="例如：野生动物识别模型" />
              </label>
              <label class="field">
                <span class="field-label">版本号</span>
                <input v-model.trim="form.version" placeholder="例如：wildlife-v2.0" />
              </label>
              <label class="field span-2">
                <span class="field-label">权重文件路径</span>
                <input v-model.trim="form.modelPath" placeholder="/models/wildlife-v2.0/best.pt" />
              </label>
              <label class="field">
                <span class="field-label">mAP@0.5</span>
                <input v-model.number="form.map50" type="number" step="0.0001" min="0" max="1" />
              </label>
              <label class="field">
                <span class="field-label">mAP@0.5:0.95</span>
                <input v-model.number="form.map5095" type="number" step="0.0001" min="0" max="1" />
              </label>
              <label class="field">
                <span class="field-label">Precision</span>
                <input v-model.number="form.precisionValue" type="number" step="0.0001" min="0" max="1" />
              </label>
              <label class="field">
                <span class="field-label">Recall</span>
                <input v-model.number="form.recallValue" type="number" step="0.0001" min="0" max="1" />
              </label>
              <label class="field">
                <span class="field-label">状态</span>
                <select v-model="form.status">
                  <option value="ENABLED">启用</option>
                  <option value="DISABLED">停用</option>
                </select>
              </label>
              <label class="field span-2">
                <span class="field-label">
                  类别清单（classConfig）
                  <span class="field-hint">JSON 数组、逗号分隔或每行一个均可</span>
                </span>
                <textarea
                  v-model="form.classConfig"
                  rows="4"
                  placeholder="野猪, 赤麂, 小麂, 白鹇, 猕猴"
                ></textarea>
              </label>
            </div>

            <div v-if="formError" class="message error">{{ formError }}</div>
          </div>

          <footer class="modal-foot">
            <span class="foot-hint">
              权重路径需为 AI 引擎容器内可访问的绝对路径，保存后由后端下发给推理服务
            </span>
            <div class="foot-actions">
              <button class="btn-ghost" :disabled="saving" @click="closeForm">取消</button>
              <button class="btn-primary" :disabled="!canSave" @click="submitForm">
                {{ saving ? '保存中...' : '保存' }}
              </button>
            </div>
          </footer>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import ModelSelector from '@/components/ModelSelector.vue'
import StatisticsChart from '@/components/StatisticsChart.vue'
import {
  activateModel,
  createModel,
  deactivateModel,
  deleteModel,
  getEngineModelStatus,
  getModelMetrics,
  listModels,
  parseClassConfig,
  updateModel,
  type EngineModelStatus,
  type ModelMetrics,
  type ModelPayload,
  type ModelVersion
} from '@/api/index'

interface ModelForm {
  modelName: string
  version: string
  modelPath: string
  classConfig: string
  precisionValue: number | undefined
  recallValue: number | undefined
  map50: number | undefined
  map5095: number | undefined
  status: 'ENABLED' | 'DISABLED'
}

const loading = ref(false)
const busy = ref(false)
const models = ref<ModelVersion[]>([])
const currentId = ref(0)
const metrics = ref<ModelMetrics | null>(null)
const metricsError = ref('')

const formVisible = ref(false)
const saving = ref(false)
const formError = ref('')
const editing = ref<ModelVersion | null>(null)
const form = reactive<ModelForm>(emptyForm())

const current = computed(() => models.value.find((m) => m.id === currentId.value) ?? null)

/**
 * AI 引擎的权重自检报告。
 *
 * 存在的理由：后端 `model_version` 表说的是"系统声称启用哪个版本"，
 * 而权重文件到底在不在、实际加载的是哪一份，只有 AI 引擎自己知道。
 * 两者不一致时页面会显示"当前模型 wildlife-v1.0"，跑的却是 COCO 预训练权重
 * —— 识别出来的类别与这个版本声明的类别毫无关系。
 */
const engineStatus = ref<EngineModelStatus | null>(null)
const engineError = ref('')

/** 引擎自检认定的"不一致"：回退到了备用权重，或引擎启用的版本与页面选中的版本不同 */
const engineMismatch = computed(() => {
  const status = engineStatus.value
  if (!status) return false
  return (
    status.usingFallback ||
    (current.value != null && status.activeVersion !== current.value.version)
  )
})

const classNames = computed(() => parseClassConfig(current.value?.classConfig))

const classDistribution = computed(() =>
  (metrics.value?.classDistribution ?? []).map((item) => ({
    name: item.className,
    value: item.count
  }))
)

const canSave = computed(
  () => !saving.value && !!form.modelName.trim() && !!form.version.trim() && !!form.modelPath.trim()
)

function emptyForm(): ModelForm {
  return {
    modelName: '',
    version: '',
    modelPath: '',
    classConfig: '',
    precisionValue: undefined,
    recallValue: undefined,
    map50: undefined,
    map5095: undefined,
    status: 'DISABLED'
  }
}

function fmt(value?: number | null): string {
  if (value == null) return '—'
  return value.toFixed(4).replace(/0+$/, '').replace(/\.$/, '')
}

function formatTime(value?: string): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

async function loadModels() {
  loading.value = true
  try {
    const res = await listModels()
    models.value = res.data ?? []
    // 默认选中当前启用的版本
    if (!models.value.some((m) => m.id === currentId.value)) {
      const enabled = models.value.find((m) => m.status === 'ENABLED')
      currentId.value = enabled?.id ?? models.value[0]?.id ?? 0
      await loadMetrics()
    }
  } catch {
    models.value = []
  } finally {
    loading.value = false
  }
}

async function loadMetrics() {
  metrics.value = null
  metricsError.value = ''
  if (!currentId.value) return
  try {
    const res = await getModelMetrics(currentId.value)
    const data = (res.data ?? {}) as unknown as ModelMetrics
    metrics.value = Object.keys(data).length ? data : null
  } catch (err: any) {
    metricsError.value = `指标加载失败：${err?.response?.data?.error || err?.message || '未知错误'}`
  }
}

function onModelChange(model: ModelVersion | null) {
  currentId.value = model?.id ?? 0
  loadMetrics()
  // 自检结论与"问的是哪个版本"有关，换版本必须重新问
  loadEngineStatus()
}

/** 启用是互斥操作，成功后重新拉列表刷新各版本状态。 */
async function onActivated() {
  await loadModels()
  await loadMetrics()
}

async function doActivate(model: ModelVersion) {
  busy.value = true
  try {
    await activateModel(model.id)
    await loadModels()
  } catch (err: any) {
    window.alert(`启用失败：${err?.response?.data?.error || err?.message || '未知错误'}`)
  } finally {
    busy.value = false
  }
}

async function doDeactivate(model: ModelVersion) {
  busy.value = true
  try {
    await deactivateModel(model.id)
    await loadModels()
  } catch (err: any) {
    window.alert(`操作失败：${err?.response?.data?.error || err?.message || '未知错误'}`)
  } finally {
    busy.value = false
  }
}

async function doDelete(model: ModelVersion) {
  if (!window.confirm(`删除模型版本 ${model.version}？已被识别任务使用的版本无法删除。`)) return
  busy.value = true
  try {
    await deleteModel(model.id)
    currentId.value = 0
    await loadModels()
  } catch (err: any) {
    window.alert(`删除失败：${err?.response?.data?.error || err?.message || '未知错误'}`)
  } finally {
    busy.value = false
  }
}

function openForm(model?: ModelVersion) {
  editing.value = model ?? null
  formError.value = ''
  saving.value = false
  Object.assign(
    form,
    model
      ? {
          modelName: model.modelName ?? '',
          version: model.version ?? '',
          modelPath: model.modelPath ?? '',
          classConfig: model.classConfig ?? '',
          precisionValue: model.precisionValue,
          recallValue: model.recallValue,
          map50: model.map50,
          map5095: model.map5095,
          status: model.status ?? 'DISABLED'
        }
      : emptyForm()
  )
  formVisible.value = true
}

function closeForm() {
  formVisible.value = false
  formError.value = ''
}

async function submitForm() {
  if (!canSave.value) return
  saving.value = true
  formError.value = ''
  const payload: ModelPayload = {
    modelName: form.modelName.trim(),
    version: form.version.trim(),
    modelPath: form.modelPath.trim(),
    classConfig: form.classConfig.trim() || undefined,
    precisionValue: form.precisionValue,
    recallValue: form.recallValue,
    map50: form.map50,
    map5095: form.map5095,
    status: form.status
  }

  try {
    if (editing.value) {
      await updateModel(editing.value.id, payload)
    } else {
      const res = await createModel(payload)
      currentId.value = res.data?.id ?? 0
    }
    formVisible.value = false
    await loadModels()
    await loadMetrics()
  } catch (err: any) {
    formError.value = `保存失败：${err?.response?.data?.error || err?.message || '未知错误'}`
  } finally {
    saving.value = false
  }
}

/**
 * 拉取引擎的权重自检报告。
 *
 * 把页面选中的版本号一起传过去 —— 引擎据此判断"这个版本期望的权重"
 * 与"实际会加载的权重"是否是同一个文件。
 */
async function loadEngineStatus() {
  engineError.value = ''
  try {
    const res = await getEngineModelStatus(current.value?.version)
    engineStatus.value = res.data ?? null
  } catch (err: any) {
    engineStatus.value = null
    // 引擎不可达不算致命（版本列表与指标仍可用），但不能假装一切正常
    engineError.value = `AI 引擎自检不可用：${err?.response?.data?.error || err?.message || '未知错误'}`
  }
}

function reloadAll() {
  loadModels().then(loadMetrics).then(loadEngineStatus)
}

onMounted(reloadAll)
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
}

.model-body {
  display: grid;
  grid-template-columns: 420px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}
.selector-card { padding: 14px 16px; }
.metric-body { padding: 14px 16px; }

/* ── 指标 ── */
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.metric-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 11px 13px;
  border-radius: 6px;
  background: var(--bg-admin-input);
  border: 1px solid var(--border-admin-table);
}
.metric-value {
  font-size: 17px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--color-primary);
}
.metric-label { font-size: 11px; color: var(--text-muted); }

.kv-list {
  margin-top: 14px;
  border-top: 1px solid var(--border-divider);
}
.kv {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px solid var(--border-divider);
  font-size: 12.5px;
}
.kv-label { width: 84px; flex-shrink: 0; color: var(--text-muted); }
.kv-value {
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mono { font-family: Consolas, Monaco, monospace; font-size: 11.5px; }

.op-row {
  display: flex;
  gap: 8px;
  margin-top: 16px;
  flex-wrap: wrap;
}

/* ── 底部 ── */
.bottom-body {
  display: grid;
  grid-template-columns: 380px minmax(0, 1fr);
  gap: 14px;
  margin-top: 14px;
  align-items: start;
}
.class-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 12px 16px;
  list-style: none;
  max-height: 340px;
  overflow-y: auto;
}
.class-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 12px;
  background: var(--bg-admin-input);
  border: 1px solid var(--border-admin-table);
}
.class-idx { font-size: 10.5px; color: var(--text-dim); }
.class-name { font-size: 12px; color: var(--text-secondary); }
.chart-wrap { padding: 8px 12px 4px; }

/* ── 徽标 ── */
.badge {
  display: inline-block;
  padding: 2px 9px;
  border-radius: 9px;
  font-size: 11px;
}
.st-enabled { background: var(--bg-badge-success); color: var(--color-success); }
.st-disabled { background: var(--bg-badge-warning); color: var(--text-warning); }

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
.btn-ghost.danger:hover:not(:disabled) { border-color: var(--color-danger); color: var(--text-danger); }
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
.state.small { padding: 26px 16px; }
.state-icon { font-size: 20px; }

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
  max-width: 720px;
  max-height: 88vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-admin-card);
  border: 1px solid var(--border-primary);
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.35);
}
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
.modal-body { flex: 1; overflow-y: auto; padding: 16px 18px; }
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

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px 14px;
}
.field { display: flex; flex-direction: column; gap: 5px; }
.field.span-2 { grid-column: span 2; }
.field-label {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 11.5px;
  color: var(--text-muted);
}
.field-hint { font-size: 10.5px; color: var(--text-dim); }
input,
select,
textarea {
  padding: 7px 9px;
  background: var(--bg-admin-input);
  border: 1px solid var(--border-admin-input);
  border-radius: 4px;
  color: var(--text-primary);
  font-size: 12.5px;
  outline: none;
  font-family: inherit;
  resize: vertical;
}
input:focus,
select:focus,
textarea:focus { border-color: var(--color-admin-focus); }

.message {
  margin-top: 12px;
  padding: 8px 10px;
  border-radius: 4px;
  font-size: 12px;
}
.message.error { background: var(--bg-badge-danger); color: var(--text-danger); }

@media (max-width: 1440px) {
  .model-body { grid-template-columns: 1fr; }
  .bottom-body { grid-template-columns: 1fr; }
  .metric-grid { grid-template-columns: repeat(2, 1fr); }
}
/* ── 权重一致性告警 ─────────────────────────────────────────────────────── */
/* 用主题变量而非硬编码颜色，亮/暗两套主题下都能读 */
.weight-alert {
  margin-bottom: 16px;
  padding: 14px 16px;
  border-radius: 10px;
  border: 1px solid var(--border-primary);
  background: var(--bg-admin-card);
  color: var(--text-secondary);
  font-size: 12.5px;
  line-height: 1.75;
}
.weight-alert.warn {
  border-color: var(--color-warning);
  background: var(--bg-badge-warning);
  color: var(--text-warning);
}
.weight-alert.ok {
  border-color: var(--color-success);
  background: var(--bg-badge-success);
  color: var(--color-success);
}
.weight-alert .alert-head {
  margin-bottom: 4px;
  font-size: 13px;
  font-weight: 600;
}
.weight-alert .alert-body p { margin: 4px 0; }
.weight-alert .alert-body b { font-weight: 700; }
.weight-alert code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--bg-admin-input);
  color: var(--text-primary);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11.5px;
}
.weight-alert .alert-consequence { font-weight: 600; }
.weight-alert .alert-issues { margin: 6px 0 0; padding-left: 18px; }
</style>
