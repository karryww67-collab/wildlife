import { createRouter, createWebHashHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { getToken, verifyToken } from '@/api'

/**
 * 角色取值：后端 AuthService.normalizeRole() 只归一为两种，
 * 库里除 ADMIN 外的任何角色（含 REVIEWER）都会被降为 USER。
 */
type Role = 'ADMIN' | 'USER'

/** 越权访问时的回退页，也是普通用户的首页。 */
const FALLBACK_PATH = '/dashboard'

/** 仅管理员可访问的路径前缀，供导航菜单按权限渲染时复用。 */
export const ADMIN_ONLY_PATHS = ['/models', '/statistics', '/users'] as const

/**
 * 权限矩阵
 *   ADMIN 管理员   —— 全部页面
 *   USER  普通用户 —— /dashboard /tasks /tasks/:id /results /reviews
 *
 * 约定：带 meta.roles 的只允许列出的角色；不带则所有已登录用户可访问。
 * meta.public 为 true 的路由免登录。
 */
const routes: RouteRecordRaw[] = [
  // 根路径按权限落到首页
  { path: '/', redirect: FALLBACK_PATH },

  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },

  // ── 普通用户与管理员均可访问 ────────────────────────────────────────────
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { requiresAuth: true, title: '监控总览' }
  },
  {
    path: '/tasks',
    name: 'TaskList',
    component: () => import('@/views/TaskListView.vue'),
    meta: { requiresAuth: true, title: '识别任务' }
  },
  {
    path: '/tasks/:id',
    name: 'TaskDetail',
    component: () => import('@/views/TaskDetailView.vue'),
    meta: { requiresAuth: true, title: '任务详情' }
  },
  {
    path: '/results',
    name: 'Results',
    component: () => import('@/views/ResultView.vue'),
    meta: { requiresAuth: true, title: '识别结果' }
  },
  {
    path: '/reviews',
    name: 'Reviews',
    component: () => import('@/views/ReviewView.vue'),
    meta: { requiresAuth: true, title: '人工复核' }
  },

  // ── 仅管理员 ───────────────────────────────────────────────────────────
  {
    path: '/models',
    name: 'Models',
    component: () => import('@/views/ModelView.vue'),
    meta: { requiresAuth: true, roles: ['ADMIN'], title: '模型管理' }
  },
  {
    path: '/statistics',
    name: 'Statistics',
    component: () => import('@/views/StatisticsView.vue'),
    meta: { requiresAuth: true, roles: ['ADMIN'], title: '统计分析' }
  },
  {
    path: '/users',
    name: 'UserManagement',
    component: () => import('@/views/admin/UserManagementView.vue'),
    meta: { requiresAuth: true, roles: ['ADMIN'], title: '用户管理' }
  },

  // 未匹配的地址一律回到首页，避免白屏
  { path: '/:pathMatch(.*)*', redirect: FALLBACK_PATH }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

function normalizeRole(role?: string): Role {
  return role?.trim().toUpperCase() === 'ADMIN' ? 'ADMIN' : 'USER'
}

router.beforeEach(async (to) => {
  // 登录页等公开页面直接放行
  if (to.meta.public) return true

  const token = getToken()
  if (!token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 每次导航都向后端确认会话，顺带取回当前角色，避免权限随本地缓存过期而失真
  let role: Role
  try {
    const res = await verifyToken()
    role = normalizeRole(res.data.role)
  } catch {
    // token 失效或后端不可达，退回登录页
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  const allowed = to.meta.roles as Role[] | undefined
  if (allowed && !allowed.includes(role)) {
    // 普通用户访问模型管理 / 统计分析 / 用户管理时落到首页
    return { path: FALLBACK_PATH }
  }

  return true
})

export default router
