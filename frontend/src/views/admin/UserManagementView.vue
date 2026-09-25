<template>
  <div class="page">
    <!-- 页头 -->
    <header class="page-head">
      <div class="head-left">
        <h1 class="page-title">用户管理</h1>
        <p class="page-sub">
          系统不开放自助注册，账号由管理员在此预置；角色决定可用功能范围，停用后无法登录
        </p>
      </div>
      <div class="head-actions">
        <button class="btn-ghost" :disabled="loading" @click="load">刷新</button>
        <button class="btn-ghost" @click="openPasswordForm">修改我的密码</button>
        <button class="btn-primary" @click="openForm()">新增用户</button>
      </div>
    </header>

    <!-- 角色说明 -->
    <section class="card role-card">
      <ul class="role-list">
        <li v-for="opt in roleOptions" :key="opt.value" class="role-item">
          <span class="role-tag" :class="`r-${opt.value.toLowerCase()}`">{{ opt.label }}</span>
          <span class="role-desc">{{ opt.desc }}</span>
        </li>
      </ul>
    </section>

    <!-- 筛选 -->
    <section class="card filter-bar">
      <label class="field">
        <span class="field-label">角色</span>
        <select v-model="roleFilter" @change="load">
          <option value="">全部角色</option>
          <option v-for="opt in roleOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
      </label>
      <label class="field grow">
        <span class="field-label">关键词</span>
        <input v-model.trim="keyword" placeholder="按账号或昵称搜索" @keyup.enter="load" />
      </label>
      <button class="btn-ghost" @click="resetFilter">重置</button>
    </section>

    <!-- 用户表 -->
    <section class="card">
      <div class="card-head">
        <span>账号列表</span>
        <span class="head-meta">共 {{ users.length }} 个账号</span>
      </div>

      <div v-if="loading" class="state">
        <span class="spinner"></span>
        <span>账号加载中...</span>
      </div>
      <div v-else-if="!users.length" class="state">
        <span class="state-icon">👥</span>
        <span>没有符合条件的账号</span>
      </div>

      <table v-else class="table">
        <thead>
          <tr>
            <th class="col-id">ID</th>
            <th>账号</th>
            <th>昵称</th>
            <th class="col-role">角色</th>
            <th class="col-status">状态</th>
            <th class="col-time">创建时间</th>
            <th class="col-ops">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in users" :key="user.id">
            <td class="mono">#{{ user.id }}</td>
            <td>
              {{ user.username }}
              <span v-if="user.username === currentUsername" class="self-tag">我自己</span>
            </td>
            <td>{{ user.nickname || '—' }}</td>
            <td>
              <span class="role-tag" :class="`r-${(user.role || 'USER').toLowerCase()}`">
                {{ USER_ROLE_LABEL[user.role] || user.role }}
              </span>
            </td>
            <td>
              <span class="badge" :class="isActive(user) ? 'st-active' : 'st-disabled'">
                {{ isActive(user) ? '正常' : '已停用' }}
              </span>
            </td>
            <td class="time">{{ formatTime(user.createdAt) }}</td>
            <td class="ops">
              <button class="btn-mini" @click="openForm(user)">编辑</button>
              <button class="btn-mini" @click="openResetForm(user)">重置密码</button>
              <button class="btn-mini" :disabled="busyId === user.id" @click="toggleStatus(user)">
                {{ isActive(user) ? '停用' : '启用' }}
              </button>
              <button
                class="btn-mini danger"
                :disabled="busyId === user.id || user.username === currentUsername"
                :title="user.username === currentUsername ? '不能删除自己的账号' : '删除账号'"
                @click="doDelete(user)"
              >删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- 新增 / 编辑 -->
    <Teleport to="body">
      <div v-if="formVisible" class="modal" @click.self="closeForm">
        <div class="modal-box">
          <header class="modal-head">
            <span class="modal-title">{{ editing ? `编辑账号 ${editing.username}` : '新增用户' }}</span>
            <button class="btn-close" @click="closeForm">×</button>
          </header>

          <div class="modal-body">
            <div class="form-grid">
              <label class="field">
                <span class="field-label">登录账号 <b class="required">*</b></span>
                <input
                  v-model.trim="form.username"
                  :disabled="!!editing"
                  placeholder="例如：reviewer01"
                />
              </label>
              <label class="field">
                <span class="field-label">昵称</span>
                <input v-model.trim="form.nickname" placeholder="例如：张巡护" />
              </label>

              <label v-if="!editing" class="field">
                <span class="field-label">初始密码 <b class="required">*</b></span>
                <input v-model="form.password" type="password" placeholder="至少 6 位" autocomplete="new-password" />
              </label>
              <label class="field">
                <span class="field-label">角色 <b class="required">*</b></span>
                <select v-model="form.role">
                  <option v-for="opt in roleOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
                </select>
              </label>

              <label v-if="editing" class="field span-2">
                <span class="field-label">邮箱</span>
                <input v-model.trim="form.email" placeholder="选填，用于接收识别任务通知" />
              </label>
            </div>

            <p class="form-note">{{ roleDesc }}</p>
            <div v-if="formError" class="message error">{{ formError }}</div>
          </div>

          <footer class="modal-foot">
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

    <!-- 重置密码 -->
    <Teleport to="body">
      <div v-if="resetVisible" class="modal" @click.self="resetVisible = false">
        <div class="modal-box narrow">
          <header class="modal-head">
            <span class="modal-title">重置密码 · {{ resetTarget?.username }}</span>
            <button class="btn-close" @click="resetVisible = false">×</button>
          </header>
          <div class="modal-body">
            <label class="field">
              <span class="field-label">新密码 <b class="required">*</b></span>
              <input v-model="newPassword" type="password" placeholder="至少 6 位" autocomplete="new-password" />
            </label>
            <div v-if="resetError" class="message error">{{ resetError }}</div>
            <div v-if="resetDone" class="message success">{{ resetDone }}</div>
          </div>
          <footer class="modal-foot">
            <div class="foot-actions">
              <button class="btn-ghost" @click="resetVisible = false">关闭</button>
              <button class="btn-primary" :disabled="resetSaving || newPassword.length < 6" @click="submitReset">
                {{ resetSaving ? '提交中...' : '确认重置' }}
              </button>
            </div>
          </footer>
        </div>
      </div>
    </Teleport>

    <!-- 修改自己的密码 -->
    <Teleport to="body">
      <div v-if="passwordVisible" class="modal" @click.self="passwordVisible = false">
        <div class="modal-box narrow">
          <header class="modal-head">
            <span class="modal-title">修改我的密码</span>
            <button class="btn-close" @click="passwordVisible = false">×</button>
          </header>
          <div class="modal-body">
            <label class="field">
              <span class="field-label">原密码 <b class="required">*</b></span>
              <input v-model="ownForm.oldPassword" type="password" autocomplete="current-password" />
            </label>
            <label class="field">
              <span class="field-label">新密码 <b class="required">*</b></span>
              <input v-model="ownForm.newPassword" type="password" placeholder="至少 6 位" autocomplete="new-password" />
            </label>
            <div v-if="ownError" class="message error">{{ ownError }}</div>
            <div v-if="ownDone" class="message success">{{ ownDone }}</div>
          </div>
          <footer class="modal-foot">
            <div class="foot-actions">
              <button class="btn-ghost" @click="passwordVisible = false">关闭</button>
              <button class="btn-primary" :disabled="ownSaving || !canChangeOwn" @click="submitOwnPassword">
                {{ ownSaving ? '提交中...' : '确认修改' }}
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
import {
  changeOwnPassword,
  createUser,
  deleteUser,
  getCurrentUser,
  listUsers,
  resetUserPassword,
  updateUser,
  updateUserStatus,
  USER_ROLE_LABEL,
  type UserInfo,
  type UserRole
} from '@/api'

/** 角色选项：value 用于接口传参，label 用于展示，desc 说明权限范围。 */
const roleOptions: Array<{ value: UserRole; label: string; desc: string }> = [
  { value: 'ADMIN', label: '系统管理员', desc: '全部权限：图像上传、任务创建、模型管理与用户管理' },
  { value: 'REVIEWER', label: '复核员', desc: '可创建识别任务并对待复核结果进行确认 / 修正 / 剔除' },
  { value: 'USER', label: '只读用户', desc: '仅可查看识别任务、结果与统计，不能改动任何数据' }
]

const loading = ref(false)
const users = ref<UserInfo[]>([])
const currentUsername = ref('')
const roleFilter = ref<UserRole | ''>('')
const keyword = ref('')
const busyId = ref(0)

const formVisible = ref(false)
const saving = ref(false)
const formError = ref('')
const editing = ref<UserInfo | null>(null)
const form = reactive({
  username: '',
  password: '',
  nickname: '',
  email: '',
  role: 'REVIEWER' as UserRole
})

const resetVisible = ref(false)
const resetTarget = ref<UserInfo | null>(null)
const resetSaving = ref(false)
const resetError = ref('')
const resetDone = ref('')
const newPassword = ref('')

const passwordVisible = ref(false)
const ownSaving = ref(false)
const ownError = ref('')
const ownDone = ref('')
const ownForm = reactive({ oldPassword: '', newPassword: '' })

const canSave = computed(() => {
  if (saving.value) return false
  if (!form.username.trim()) return false
  if (!editing.value && form.password.length < 6) return false
  return true
})

const canChangeOwn = computed(
  () => !!ownForm.oldPassword && ownForm.newPassword.length >= 6
)

/** 弹窗底部展示当前所选角色的权限说明。 */
const roleDesc = computed(() => roleOptions.find((opt) => opt.value === form.role)?.desc ?? '')

/** 实体暂无 status 列，缺省视为正常。 */
function isActive(user: UserInfo): boolean {
  return (user.status ?? 'ACTIVE') !== 'DISABLED'
}

function formatTime(value?: string): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

async function loadCurrentUser() {
  try {
    const res = await getCurrentUser()
    currentUsername.value = res.data?.username ?? ''
  } catch {
    currentUsername.value = ''
  }
}

async function load() {
  loading.value = true
  try {
    const res = await listUsers({
      role: roleFilter.value || undefined,
      keyword: keyword.value || undefined
    })
    users.value = res.data ?? []
  } catch {
    users.value = []
  } finally {
    loading.value = false
  }
}

function resetFilter() {
  roleFilter.value = ''
  keyword.value = ''
  load()
}

function openForm(user?: UserInfo) {
  editing.value = user ?? null
  formError.value = ''
  saving.value = false
  Object.assign(form, {
    username: user?.username ?? '',
    password: '',
    nickname: user?.nickname ?? '',
    email: user?.email ?? '',
    role: (user?.role ?? 'REVIEWER') as UserRole
  })
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
  try {
    if (editing.value) {
      await updateUser(editing.value.id, {
        nickname: form.nickname.trim() || undefined,
        email: form.email.trim() || undefined,
        role: form.role
      })
    } else {
      await createUser({
        username: form.username.trim(),
        password: form.password,
        nickname: form.nickname.trim() || undefined,
        role: form.role
      })
    }
    formVisible.value = false
    await load()
  } catch (err: any) {
    formError.value = `保存失败：${err?.response?.data?.error || err?.message || '未知错误'}`
  } finally {
    saving.value = false
  }
}

async function toggleStatus(user: UserInfo) {
  const next = isActive(user) ? 'DISABLED' : 'ACTIVE'
  if (next === 'DISABLED' && !window.confirm(`停用账号 ${user.username}？停用后该账号无法登录。`)) return
  busyId.value = user.id
  try {
    await updateUserStatus(user.id, next)
    await load()
  } catch (err: any) {
    window.alert(`操作失败：${err?.response?.data?.error || err?.message || '未知错误'}`)
  } finally {
    busyId.value = 0
  }
}

function openResetForm(user: UserInfo) {
  resetTarget.value = user
  newPassword.value = ''
  resetError.value = ''
  resetDone.value = ''
  resetSaving.value = false
  resetVisible.value = true
}

async function submitReset() {
  if (!resetTarget.value || newPassword.value.length < 6) return
  resetSaving.value = true
  resetError.value = ''
  resetDone.value = ''
  try {
    await resetUserPassword(resetTarget.value.id, newPassword.value)
    resetDone.value = '密码已重置，请通知用户使用新密码登录'
    newPassword.value = ''
  } catch (err: any) {
    resetError.value = `重置失败：${err?.response?.data?.error || err?.message || '未知错误'}`
  } finally {
    resetSaving.value = false
  }
}

function openPasswordForm() {
  ownForm.oldPassword = ''
  ownForm.newPassword = ''
  ownError.value = ''
  ownDone.value = ''
  ownSaving.value = false
  passwordVisible.value = true
}

async function submitOwnPassword() {
  if (!canChangeOwn.value) return
  ownSaving.value = true
  ownError.value = ''
  ownDone.value = ''
  try {
    await changeOwnPassword({
      oldPassword: ownForm.oldPassword,
      newPassword: ownForm.newPassword
    })
    ownDone.value = '密码修改成功'
    ownForm.oldPassword = ''
    ownForm.newPassword = ''
  } catch (err: any) {
    ownError.value = `修改失败：${err?.response?.data?.error || err?.message || '未知错误'}`
  } finally {
    ownSaving.value = false
  }
}

async function doDelete(user: UserInfo) {
  if (!window.confirm(`删除账号 ${user.username}？该操作不可恢复。`)) return
  busyId.value = user.id
  try {
    await deleteUser(user.id)
    await load()
  } catch (err: any) {
    window.alert(`删除失败：${err?.response?.data?.error || err?.message || '未知错误'}`)
  } finally {
    busyId.value = 0
  }
}

onMounted(() => {
  loadCurrentUser()
  load()
})
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
.head-actions { display: flex; gap: 8px; flex-shrink: 0; }

/* ── 角色说明 ── */
.card {
  background: var(--bg-admin-card);
  border: 1px solid var(--border-primary);
  border-radius: 8px;
  overflow: hidden;
}
.role-card { margin-bottom: 14px; }
.role-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px 18px;
  padding: 12px 16px;
  list-style: none;
}
.role-item { display: flex; align-items: center; gap: 9px; font-size: 12px; }
.role-desc { color: var(--text-muted); }

.role-tag {
  display: inline-block;
  padding: 2px 9px;
  border-radius: 9px;
  font-size: 11px;
  white-space: nowrap;
  flex-shrink: 0;
}
.r-admin { background: var(--bg-badge-danger); color: var(--text-danger); }
.r-reviewer { background: var(--bg-badge-success); color: var(--color-primary); }
.r-user { background: var(--bg-badge-purple); color: var(--text-purple); }

/* ── 筛选 ── */
.filter-bar {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 14px 16px;
  margin-bottom: 14px;
}
.field { display: flex; flex-direction: column; gap: 5px; min-width: 160px; }
.field.grow { flex: 1; }
.field.span-2 { grid-column: span 2; }
.field-label {
  display: flex;
  align-items: baseline;
  gap: 4px;
  font-size: 11.5px;
  color: var(--text-muted);
}
.required { color: var(--color-danger); font-weight: 400; }

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
  font-family: inherit;
}
input:focus,
select:focus { border-color: var(--color-admin-focus); }
input:disabled { opacity: 0.6; cursor: not-allowed; }

/* ── 表格 ── */
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  font-size: 13px;
  font-weight: 600;
  border-bottom: 1px solid var(--border-primary);
}
.head-meta { font-size: 11.5px; font-weight: 400; color: var(--text-muted); }
.table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
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
}
.table tbody tr:hover { background: var(--bg-card-hover); }
.col-id { width: 70px; }
.col-role { width: 120px; }
.col-status { width: 92px; }
.col-time { width: 160px; }
.col-ops { width: 290px; }
.mono { font-family: Consolas, Monaco, monospace; color: var(--text-muted); }
.time { color: var(--text-muted); font-size: 12px; }
.ops { display: flex; gap: 6px; flex-wrap: wrap; }
.self-tag {
  margin-left: 6px;
  padding: 1px 7px;
  border-radius: 8px;
  font-size: 10.5px;
  background: var(--bg-badge-success);
  color: var(--color-primary);
}

.badge {
  display: inline-block;
  padding: 2px 9px;
  border-radius: 9px;
  font-size: 11px;
}
.st-active { background: var(--bg-badge-success); color: var(--color-success); }
.st-disabled { background: var(--bg-badge-danger); color: var(--text-danger); }

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
.btn-mini:hover:not(:disabled) { border-color: var(--color-primary); color: var(--color-primary); }
.btn-mini.danger:hover:not(:disabled) { border-color: var(--color-danger); color: var(--text-danger); }
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
}
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
  max-width: 620px;
  max-height: 88vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-admin-card);
  border: 1px solid var(--border-primary);
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.35);
}
.modal-box.narrow { max-width: 440px; }
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
  justify-content: flex-end;
  padding: 12px 18px;
  border-top: 1px solid var(--border-primary);
  background: var(--bg-admin-input-alt);
}
.foot-actions { display: flex; gap: 8px; }
.modal-body .field + .field { margin-top: 12px; }

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px 14px;
}
.form-grid .field + .field { margin-top: 0; }
.form-note {
  margin-top: 12px;
  padding: 8px 10px;
  border-radius: 4px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--text-muted);
  background: var(--bg-admin-input);
}

.message {
  margin-top: 12px;
  padding: 8px 10px;
  border-radius: 4px;
  font-size: 12px;
}
.message.error { background: var(--bg-badge-danger); color: var(--text-danger); }
.message.success { background: var(--bg-badge-success); color: var(--color-success); }

@media (max-width: 1100px) {
  .role-list { grid-template-columns: 1fr; }
}
</style>
