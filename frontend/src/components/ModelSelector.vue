<template>
  <div class="model-selector" :class="{ compact }">
    <!-- 紧凑模式：下拉选择 -->
    <template v-if="compact">
      <label class="compact-label" v-if="label">{{ label }}</label>
      <select
        class="compact-select"
        :value="modelValue ?? 0"
        :disabled="loading || !items.length"
        @change="onSelectChange"
      >
        <option :value="0">{{ loading ? '加载中...' : items.length ? '请选择模型版本' : '暂无可用模型' }}</option>
        <option v-for="m in items" :key="m.id" :value="m.id">
          {{ m.version }}{{ m.status === 'ENABLED' ? '（启用中）' : '' }}{{ m.map50 != null ? ` · mAP50 ${fmt(m.map50)}` : '' }}
        </option>
      </select>
    </template>

    <!-- 完整模式：卡片列表 -->
    <template v-else>
      <header class="selector-head" v-if="label || items.length">
        <span class="head-label">{{ label }}</span>
        <span class="head-meta">
          <span v-if="selected" class="current">当前：{{ selected.version }}</span>
          <button class="btn-refresh" :disabled="loading" @click="load">刷新</button>
        </span>
      </header>

      <p v-if="loading" class="tip">模型列表加载中...</p>
      <p v-else-if="!items.length" class="tip">暂无已注册模型，请先在模型管理中登记权重</p>

      <ul v-else class="model-list">
        <li
          v-for="m in items"
          :key="m.id"
          class="model-item"
          :class="{ active: m.id === modelValue, enabled: m.status === 'ENABLED' }"
          @click="pick(m)"
        >
          <div class="mi-main">
            <div class="mi-title">
              <span class="radio" :class="{ on: m.id === modelValue }"></span>
              <span class="version">{{ m.version }}</span>
              <span v-if="m.status === 'ENABLED'" class="tag enabled">启用中</span>
              <span v-else class="tag disabled">未启用</span>
            </div>
            <div class="mi-sub">
              <span class="model-name">{{ m.modelName }}</span>
              <span class="path mono" :title="m.modelPath">{{ m.modelPath }}</span>
            </div>
          </div>

          <div v-if="showMetrics" class="mi-metrics">
            <div class="metric">
              <span class="metric-value">{{ fmt(m.map50) }}</span>
              <span class="metric-label">mAP50</span>
            </div>
            <div class="metric">
              <span class="metric-value">{{ fmt(m.map5095) }}</span>
              <span class="metric-label">mAP50-95</span>
            </div>
            <div class="metric">
              <span class="metric-value">{{ fmt(m.precisionValue) }}</span>
              <span class="metric-label">Precision</span>
            </div>
            <div class="metric">
              <span class="metric-value">{{ fmt(m.recallValue) }}</span>
              <span class="metric-label">Recall</span>
            </div>
            <div v-if="classCount(m)" class="metric">
              <span class="metric-value">{{ classCount(m) }}</span>
              <span class="metric-label">类别数</span>
            </div>
          </div>

          <div class="mi-actions">
            <button
              v-if="allowActivate && m.status !== 'ENABLED'"
              class="btn-activate"
              :disabled="activatingId === m.id"
              @click.stop="activate(m)"
            >{{ activatingId === m.id ? '启用中...' : '启用' }}</button>
            <span v-else-if="m.status === 'ENABLED'" class="active-mark">✓ 已启用</span>
          </div>
        </li>
      </ul>

      <div v-if="errorMsg" class="message error">{{ errorMsg }}</div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  activateModel,
  listModels,
  parseClassConfig,
  type ModelVersion
} from '@/api/index'

const props = withDefaults(
  defineProps<{
    /** v-model 绑定选中的模型 ID。 */
    modelValue?: number
    /** 外部直接传入模型列表，传入后不再自行请求。 */
    models?: ModelVersion[]
    label?: string
    /** 是否显示"启用"按钮（启用会把其他模型置为停用）。 */
    allowActivate?: boolean
    showMetrics?: boolean
    /** 紧凑模式渲染成下拉框。 */
    compact?: boolean
    /** 未指定 modelValue 时，自动选中处于启用状态的模型。 */
    autoSelectActive?: boolean
  }>(),
  {
    modelValue: 0,
    models: () => [],
    label: '识别模型',
    allowActivate: false,
    showMetrics: true,
    compact: false,
    autoSelectActive: true
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: number): void
  (e: 'change', model: ModelVersion | null): void
  (e: 'activated', model: ModelVersion): void
}>()

const loading = ref(false)
const activatingId = ref(0)
const errorMsg = ref('')
const remoteList = ref<ModelVersion[]>([])

const items = computed<ModelVersion[]>(() => (props.models.length ? props.models : remoteList.value))

const selected = computed<ModelVersion | null>(
  () => items.value.find((m) => m.id === props.modelValue) ?? null
)

function fmt(value?: number | null): string {
  if (value == null) return '—'
  return value.toFixed(4).replace(/0+$/, '').replace(/\.$/, '')
}

function classCount(m: ModelVersion): number {
  return parseClassConfig(m.classConfig).length
}

async function load() {
  if (props.models.length) return
  loading.value = true
  errorMsg.value = ''
  try {
    const res = await listModels()
    remoteList.value = res.data ?? []
    applyDefaultSelection()
  } catch (err: any) {
    errorMsg.value = `模型列表加载失败：${err?.response?.data?.error || err?.message || '未知错误'}`
  } finally {
    loading.value = false
  }
}

/** 外部没指定选择时，默认落在当前启用的模型上。 */
function applyDefaultSelection() {
  if (!props.autoSelectActive) return
  if (props.modelValue) return
  const enabled = remoteList.value.find((m) => m.status === 'ENABLED')
  const fallback = enabled ?? remoteList.value[0]
  if (!fallback) return
  emit('update:modelValue', fallback.id)
  emit('change', fallback)
}

watch(
  () => props.models,
  () => {
    applyDefaultSelection()
  }
)

function pick(m: ModelVersion) {
  if (m.id === props.modelValue) return
  emit('update:modelValue', m.id)
  emit('change', m)
}

function onSelectChange(e: Event) {
  const id = Number((e.target as HTMLSelectElement).value)
  const found = items.value.find((m) => m.id === id) ?? null
  emit('update:modelValue', id)
  emit('change', found)
}

async function activate(m: ModelVersion) {
  activatingId.value = m.id
  errorMsg.value = ''
  try {
    await activateModel(m.id)
    // 启用是互斥操作，本地同步状态，避免整表重拉
    remoteList.value = remoteList.value.map((item) => ({
      ...item,
      status: item.id === m.id ? 'ENABLED' : 'DISABLED'
    }))
    emit('activated', { ...m, status: 'ENABLED' })
  } catch (err: any) {
    errorMsg.value = `启用失败：${err?.response?.data?.error || err?.message || '未知错误'}`
  } finally {
    activatingId.value = 0
  }
}

onMounted(load)

defineExpose({ reload: load })
</script>

<style scoped>
.model-selector { display: flex; flex-direction: column; gap: 10px; }

.selector-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.head-label { font-size: 13px; font-weight: 600; color: var(--text-secondary); }

.head-meta { display: flex; align-items: center; gap: 10px; }
.current { font-size: 12px; color: var(--color-primary); }

.btn-refresh {
  padding: 3px 10px;
  font-size: 12px;
  background: transparent;
  color: var(--text-muted);
  border: 1px solid var(--border-admin-input);
  border-radius: 4px;
  cursor: pointer;
}
.btn-refresh:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
.btn-refresh:disabled { opacity: 0.5; cursor: not-allowed; }

.model-list { list-style: none; display: flex; flex-direction: column; gap: 8px; }

.model-item {
  display: flex;
  /*
   * 必须允许换行。
   *
   * 模型选择卡在模型管理页只有 420px 宽，而一行里要塞下
   * 「版本号 + 启用中标签 + 5 个指标 + 操作按钮」——放不下。
   * 不换行时 mi-metrics / mi-actions 都是 flex-shrink: 0（不能压），
   * 于是唯一可压的 mi-main 被挤成 0 宽，里面的版本号与标签溢出到指标上，
   * 表现为文字重叠。允许换行后：宽的时候还是一行，窄的时候指标自动落到第二行。
   */
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px 14px;
  padding: 12px 14px;
  background: var(--bg-admin-input-alt);
  border: 1px solid var(--border-admin-input);
  border-radius: 8px;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}

.model-item:hover { border-color: var(--color-primary); }
.model-item.active { border-color: var(--color-primary); background: var(--bg-card-active); }
.model-item.enabled .version { color: var(--color-primary); }

/* flex-basis 给下限：宁可行内换行，也不把自己压成 0 宽 */
.mi-main { display: flex; flex-direction: column; gap: 5px; flex: 1 1 168px; min-width: 0; }

.mi-title { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }

.radio {
  width: 13px;
  height: 13px;
  border-radius: 50%;
  border: 1.5px solid var(--border-admin-input);
  flex-shrink: 0;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.radio.on { border-color: var(--color-primary); box-shadow: inset 0 0 0 3px var(--color-primary); }

/* 版本号是不可断的标识，别让它折成 "wildlife-" / "v1.0" 两行 */
.version { font-size: 14px; font-weight: 600; color: var(--text-primary); white-space: nowrap; }

.tag { font-size: 11px; padding: 1px 8px; border-radius: 9px; white-space: nowrap; }
.tag.enabled { background: rgba(76, 175, 80, 0.18); color: #81c784; }
.tag.disabled { background: rgba(120, 144, 156, 0.18); color: var(--text-dim); }

.mi-sub { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
.model-name { font-size: 12px; color: var(--text-muted); flex-shrink: 0; max-width: 50%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.path {
  font-size: 11px;
  color: var(--text-dark);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mono { font-family: ui-monospace, Consolas, monospace; }

/* 指标自身也允许换行：指标多的时候不至于把所在行撑破 */
.mi-metrics { display: flex; flex-wrap: wrap; gap: 6px 14px; flex: 0 1 auto; }

.metric { display: flex; flex-direction: column; align-items: center; gap: 1px; }

.metric-value {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.metric-label { font-size: 10px; color: var(--text-dim); white-space: nowrap; }

/* 换行后按钮可能独占一行，margin-left: auto 保证它始终贴右 */
.mi-actions { flex: 0 0 auto; min-width: 56px; margin-left: auto; text-align: right; }

.btn-activate {
  padding: 4px 12px;
  font-size: 12px;
  background: transparent;
  color: var(--color-primary);
  border: 1px solid var(--border-accent);
  border-radius: 4px;
  cursor: pointer;
}
.btn-activate:hover:not(:disabled) { background: var(--bg-card-active); }
.btn-activate:disabled { opacity: 0.5; cursor: not-allowed; }

.active-mark { font-size: 12px; color: #4caf50; }

.tip { font-size: 12px; color: var(--text-dim); padding: 8px 0; }

.message { padding: 8px 12px; border-radius: 4px; font-size: 12px; }
.message.error { background: rgba(198, 40, 40, 0.15); color: var(--text-danger); border: 1px solid #c62828; }

/* 紧凑模式 */
.compact { flex-direction: row; align-items: center; gap: 10px; }
.compact-label { font-size: 12px; color: var(--text-muted); flex-shrink: 0; }
.compact-select {
  flex: 1;
  padding: 6px 10px;
  background: var(--bg-admin-input-alt);
  border: 1px solid var(--border-admin-input);
  border-radius: 4px;
  color: var(--text-primary);
  font-size: 13px;
  outline: none;
}
.compact-select:focus { border-color: var(--color-admin-focus); }
.compact-select:disabled { opacity: 0.6; }
</style>
