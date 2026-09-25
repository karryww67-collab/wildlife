<template>
  <nav class="nav-bar">
    <div class="nav-brand">
      <span class="brand-icon">🐾</span>
      <span class="brand-text">野生动物图像批量识别系统</span>
    </div>

    <div class="nav-links">
      <router-link
        v-for="item in menu"
        :key="item.path"
        :to="item.path"
        class="nav-link"
        active-class="active"
      >
        <span class="nav-icon">{{ item.icon }}</span>{{ item.label }}
      </router-link>
    </div>

    <div class="nav-status">
      <span class="ws-indicator" :title="alertStore.wsConnected ? '推送连接正常' : '推送未连接'">
        <span class="ws-dot" :class="{ connected: alertStore.wsConnected }"></span>
        {{ alertStore.wsConnected ? '实时连接' : '未连接' }}
      </span>

      <button
        class="theme-btn"
        type="button"
        :title="`当前主题：${themeMeta.label} · 点击切换到「${nextThemeMeta.label}」`"
        @click="toggleTheme"
      >
        {{ themeMeta.icon }}
      </button>

      <span v-if="username" class="nav-user">
        {{ username }}
        <span v-if="role" class="role-tag" :class="{ admin: role === 'ADMIN' }">{{ roleText }}</span>
      </span>

      <button class="logout-btn" type="button" @click="handleLogout">退出</button>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { logout, removeToken, verifyToken } from '@/api'
import { ADMIN_ONLY_PATHS } from '@/router'
import { useAlertStore } from '@/stores/alertStore'
import { useTheme } from '@/composables/useTheme'

const router = useRouter()
const alertStore = useAlertStore()
const { themeMeta, nextThemeMeta, toggleTheme } = useTheme()

/** 当前登录用户，由后端 /api/auth/verify 取回（本地只存 token，不存身份）。 */
const username = ref('')
const role = ref('')

const roleText = computed(() => (role.value === 'ADMIN' ? '管理员' : '普通用户'))

/** 菜单顺序即导航顺序，标签与 router 的 meta.title 保持一致。 */
const MENU = [
  { path: '/dashboard', label: '总览', icon: '📊' },
  { path: '/tasks', label: '识别任务', icon: '🗂' },
  { path: '/results', label: '识别结果', icon: '🔍' },
  { path: '/reviews', label: '人工复核', icon: '✅' },
  { path: '/models', label: '模型管理', icon: '🧠' },
  { path: '/statistics', label: '统计分析', icon: '📈' },
  { path: '/users', label: '用户管理', icon: '👤' }
]

/** 仅管理员可见的路径前缀，直接复用 router 导出，避免两处各写一份权限表。 */
const ADMIN_ONLY: readonly string[] = ADMIN_ONLY_PATHS

const menu = computed(() =>
  MENU.filter((item) => !ADMIN_ONLY.includes(item.path) || role.value === 'ADMIN')
)

async function loadSession() {
  try {
    const res = await verifyToken()
    username.value = res.data.username || ''
    role.value = (res.data.role || '').toUpperCase()
  } catch {
    // token 失效时由路由守卫接管跳转，这里保持安静
  }
}

async function handleLogout() {
  try {
    await logout()
  } catch {
    // 服务端会话可能已过期，本地清理照常执行
  }
  removeToken()
  router.push('/login')
}

onMounted(loadSession)
</script>

<style scoped>
.nav-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 24px;
  height: 56px;
  background: linear-gradient(90deg, var(--bg-nav-start) 0%, var(--bg-nav-end) 100%);
  border-bottom: 1px solid var(--border-primary);
  flex-shrink: 0;
}

.nav-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.brand-icon {
  font-size: 22px;
}

.brand-text {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.nav-links {
  display: flex;
  gap: 4px;
  align-items: center;
  flex: 1;
  min-width: 0;
  overflow-x: auto;
}

.nav-link {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-secondary);
  text-decoration: none;
  padding: 6px 14px;
  border-radius: 6px;
  font-size: 14px;
  white-space: nowrap;
  transition: background 0.2s, color 0.2s;
}

.nav-link:hover {
  background: var(--bg-card-hover);
  color: var(--text-primary);
}

.nav-link.active {
  background: var(--color-nav-active-bg);
  color: var(--color-nav-active-text);
}

.nav-icon {
  font-size: 15px;
}

.nav-status {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}

.ws-indicator {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-muted);
}

.ws-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-ws-dot-off);
  transition: background 0.3s;
}

.ws-dot.connected {
  background: var(--color-success);
  box-shadow: 0 0 6px var(--color-success);
}

.nav-user {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text-secondary);
}

.role-tag {
  padding: 1px 7px;
  border-radius: 10px;
  font-size: 11px;
  background: var(--bg-card-hover);
  border: 1px solid var(--border-primary);
  color: var(--text-muted);
}

.role-tag.admin {
  background: var(--bg-badge-purple);
  border-color: var(--border-purple);
  color: var(--text-purple);
}

.logout-btn {
  padding: 5px 12px;
  font-size: 13px;
  border-radius: 6px;
  cursor: pointer;
  background: transparent;
  border: 1px solid var(--border-primary);
  color: var(--text-secondary);
  transition: background 0.2s, color 0.2s, border-color 0.2s;
}

.logout-btn:hover {
  background: var(--bg-badge-danger);
  border-color: var(--border-danger);
  color: var(--text-danger);
}

.theme-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
  border-radius: 6px;
  background: transparent;
  border: 1px solid var(--border-primary);
  color: var(--text-secondary);
  transition: background 0.2s, border-color 0.2s;
}

.theme-btn:hover {
  background: var(--bg-card-hover);
  border-color: var(--border-accent);
}
</style>
