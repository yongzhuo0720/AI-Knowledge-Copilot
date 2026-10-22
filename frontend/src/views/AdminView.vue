<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  deleteAdminKnowledgeBase,
  deleteAdminUser,
  deleteAdminWorkspace,
  getAdminOverview,
  listAdminKnowledgeBases,
  listAdminUsers,
  listAdminWorkspaces,
  updateAdminUserAdmin,
  updateAdminUserStatus,
  type AdminKnowledgeBase,
  type AdminOverview,
  type AdminUser,
  type AdminWorkspace,
} from '@/services/api'

type AdminTab = 'users' | 'workspaces' | 'knowledge-bases'

const tabs: Array<{ key: AdminTab; label: string; caption: string }> = [
  { key: 'users', label: '用户账号', caption: '注册、封禁与权限' },
  { key: 'workspaces', label: '工作空间', caption: '归属与成员' },
  { key: 'knowledge-bases', label: '知识库', caption: '文档与会话' },
]

const activeTab = ref<AdminTab>('users')
const overview = ref<AdminOverview | null>(null)
const users = ref<AdminUser[]>([])
const workspaces = ref<AdminWorkspace[]>([])
const knowledgeBases = ref<AdminKnowledgeBase[]>([])
const search = ref('')
const loading = ref(false)
const busyId = ref<number | null>(null)
const message = ref('')

const currentUserId = computed(() => {
  const stored = localStorage.getItem('currentUser')
  if (!stored) return null
  try { return (JSON.parse(stored) as { id: number }).id } catch { return null }
})

const filteredUsers = computed(() => filterBy(users.value, (user) => [user.username, user.email]))
const filteredWorkspaces = computed(() => filterBy(workspaces.value, (workspace) => [workspace.name, workspace.ownerUsername ?? '']))
const filteredKnowledgeBases = computed(() => filterBy(knowledgeBases.value, (item) => [item.name, item.workspaceName ?? '']))

function filterBy<T>(items: T[], pick: (item: T) => string[]): T[] {
  const keyword = search.value.trim().toLowerCase()
  if (!keyword) return items
  return items.filter((item) => pick(item).some((value) => value.toLowerCase().includes(keyword)))
}

function showError(error: unknown, fallback: string) {
  message.value = error instanceof Error ? error.message : fallback
}

function formatDate(value: string | null | undefined) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

async function loadAll() {
  loading.value = true
  message.value = ''
  try {
    const [overviewResponse, usersResponse, workspacesResponse, knowledgeBasesResponse] = await Promise.all([
      getAdminOverview(),
      listAdminUsers(),
      listAdminWorkspaces(),
      listAdminKnowledgeBases(),
    ])
    overview.value = overviewResponse.data
    users.value = usersResponse.data
    workspaces.value = workspacesResponse.data
    knowledgeBases.value = knowledgeBasesResponse.data
  } catch (error) {
    showError(error, '加载后台数据失败')
  } finally {
    loading.value = false
  }
}

async function toggleStatus(user: AdminUser) {
  const nextStatus = user.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'
  const action = nextStatus === 'SUSPENDED' ? '封禁' : '解封'
  if (!window.confirm(`确定要${action}用户「${user.username}」吗？`)) return
  busyId.value = user.id
  message.value = ''
  try {
    const response = await updateAdminUserStatus(user.id, nextStatus)
    users.value = users.value.map((item) => (item.id === user.id ? response.data : item))
    message.value = `已${action}用户「${user.username}」。`
    overview.value = (await getAdminOverview()).data
  } catch (error) {
    showError(error, `${action}用户失败`)
  } finally {
    busyId.value = null
  }
}

async function toggleAdmin(user: AdminUser) {
  const nextAdmin = !user.isAdmin
  const action = nextAdmin ? '授予' : '撤销'
  if (!window.confirm(`确定要${action}用户「${user.username}」的管理员权限吗？`)) return
  busyId.value = user.id
  message.value = ''
  try {
    const response = await updateAdminUserAdmin(user.id, nextAdmin)
    users.value = users.value.map((item) => (item.id === user.id ? response.data : item))
    message.value = `已${action}「${user.username}」的管理员权限。`
    overview.value = (await getAdminOverview()).data
  } catch (error) {
    showError(error, `${action}管理员权限失败`)
  } finally {
    busyId.value = null
  }
}

async function removeUser(user: AdminUser) {
  const detail = `工作空间 ${user.workspaceCount} 个、知识库 ${user.knowledgeBaseCount} 个、会话 ${user.conversationCount} 条`
  if (!window.confirm(`确定要删除用户「${user.username}」吗？\n该账号名下的${detail}将一并永久删除，且无法恢复。`)) return
  busyId.value = user.id
  message.value = ''
  try {
    await deleteAdminUser(user.id)
    users.value = users.value.filter((item) => item.id !== user.id)
    message.value = `已删除用户「${user.username}」及其名下数据。`
    await loadAll()
  } catch (error) {
    showError(error, '删除用户失败')
  } finally {
    busyId.value = null
  }
}

async function removeWorkspace(workspace: AdminWorkspace) {
  if (!window.confirm(`确定要删除工作空间「${workspace.name}」吗？\n其下 ${workspace.knowledgeBaseCount} 个知识库及全部文档、会话将一并永久删除。`)) return
  busyId.value = workspace.id
  message.value = ''
  try {
    await deleteAdminWorkspace(workspace.id)
    message.value = `已删除工作空间「${workspace.name}」。`
    await loadAll()
  } catch (error) {
    showError(error, '删除工作空间失败')
  } finally {
    busyId.value = null
  }
}

async function removeKnowledgeBase(item: AdminKnowledgeBase) {
  if (!window.confirm(`确定要删除知识库「${item.name}」吗？\n其下 ${item.documentCount} 个文档与 ${item.conversationCount} 条会话将一并永久删除。`)) return
  busyId.value = item.id
  message.value = ''
  try {
    await deleteAdminKnowledgeBase(item.id)
    message.value = `已删除知识库「${item.name}」。`
    await loadAll()
  } catch (error) {
    showError(error, '删除知识库失败')
  } finally {
    busyId.value = null
  }
}

onMounted(() => { void loadAll() })
</script>

<template>
  <main class="admin-shell">
    <header class="admin-header">
      <div>
        <p class="eyebrow">管理后台</p>
        <h1>后台管理</h1>
        <p class="admin-summary">集中查看全系统的账号、工作空间与知识库，并执行封禁、授权与清理操作。</p>
      </div>
      <button class="secondary-button" type="button" :disabled="loading" @click="loadAll">{{ loading ? '刷新中…' : '刷新数据' }}</button>
    </header>

    <section v-if="overview" class="admin-metrics">
      <article class="panel admin-metric"><span>用户总数</span><strong>{{ overview.userCount }}</strong><small>{{ overview.activeUserCount }} 正常 · {{ overview.suspendedUserCount }} 已封禁</small></article>
      <article class="panel admin-metric"><span>管理员</span><strong>{{ overview.adminCount }}</strong><small>可进入本后台</small></article>
      <article class="panel admin-metric"><span>工作空间</span><strong>{{ overview.workspaceCount }}</strong><small>全系统合计</small></article>
      <article class="panel admin-metric"><span>知识库</span><strong>{{ overview.knowledgeBaseCount }}</strong><small>{{ overview.documentCount }} 个文档</small></article>
      <article class="panel admin-metric"><span>会话</span><strong>{{ overview.conversationCount }}</strong><small>含问答与 Agent</small></article>
    </section>

    <section class="admin-toolbar">
      <nav class="admin-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          :class="{ active: activeTab === tab.key }"
          @click="activeTab = tab.key; search = ''"
        >
          {{ tab.label }}
          <small>{{ tab.caption }}</small>
        </button>
      </nav>
      <input v-model="search" class="admin-search" type="search" :placeholder="`在${tabs.find((t) => t.key === activeTab)?.label}中搜索…`" />
    </section>

    <section v-if="activeTab === 'users'" class="panel admin-table-panel">
      <table class="admin-table">
        <thead>
          <tr>
            <th>账号</th><th>状态</th><th>权限</th><th>资源</th><th>注册时间</th><th class="admin-actions-head">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in filteredUsers" :key="user.id">
            <td>
              <div class="admin-identity">
                <span class="user-avatar">{{ user.username.slice(0, 1).toUpperCase() }}</span>
                <div><strong>{{ user.username }}</strong><small>{{ user.email }}</small></div>
              </div>
            </td>
            <td><span class="status-pill" :class="user.status === 'ACTIVE' ? 'active' : 'failed'">{{ user.status === 'ACTIVE' ? '正常' : '已封禁' }}</span></td>
            <td><span class="status-pill" :class="user.isAdmin ? 'active' : ''">{{ user.isAdmin ? '管理员' : '普通用户' }}</span></td>
            <td class="admin-resource-cell">{{ user.workspaceCount }} 空间 · {{ user.conversationCount }} 会话</td>
            <td class="admin-time-cell">{{ formatDate(user.createdAt) }}</td>
            <td>
              <div class="admin-actions">
                <button class="tiny-button" type="button" :disabled="busyId === user.id || user.id === currentUserId" @click="toggleStatus(user)">
                  {{ user.status === 'ACTIVE' ? '封禁' : '解封' }}
                </button>
                <button class="tiny-button" type="button" :disabled="busyId === user.id || user.id === currentUserId" @click="toggleAdmin(user)">
                  {{ user.isAdmin ? '撤管' : '授权' }}
                </button>
                <button class="tiny-button danger" type="button" :disabled="busyId === user.id || user.id === currentUserId" @click="removeUser(user)">删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="!filteredUsers.length"><td class="admin-empty" colspan="6">没有匹配的用户。</td></tr>
        </tbody>
      </table>
    </section>

    <section v-if="activeTab === 'workspaces'" class="panel admin-table-panel">
      <table class="admin-table">
        <thead><tr><th>工作空间</th><th>归属人</th><th>成员</th><th>知识库</th><th>创建时间</th><th class="admin-actions-head">操作</th></tr></thead>
        <tbody>
          <tr v-for="workspace in filteredWorkspaces" :key="workspace.id">
            <td><div class="admin-identity"><span class="admin-id-chip">#{{ workspace.id }}</span><div><strong>{{ workspace.name }}</strong><small>工作空间</small></div></div></td>
            <td>{{ workspace.ownerUsername ?? `用户 #${workspace.ownerUserId}` }}</td>
            <td class="admin-resource-cell">{{ workspace.memberCount }} 人</td>
            <td class="admin-resource-cell">{{ workspace.knowledgeBaseCount }} 个</td>
            <td class="admin-time-cell">{{ formatDate(workspace.createdAt) }}</td>
            <td><div class="admin-actions"><button class="tiny-button danger" type="button" :disabled="busyId === workspace.id" @click="removeWorkspace(workspace)">删除</button></div></td>
          </tr>
          <tr v-if="!filteredWorkspaces.length"><td class="admin-empty" colspan="6">没有匹配的工作空间。</td></tr>
        </tbody>
      </table>
    </section>

    <section v-if="activeTab === 'knowledge-bases'" class="panel admin-table-panel">
      <table class="admin-table">
        <thead><tr><th>知识库</th><th>所属空间</th><th>状态</th><th>文档 / 会话</th><th>创建时间</th><th class="admin-actions-head">操作</th></tr></thead>
        <tbody>
          <tr v-for="item in filteredKnowledgeBases" :key="item.id">
            <td><div class="admin-identity"><span class="admin-id-chip">#{{ item.id }}</span><div><strong>{{ item.name }}</strong><small>{{ item.description || '暂无描述' }}</small></div></div></td>
            <td>{{ item.workspaceName ?? `工作空间 #${item.workspaceId}` }}</td>
            <td><span class="status-pill" :class="item.status === 'ACTIVE' ? 'active' : ''">{{ item.status === 'ACTIVE' ? '正常' : item.status }}</span></td>
            <td class="admin-resource-cell">{{ item.documentCount }} 文档 · {{ item.conversationCount }} 会话</td>
            <td class="admin-time-cell">{{ formatDate(item.createdAt) }}</td>
            <td><div class="admin-actions"><button class="tiny-button danger" type="button" :disabled="busyId === item.id" @click="removeKnowledgeBase(item)">删除</button></div></td>
          </tr>
          <tr v-if="!filteredKnowledgeBases.length"><td class="admin-empty" colspan="6">没有匹配的知识库。</td></tr>
        </tbody>
      </table>
    </section>

    <p v-if="message" class="message admin-message" :class="{ 'message-error': message.includes('失败') || message.includes('错误') }">
      {{ message }}
      <button class="message-clear" type="button" title="关闭提示" @click="message = ''">×</button>
    </p>
  </main>
</template>

<style scoped>
.admin-shell { width: min(1280px, 100%); margin: 0 auto; padding: 24px 32px 56px; }
.admin-header { display: flex; align-items: end; justify-content: space-between; gap: 24px; margin-bottom: 20px; }
.admin-header h1 { margin-bottom: 6px; font-size: 20px; font-weight: 600; }
.admin-summary { max-width: 620px; margin: 0; color: var(--muted); font-size: 14px; line-height: 1.6; }
.admin-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; margin-bottom: 16px; }
.admin-metric { display: grid; gap: 4px; padding: 16px; }
.admin-metric span { color: var(--muted); font-size: 12px; font-weight: 600; }
.admin-metric strong { color: var(--ink); font-size: 22px; font-weight: 600; line-height: 1.2; }
.admin-metric small { color: var(--muted); font-size: 12px; }
.admin-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.admin-tabs { display: flex; gap: 16px; flex: 1; border-bottom: 1px solid var(--line-muted); }
.admin-tabs button { display: grid; gap: 1px; padding: 0 2px 8px; border: 0; background: transparent; color: var(--muted); font-size: 14px; text-align: left; }
.admin-tabs button:hover { color: var(--ink); }
.admin-tabs button.active { color: var(--ink); font-weight: 600; }
.admin-tabs button small { color: var(--muted); font-size: 11px; font-weight: 400; }
.admin-tabs button.active::after { position: relative; display: block; bottom: -9px; height: 2px; margin-top: -2px; background: var(--accent); content: ''; }
.admin-search { width: 240px; flex-shrink: 0; padding: 5px 12px; font-size: 12px; }
.admin-table-panel { padding: 4px 16px 12px; overflow-x: auto; }
.admin-table { width: 100%; border-collapse: collapse; }
.admin-table th { padding: 10px 10px; border-bottom: 1px solid var(--line); color: var(--muted); font-size: 12px; font-weight: 600; text-align: left; white-space: nowrap; }
.admin-table td { padding: 10px; border-bottom: 1px solid var(--line-muted); color: var(--ink); font-size: 13px; vertical-align: middle; }
.admin-table tbody tr:last-child td { border-bottom: 0; }
.admin-table tbody tr:hover td { background: var(--surface-muted); }
.admin-identity { display: flex; align-items: center; gap: 10px; min-width: 0; }
.admin-identity > div { display: grid; gap: 1px; min-width: 0; }
.admin-identity strong { font-size: 13px; font-weight: 500; }
.admin-identity small { color: var(--muted); font-size: 11px; }
.admin-id-chip { display: inline-grid; place-items: center; min-width: 40px; height: 26px; padding: 0 8px; border: 1px solid var(--line-muted); border-radius: var(--radius); background: var(--surface-muted); color: var(--muted); font-family: var(--mono); font-size: 11px; font-weight: 600; }
.admin-resource-cell, .admin-time-cell { color: var(--muted); font-size: 12px; white-space: nowrap; }
.admin-actions-head { text-align: right; }
.admin-actions { display: flex; align-items: center; justify-content: flex-end; gap: 6px; }
.tiny-button { padding: 3px 10px; border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); color: var(--accent); font-size: 12px; font-weight: 500; transition: background .12s, border-color .12s; }
.tiny-button:hover:not(:disabled) { background: var(--surface-muted); border-color: #aab1b9; }
.tiny-button.danger { color: var(--danger); }
.tiny-button.danger:hover:not(:disabled) { background: var(--danger-subtle); border-color: rgba(207, 34, 46, .35); }
.admin-empty { padding: 30px 10px !important; color: var(--muted); font-size: 12px; text-align: center; }
.admin-message { margin-top: 14px; }
@media (max-width: 900px) {
  .admin-shell { padding: 20px 20px 40px; }
  .admin-header { display: grid; align-items: start; }
  .admin-toolbar { display: grid; }
  .admin-search { width: 100%; }
}
</style>
