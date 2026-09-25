import { computed, ref, watch } from 'vue'

/**
 * 主题：只有两套，都是薄荷绿系的浅色主题，没有深色主题。
 *   forest  —— 薄荷绿森林（默认）
 *   cartoon —— 卡通动物
 */
export type Theme = 'forest' | 'cartoon'

export const THEME_META: Record<Theme, { label: string; icon: string }> = {
  forest: { label: '薄荷绿森林', icon: '🌲' },
  cartoon: { label: '卡通动物', icon: '🦊' }
}

const STORAGE_KEY = 'wildlife-theme'

function normalize(v: string | null): Theme | null {
  if (v === 'forest' || v === 'cartoon') return v
  // 旧值迁移：mint（初版薄荷绿）统一归到 forest，
  // 保证老用户不会因为值失效而落到"半套主题"。
  if (v === 'mint') return 'forest'
  return null
}

function loadTheme(): Theme {
  try {
    return normalize(localStorage.getItem(STORAGE_KEY)) ?? 'forest'
  } catch {
    return 'forest'
  }
}

const theme = ref<Theme>(loadTheme())

function applyTheme(t: Theme) {
  document.documentElement.setAttribute('data-theme', t)
}

const nextTheme = computed<Theme>(() => (theme.value === 'forest' ? 'cartoon' : 'forest'))

function toggleTheme() {
  theme.value = nextTheme.value
}

watch(theme, (t) => {
  applyTheme(t)
  try { localStorage.setItem(STORAGE_KEY, t) } catch {}
}, { immediate: true })

export function useTheme() {
  return {
    theme,
    nextTheme,
    /** 当前主题的展示信息（名称 + 图标），供切换按钮直接使用。 */
    themeMeta: computed(() => THEME_META[theme.value]),
    nextThemeMeta: computed(() => THEME_META[nextTheme.value]),
    toggleTheme
  }
}
