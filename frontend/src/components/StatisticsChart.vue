<template>
  <div class="stat-chart">
    <header v-if="title || showValueSummary" class="chart-head">
      <span class="chart-title" v-if="title">{{ title }}</span>
      <span class="chart-summary" v-if="showValueSummary">
        共 {{ total.toLocaleString('zh-CN') }} {{ unit }}
      </span>
    </header>

    <div v-if="loading" class="chart-state">
      <span class="spinner"></span>
      <span>数据加载中...</span>
    </div>

    <div v-else-if="!data.length" class="chart-state">
      <span class="state-icon">📊</span>
      <span>{{ emptyText }}</span>
    </div>

    <div v-show="!loading && data.length" ref="chartEl" class="chart-canvas" :style="{ height: height + 'px' }"></div>
  </div>
</template>

<script lang="ts">
/** 图表统一数据项：name 作类目，value 作数值，extra 可挂附加信息（如保护等级）。 */
export interface ChartDatum {
  name: string
  value: number
  extra?: Record<string, unknown>
}

export type ChartType = 'bar' | 'hbar' | 'line' | 'area' | 'pie' | 'donut'
</script>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = withDefaults(
  defineProps<{
    type?: ChartType
    data?: ChartDatum[]
    title?: string
    loading?: boolean
    height?: number
    /** 数值单位，用于 tooltip 与汇总文案。 */
    unit?: string
    /** 颜色策略：single 统一主色，palette 逐项取色板。 */
    colorBy?: 'single' | 'palette'
    showLegend?: boolean
    /** 折线 / 面积图是否平滑。 */
    smooth?: boolean
    /** 是否在标题右侧显示数值汇总。 */
    showValueSummary?: boolean
    emptyText?: string
    /** 逃生口：传入后与内置 option 深合并，可覆盖任意配置。 */
    option?: Record<string, unknown>
  }>(),
  {
    type: 'bar',
    data: () => [],
    title: '',
    loading: false,
    height: 280,
    unit: '个',
    colorBy: 'palette',
    showLegend: false,
    smooth: true,
    showValueSummary: false,
    emptyText: '暂无数据',
    option: undefined
  }
)

const emit = defineEmits<{
  (e: 'chart-click', datum: ChartDatum): void
  (e: 'ready', chart: echarts.ECharts): void
}>()

interface ThemeColors {
  primary: string
  text: string
  muted: string
  dim: string
  border: string
  surface: string
  track: string
  palette: string[]
}

const chartEl = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null
let themeObserver: MutationObserver | null = null

const total = computed(() => props.data.reduce((sum, d) => sum + (Number(d.value) || 0), 0))

/** 兜底色板：仅当主题没有声明 --chart-N 变量时使用。 */
const FALLBACK_PALETTE = [
  '#00e5ff',
  '#7c4dff',
  '#4caf50',
  '#ff9800',
  '#e91e63',
  '#00bcd4',
  '#ffc107',
  '#8bc34a',
  '#f44336',
  '#3f51b5',
  '#009688',
  '#ff5722'
]

/** 从当前主题的 CSS 变量里取真实颜色值（图表 canvas 不认 var()）。 */
function readTheme(): ThemeColors {
  const style = getComputedStyle(document.documentElement)
  const pick = (name: string, fallback: string) => {
    const v = style.getPropertyValue(name).trim()
    return v || fallback
  }
  // 色板也走变量，这样切换主题时图表配色跟着变
  const palette = FALLBACK_PALETTE.map((fb, i) => pick(`--chart-${i + 1}`, fb))
  return {
    primary: pick('--color-primary', '#00e5ff'),
    text: pick('--text-primary', '#e3f2fd'),
    muted: pick('--text-muted', '#78909c'),
    dim: pick('--text-dim', '#546e7a'),
    border: pick('--border-primary', '#1e3a5f'),
    surface: pick('--bg-admin-card', '#0d1f33'),
    track: pick('--bg-admin-input', '#1a2e44'),
    palette
  }
}

function colorAt(index: number, theme: ThemeColors): string {
  if (props.colorBy === 'single') return theme.primary
  return theme.palette[index % theme.palette.length]
}

function buildOption(theme: ThemeColors): Record<string, unknown> {
  const names = props.data.map((d) => d.name)
  const values = props.data.map((d) => Number(d.value) || 0)
  const isCategoryAxis = props.type === 'bar' || props.type === 'hbar' || props.type === 'line' || props.type === 'area'

  const tooltip = {
    trigger: isCategoryAxis ? 'axis' : 'item',
    backgroundColor: theme.surface,
    borderColor: theme.border,
    borderWidth: 1,
    textStyle: { color: theme.text, fontSize: 12 },
    axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(255,255,255,0.04)' } },
    formatter: (params: any) => {
      const list = Array.isArray(params) ? params : [params]
      const head = isCategoryAxis ? `<b>${list[0]?.axisValue ?? ''}</b>` : ''
      const lines = list.map((p: any) => {
        const extra = props.data[p.dataIndex]?.extra
        const level = extra && typeof extra.protectionLevel === 'string' ? ` · ${extra.protectionLevel}` : ''
        const ratio = total.value > 0 ? `（${((Number(p.value) / total.value) * 100).toFixed(1)}%）` : ''
        return `${p.marker ?? ''}${p.name ?? ''}：${Number(p.value).toLocaleString('zh-CN')} ${props.unit}${ratio}${level}`
      })
      return [head, ...lines].filter(Boolean).join('<br/>')
    }
  }

  const legend = {
    show: props.showLegend,
    bottom: 0,
    textStyle: { color: theme.muted, fontSize: 11 },
    itemWidth: 10,
    itemHeight: 8
  }

  const base: Record<string, unknown> = {
    backgroundColor: 'transparent',
    color: theme.palette,
    tooltip,
    legend
  }

  if (props.type === 'pie' || props.type === 'donut') {
    return {
      ...base,
      series: [
        {
          type: 'pie',
          radius: props.type === 'donut' ? ['46%', '70%'] : '68%',
          center: ['50%', '48%'],
          avoidLabelOverlap: true,
          itemStyle: { borderColor: theme.surface, borderWidth: 2 },
          label: { color: theme.muted, fontSize: 11, formatter: '{b} {d}%' },
          labelLine: { lineStyle: { color: theme.border } },
          data: props.data.map((d, i) => ({
            name: d.name,
            value: Number(d.value) || 0,
            itemStyle: { color: colorAt(i, theme) }
          }))
        }
      ]
    }
  }

  if (props.type === 'hbar') {
    return {
      ...base,
      grid: { left: 8, right: 24, top: 12, bottom: props.showLegend ? 26 : 8, containLabel: true },
      xAxis: {
        type: 'value',
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: theme.dim, fontSize: 11 },
        splitLine: { lineStyle: { color: theme.border, type: 'dashed', opacity: 0.5 } }
      },
      yAxis: {
        type: 'category',
        inverse: true,
        data: names,
        axisLine: { lineStyle: { color: theme.border } },
        axisTick: { show: false },
        axisLabel: { color: theme.muted, fontSize: 11 }
      },
      series: [
        {
          type: 'bar',
          barMaxWidth: 14,
          itemStyle: { borderRadius: [0, 3, 3, 0] },
          label: { show: true, position: 'right', color: theme.muted, fontSize: 11 },
          data: values.map((v, i) => ({ value: v, itemStyle: { color: colorAt(i, theme) } }))
        }
      ]
    }
  }

  const isLine = props.type === 'line' || props.type === 'area'

  const series: Record<string, unknown> = {
    type: isLine ? 'line' : 'bar',
    smooth: isLine ? props.smooth : undefined,
    symbolSize: 6,
    barMaxWidth: 22,
    itemStyle: { borderRadius: isLine ? 0 : [3, 3, 0, 0] },
    lineStyle: isLine ? { width: 2, color: theme.primary } : undefined,
    areaStyle:
      props.type === 'area'
        ? {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: theme.primary + '66' },
                { offset: 1, color: theme.primary + '05' }
              ]
            }
          }
        : undefined,
    data: isLine
      ? values
      : values.map((v, i) => ({ value: v, itemStyle: { color: colorAt(i, theme) } }))
  }

  if (isLine) {
    series.itemStyle = { color: theme.primary }
  }

  return {
    ...base,
    grid: { left: 8, right: 16, top: 16, bottom: props.showLegend ? 26 : 8, containLabel: true },
    xAxis: {
      type: 'category',
      data: names,
      axisLine: { lineStyle: { color: theme.border } },
      axisTick: { show: false },
      axisLabel: { color: theme.muted, fontSize: 11, interval: 0, rotate: names.length > 8 ? 30 : 0 }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: theme.dim, fontSize: 11 },
      splitLine: { lineStyle: { color: theme.border, type: 'dashed', opacity: 0.5 } }
    },
    series: [series]
  }
}

function deepMerge(
  base: Record<string, unknown>,
  override?: Record<string, unknown>
): Record<string, unknown> {
  if (!override) return base
  const out = { ...base }
  Object.keys(override).forEach((key) => {
    const a = out[key]
    const b = override[key]
    if (a && b && typeof a === 'object' && typeof b === 'object' && !Array.isArray(a) && !Array.isArray(b)) {
      out[key] = deepMerge(a as Record<string, unknown>, b as Record<string, unknown>)
    } else {
      out[key] = b
    }
  })
  return out
}

function render() {
  if (!chart) return
  const option = deepMerge(buildOption(readTheme()), props.option)
  // notMerge：数据集变化时整份替换，避免旧 serie 残留
  chart.setOption(option as unknown as echarts.EChartsOption, { notMerge: true })
}

function init() {
  if (!chartEl.value || chart) return
  chart = echarts.init(chartEl.value)
  chart.on('click', (params: any) => {
    const datum = props.data[params.dataIndex]
    if (datum) emit('chart-click', datum)
  })
  render()
  emit('ready', chart)
}

const handleResize = () => chart?.resize()

onMounted(() => {
  if (props.data.length && !props.loading) init()

  if (chartEl.value && 'ResizeObserver' in window) {
    resizeObserver = new ResizeObserver(handleResize)
    resizeObserver.observe(chartEl.value)
  } else {
    window.addEventListener('resize', handleResize)
  }

  // 主题切换时重算颜色（canvas 不认 CSS 变量，需读出后再注入）
  themeObserver = new MutationObserver(() => {
    if (!props.data.length) return
    // 等 CSS 变量落盘后再读色，避免读到过渡中的中间值
    setTimeout(() => {
      if (!chart) init()
      else render()
    }, 60)
  })
  themeObserver.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['data-theme']
  })
})

watch(
  () => [props.data, props.type, props.option, props.colorBy, props.showLegend, props.smooth],
  () => {
    if (!props.data.length || props.loading) return
    if (!chart) init()
    else render()
  },
  { deep: true }
)

watch(
  () => props.loading,
  (busy) => {
    if (busy || !props.data.length) return
    // 从 loading 切回时容器刚 v-show 显示，需要下一帧再初始化
    setTimeout(() => {
      if (!chart) init()
      else {
        chart.resize()
        render()
      }
    }, 0)
  }
)

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  themeObserver?.disconnect()
  themeObserver = null
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
  chart = null
})

defineExpose({ resize: handleResize, getInstance: () => chart })
</script>

<style scoped>
.stat-chart {
  background: var(--bg-admin-card);
  border: 1px solid var(--border-primary);
  border-radius: 10px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.chart-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.chart-title { font-size: 13px; font-weight: 600; color: var(--text-secondary); }
.chart-summary { font-size: 12px; color: var(--text-dim); }

.chart-canvas { width: 100%; min-height: 120px; }

.chart-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 160px;
  font-size: 13px;
  color: var(--text-dim);
}

.state-icon { font-size: 22px; }

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
</style>
