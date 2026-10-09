<template>
  <div class="page">
    <!-- 页头 -->
    <header class="page-head">
      <div class="head-left">
        <h1 class="page-title">统计分析</h1>
        <p class="page-sub">
          从物种构成、保护等级、时间节律与置信度四个维度评估本批次识别结果，用于论文的定量分析
        </p>
      </div>
      <div class="head-actions">
        <div class="segmented">
          <button
            v-for="opt in rangeOptions"
            :key="opt.value"
            class="seg-btn"
            :class="{ on: range === opt.value }"
            @click="changeRange(opt.value)"
          >{{ opt.label }}</button>
        </div>
        <button class="btn-ghost" :disabled="loading" @click="loadAll">刷新</button>
      </div>
    </header>

    <!-- 概览 -->
    <section class="stat-row">
      <div v-for="card in overviewCards" :key="card.label" class="stat-card">
        <span class="stat-value" :class="card.tone">{{ card.value }}</span>
        <span class="stat-label">{{ card.label }}</span>
      </div>
    </section>

    <!-- 图表区 -->
    <div class="grid">
      <!-- 物种分布 -->
      <section class="card span-2">
        <div class="card-head">
          <span>物种分布</span>
          <span class="head-meta">
            共 {{ speciesDistribution.speciesCount }} 种 · 检出 {{ speciesDistribution.total }} 次
          </span>
        </div>
        <div class="chart-wrap">
          <StatisticsChart
            v-if="speciesData.length"
            type="hbar"
            :data="speciesData"
            :height="Math.max(240, speciesData.length * 26)"
            unit="次"
            :loading="loading"
          />
          <div v-else class="state small">暂无识别结果</div>
        </div>
        <ul v-if="speciesDistribution.list.length" class="spec-list">
          <li v-for="item in speciesDistribution.list" :key="item.name" class="spec-item">
            <span class="spec-dot" :style="{ background: colorOfClass(undefined, item.name) }"></span>
            <span class="spec-name" :title="item.name">{{ item.name }}</span>
            <span class="spec-level" :class="levelClass(item.protectionLevel)">{{ item.protectionLevel }}</span>
            <span class="spec-value">{{ item.value }} 次</span>
            <span class="spec-ratio">{{ item.ratio.toFixed(1) }}%</span>
          </li>
        </ul>
      </section>

      <!-- 保护等级分布 -->
      <section class="card">
        <div class="card-head">
          <span>保护等级构成</span>
          <span class="head-meta">按国家保护级别</span>
        </div>
        <div class="chart-wrap">
          <StatisticsChart
            v-if="protectionData.length"
            type="donut"
            :data="protectionData"
            :height="270"
            unit="次"
            :show-legend="true"
          />
          <div v-else class="state small">暂无数据</div>
        </div>
      </section>

      <!-- IUCN -->
      <section class="card">
        <div class="card-head">
          <span>IUCN 受威胁等级</span>
          <span class="head-meta">依据物种保护名录</span>
        </div>
        <div class="chart-wrap">
          <StatisticsChart
            v-if="iucnData.length"
            type="pie"
            :data="iucnData"
            :height="270"
            unit="次"
            :show-legend="true"
          />
          <div v-else class="state small">暂无数据</div>
        </div>
      </section>

      <!-- 趋势 -->
      <section class="card span-2">
        <div class="card-head">
          <span>识别量趋势</span>
          <div class="head-tools">
            <div class="segmented small">
              <button class="seg-btn" :class="{ on: granularity === 'day' }" @click="changeGranularity('day')">
                按天
              </button>
              <button class="seg-btn" :class="{ on: granularity === 'hour' }" @click="changeGranularity('hour')">
                按小时
              </button>
            </div>
          </div>
        </div>
        <div class="chart-wrap">
          <StatisticsChart
            v-if="trendData.length"
            type="area"
            :data="trendData"
            :height="260"
            unit="次"
            :smooth="true"
          />
          <div v-else class="state small">所选时间范围内暂无识别记录</div>
        </div>
      </section>

      <!-- 检出率趋势 -->
      <section class="card span-2">
        <div class="card-head">
          <span>检出率趋势</span>
          <span class="head-meta">
            有检出 {{ detectionRate.detectedImages.toLocaleString('zh-CN') }} /
            识别成功 {{ detectionRate.successImages.toLocaleString('zh-CN') }} 张 ·
            空拍 {{ detectionRate.undetectedImages }} 张
          </span>
        </div>
        <div class="chart-wrap">
          <StatisticsChart
            v-if="detectionRateData.length"
            type="area"
            :data="detectionRateData"
            :height="240"
            unit="%"
            :smooth="true"
          />
          <div v-else class="state small">所选时间范围内暂无识别记录</div>
        </div>
        <p class="progress-note">
          检出率 = 至少检出一个目标的图像数 ÷ 识别成功的图像数，整体
          {{ detectionRate.detectionRate.toFixed(2) }}%；分母不含识别失败与排队中的图像。
          与上方「识别量趋势」口径不同：识别量统计的是目标个数，检出率统计的是图像张数，
          一张图检出多个目标不会让检出率超过 100%。
        </p>
      </section>

      <!-- 置信度分布 -->
      <section class="card span-2">
        <div class="card-head">
          <span>置信度分布</span>
          <span class="head-meta">共 {{ confidenceDistribution.total }} 条结果</span>
        </div>
        <div class="chart-wrap">
          <StatisticsChart
            v-if="confidenceData.length"
            type="bar"
            :data="confidenceData"
            :height="260"
            unit="条"
            color-by="single"
          />
          <div v-else class="state small">暂无数据</div>
        </div>
      </section>

      <!-- 任务状态 -->
      <section class="card">
        <div class="card-head">
          <span>任务状态</span>
          <span class="head-meta">共 {{ taskStatus.total }} 个任务</span>
        </div>
        <div class="chart-wrap">
          <StatisticsChart
            v-if="taskStatusData.length"
            type="donut"
            :data="taskStatusData"
            :height="240"
            unit="个"
            :show-legend="true"
          />
          <div v-else class="state small">暂无任务</div>
        </div>
        <div class="progress-block">
          <div class="progress-head">
            <span>整体识别进度</span>
            <span class="progress-value">{{ taskStatus.progress.toFixed(1) }}%</span>
          </div>
          <div class="bar">
            <div class="bar-fill" :style="{ width: Math.min(100, taskStatus.progress) + '%' }"></div>
          </div>
          <p class="progress-note">
            已处理 {{ taskStatus.processedImages.toLocaleString('zh-CN') }} /
            {{ taskStatus.totalImages.toLocaleString('zh-CN') }} 张图像
          </p>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import StatisticsChart from '@/components/StatisticsChart.vue'
import {
  colorOfClass,
  getClassStatistics,
  getConfidenceDistribution,
  getDetectionRateStatistics,
  getOverview,
  getProtectionDistribution,
  getTaskStatusStatistics,
  getTrendStatistics,
  TASK_STATUS_LABEL,
  type ClassDistribution,
  type DetectionRateResult,
  type NameValue,
  type StatisticsOverview,
  type TaskStatusStatistics,
  type TrendPoint
} from '@/api/index'

type RangeKey = '' | 'today' | '7d' | '30d' | '90d' | '1y'

const rangeOptions: Array<{ value: RangeKey; label: string }> = [
  { value: '', label: '全部' },
  { value: 'today', label: '今日' },
  { value: '7d', label: '近 7 天' },
  { value: '30d', label: '近 30 天' },
  { value: '90d', label: '近 90 天' },
  { value: '1y', label: '近一年' }
]

const loading = ref(false)
const range = ref<RangeKey>('')
const granularity = ref<'hour' | 'day'>('day')

const overview = ref<StatisticsOverview>({
  imageCount: 0,
  taskCount: 0,
  resultCount: 0,
  speciesCount: 0,
  protectedSpeciesCount: 0,
  pendingReviewCount: 0,
  avgConfidence: 0,
  successImageCount: 0,
  detectedImageCount: 0,
  undetectedImageCount: 0,
  detectionRate: 0
})

const speciesDistribution = ref<ClassDistribution>({
  total: 0,
  speciesCount: 0,
  list: []
})

const protectionDistribution = ref<{ byProtectionLevel: NameValue[]; byIucn: NameValue[] }>({
  byProtectionLevel: [],
  byIucn: []
})

const trend = ref<TrendPoint[]>([])

/** 检出率趋势：分子/分母都是图像张数，与 trend（目标个数）不是一个口径 */
const detectionRate = ref<DetectionRateResult>({
  granularity: 'day',
  range: 'all',
  successImages: 0,
  detectedImages: 0,
  undetectedImages: 0,
  detectionRate: 0,
  list: []
})

const confidenceDistribution = ref<{
  total: number
  list: Array<{ range: string; value: number; ratio: number }>
}>({ total: 0, list: [] })

const taskStatus = ref<TaskStatusStatistics>({
  total: 0,
  list: [],
  totalImages: 0,
  processedImages: 0,
  progress: 0
})

const overviewCards = computed(() => [
  { label: '图像总数', value: overview.value.imageCount.toLocaleString('zh-CN'), tone: '' },
  { label: '识别任务', value: overview.value.taskCount.toLocaleString('zh-CN'), tone: '' },
  { label: '识别结果', value: overview.value.resultCount.toLocaleString('zh-CN'), tone: 'info' },
  { label: '检出率', value: overview.value.detectionRate.toFixed(2) + '%', tone: 'ok' },
  { label: '涉及物种', value: overview.value.speciesCount, tone: 'info' },
  { label: '重点保护物种', value: overview.value.protectedSpeciesCount, tone: 'warn' },
  { label: '待复核结果', value: overview.value.pendingReviewCount.toLocaleString('zh-CN'), tone: 'warn' },
  { label: '平均置信度', value: `${(overview.value.avgConfidence * 100).toFixed(1)}%`, tone: 'ok' }
])

const speciesData = computed(() =>
  speciesDistribution.value.list.map((item) => ({
    name: item.name,
    value: item.value,
    extra: { protectionLevel: item.protectionLevel }
  }))
)

const protectionData = computed(() => protectionDistribution.value.byProtectionLevel)

const iucnData = computed(() => protectionDistribution.value.byIucn)

const trendData = computed(() =>
  trend.value.map((item) => ({ name: item.time, value: item.value }))
)

const detectionRateData = computed(() =>
  detectionRate.value.list.map((item) => ({ name: item.time, value: item.detectionRate }))
)

const confidenceData = computed(() =>
  confidenceDistribution.value.list.map((item) => ({ name: item.range, value: item.value }))
)

const taskStatusData = computed(() =>
  taskStatus.value.list
    .filter((item) => item.value > 0)
    .map((item) => ({
      name: TASK_STATUS_LABEL[item.name as keyof typeof TASK_STATUS_LABEL] ?? item.name,
      value: item.value
    }))
)

function levelClass(level: string): string {
  if (level === '国家一级') return 'lv-1'
  if (level === '国家二级') return 'lv-2'
  if (level === '三有') return 'lv-3'
  return 'lv-0'
}

async function loadOverview() {
  try {
    const res = await getOverview(range.value || undefined)
    overview.value = res.data ?? overview.value
  } catch {
    /* 保留原值 */
  }
}

async function loadSpecies() {
  try {
    const res = await getClassStatistics({ range: range.value || undefined, top: 15 })
    speciesDistribution.value = res.data ?? speciesDistribution.value
  } catch {
    speciesDistribution.value = { total: 0, speciesCount: 0, list: [] }
  }
}

async function loadProtection() {
  try {
    const res = await getProtectionDistribution(range.value || undefined)
    protectionDistribution.value = {
      byProtectionLevel: res.data?.byProtectionLevel ?? [],
      byIucn: res.data?.byIucn ?? []
    }
  } catch {
    protectionDistribution.value = { byProtectionLevel: [], byIucn: [] }
  }
}

async function loadTrend() {
  try {
    const res = await getTrendStatistics({
      granularity: granularity.value,
      range: range.value || undefined
    })
    trend.value = res.data?.list ?? []
  } catch {
    trend.value = []
  }
}

async function loadDetectionRate() {
  try {
    const res = await getDetectionRateStatistics({
      granularity: granularity.value,
      range: range.value || undefined
    })
    detectionRate.value = res.data ?? detectionRate.value
  } catch {
    detectionRate.value = { ...detectionRate.value, list: [] }
  }
}

async function loadConfidence() {
  try {
    const res = await getConfidenceDistribution()
    confidenceDistribution.value = res.data ?? { total: 0, list: [] }
  } catch {
    confidenceDistribution.value = { total: 0, list: [] }
  }
}

async function loadTaskStatus() {
  try {
    const res = await getTaskStatusStatistics()
    taskStatus.value = res.data ?? taskStatus.value
  } catch {
    /* 保留原值 */
  }
}

async function loadAll() {
  loading.value = true
  try {
    await Promise.all([
      loadOverview(),
      loadSpecies(),
      loadProtection(),
      loadTrend(),
      loadDetectionRate(),
      loadConfidence(),
      loadTaskStatus()
    ])
  } finally {
    loading.value = false
  }
}

function changeRange(value: RangeKey) {
  range.value = value
  loadAll()
}

function changeGranularity(value: 'hour' | 'day') {
  granularity.value = value
  loadTrend()
  loadDetectionRate()
}

onMounted(loadAll)
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
  max-width: 820px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}
.head-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }

.segmented {
  display: inline-flex;
  padding: 2px;
  border-radius: 6px;
  background: var(--bg-admin-input);
  border: 1px solid var(--border-admin-input);
}
.seg-btn {
  padding: 5px 12px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--text-muted);
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.2s;
}
.seg-btn:hover { color: var(--text-primary); }
.seg-btn.on {
  background: var(--color-primary);
  color: var(--text-white);
}
.segmented.small .seg-btn { padding: 3px 10px; font-size: 11.5px; }

/* ── 概览 ── */
.stat-row {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}
.stat-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 13px 15px;
  background: var(--bg-admin-card);
  border: 1px solid var(--border-primary);
  border-radius: 8px;
}
.stat-value {
  font-size: 19px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.stat-value.info { color: var(--color-primary); }
.stat-value.warn { color: var(--color-warning); }
.stat-value.ok { color: var(--color-success); }
.stat-label { font-size: 11px; color: var(--text-muted); }

/* ── 卡片与栅格 ── */
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
.head-meta { font-size: 11.5px; font-weight: 400; color: var(--text-muted); }
.head-tools { display: flex; align-items: center; gap: 8px; }

.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}
.span-2 { grid-column: span 2; }
.chart-wrap { padding: 10px 14px 4px; }

/* ── 物种清单 ── */
.spec-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px 14px;
  padding: 10px 16px 16px;
  list-style: none;
  border-top: 1px solid var(--border-divider);
}
.spec-item {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--text-secondary);
}
.spec-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.spec-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.spec-level {
  padding: 1px 7px;
  border-radius: 8px;
  font-size: 10.5px;
  white-space: nowrap;
}
.lv-1 { background: var(--bg-badge-danger); color: var(--text-danger); }
.lv-2 { background: var(--bg-badge-warning); color: var(--text-warning); }
.lv-3 { background: var(--bg-badge-success); color: var(--color-primary); }
.lv-0 { background: var(--bg-badge-purple); color: var(--text-muted); }
.spec-value {
  font-variant-numeric: tabular-nums;
  color: var(--text-primary);
  width: 52px;
  text-align: right;
}
.spec-ratio {
  width: 50px;
  text-align: right;
  font-variant-numeric: tabular-nums;
  color: var(--text-muted);
  font-size: 11.5px;
}

/* ── 进度 ── */
.progress-block {
  padding: 12px 16px 16px;
  border-top: 1px solid var(--border-divider);
}
.progress-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--text-secondary);
}
.progress-value { color: var(--color-primary); font-variant-numeric: tabular-nums; }
.bar {
  height: 7px;
  border-radius: 4px;
  background: var(--bg-track);
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: 4px;
  background: linear-gradient(90deg, var(--color-primary), var(--color-purple));
  transition: width 0.5s ease;
}
.progress-note {
  margin-top: 8px;
  font-size: 11.5px;
  color: var(--text-muted);
}

/* ── 按钮 ── */
.btn-ghost {
  padding: 7px 14px;
  font-size: 12.5px;
  background: transparent;
  color: var(--text-secondary);
  border: 1px solid var(--border-admin-input);
  border-radius: 4px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.2s;
}
.btn-ghost:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
.btn-ghost:disabled { opacity: 0.45; cursor: not-allowed; }

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
.state.small { padding: 60px 16px; }

@media (max-width: 1500px) {
  .stat-row { grid-template-columns: repeat(4, 1fr); }
}
@media (max-width: 1200px) {
  .grid { grid-template-columns: 1fr; }
  .span-2 { grid-column: span 1; }
  .spec-list { grid-template-columns: 1fr; }
}
</style>
