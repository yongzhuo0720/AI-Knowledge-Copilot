<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { WORKSPACE_CHANGED_EVENT } from '@/services/crossTabSync'

import {
  addWorkspaceMember,
  listWorkspaceMembers,
  listWorkspaces,
  type Workspace,
  type WorkspaceMember,
} from '@/services/api'

type StoredUser = { id: number; username: string }

function readStoredUser(): StoredUser | null {
  const stored = localStorage.getItem('currentUser')
  if (!stored) return null
  try { return JSON.parse(stored) as StoredUser } catch { return null }
}

const currentUser = ref<StoredUser | null>(readStoredUser())
const router = useRouter()
const workspaces = ref<Workspace[]>([])
const workspaceId = ref(localStorage.getItem('activeWorkspaceId') ?? '')
const members = ref<WorkspaceMember[]>([])
const memberUserId = ref('')
const memberRole = ref<WorkspaceMember['role']>('MEMBER')
const loading = ref(false)
const message = ref('')

const activeWorkspace = computed(() => workspaces.value.find((item) => String(item.id) === workspaceId.value))
const canManageMembers = computed(() => activeWorkspace.value?.ownerUserId === currentUser.value?.id || members.value.some((item) => item.userId === currentUser.value?.id && item.role === 'ADMIN'))

/** 角色显示为中文（数据本身仍是英文枚举） */
const ROLE_LABELS: Record<string, string> = { OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员' }
const roleLabel = (value: string) => ROLE_LABELS[value] ?? value

async function loadContext() {
  if (!currentUser.value) return
  loading.value = true
  message.value = ''
  try {
    workspaces.value = (await listWorkspaces(currentUser.value.id)).data
    if (!workspaceId.value || !workspaces.value.some((item) => String(item.id) === workspaceId.value)) workspaceId.value = workspaces.value.length ? String(workspaces.value[0].id) : ''
    await loadMembers()
  } catch (error) { message.value = error instanceof Error ? error.message : '加载设置失败' }
  finally { loading.value = false }
}

async function loadMembers() {
  if (!currentUser.value || !workspaceId.value) { members.value = []; return }
  members.value = (await listWorkspaceMembers(currentUser.value.id, Number(workspaceId.value))).data
}

async function changeWorkspace() {
  localStorage.setItem('activeWorkspaceId', workspaceId.value)
  // 局部切换必须通知全局侧栏（App.vue 与跨标签同步都监听这个事件）
  window.dispatchEvent(new CustomEvent(WORKSPACE_CHANGED_EVENT, { detail: { workspaceId: workspaceId.value } }))
  await loadMembers()
}

async function submitMember() {
  const targetUserId = Number(memberUserId.value)
  if (!currentUser.value || !workspaceId.value || !Number.isInteger(targetUserId) || targetUserId <= 0) return
  loading.value = true
  message.value = ''
  try {
    const response = await addWorkspaceMember(currentUser.value.id, Number(workspaceId.value), targetUserId, memberRole.value)
    members.value = [...members.value.filter((item) => item.userId !== targetUserId), response.data]
    memberUserId.value = ''
    message.value = `成员 #${targetUserId} 已加入工作空间。`
  } catch (error) { message.value = error instanceof Error ? error.message : '添加成员失败' }
  finally { loading.value = false }
}

/** 侧栏（或另一标签页）切换工作空间时同步本页。幂等：自己派发的同一事件到达时直接返回。 */
function syncWorkspaceFromGlobal(event: Event) {
  const next = (event as CustomEvent<{ workspaceId?: string }>).detail?.workspaceId ?? localStorage.getItem('activeWorkspaceId') ?? ''
  if (!next || next === workspaceId.value) return
  workspaceId.value = next
  void loadMembers()
}

onMounted(() => {
  window.addEventListener(WORKSPACE_CHANGED_EVENT, syncWorkspaceFromGlobal)
  void loadContext()
})

onBeforeUnmount(() => {
  window.removeEventListener(WORKSPACE_CHANGED_EVENT, syncWorkspaceFromGlobal)
})
</script>

<template>
  <main class="settings-shell">
    <header class="settings-header"><div><p class="eyebrow">工作空间设置</p><h1>管理协作边界</h1><p class="settings-summary">成员只能访问自己加入的工作空间；知识库、文档、会话和 Agent 请求都会继承这层权限。</p></div><span class="agent-badge"><span class="status-dot online" />权限隔离已启用</span></header>
    <section class="settings-grid">
      <section class="panel settings-card"><div class="settings-card-heading"><div><p class="eyebrow">当前空间</p><h2>当前空间</h2></div><span class="section-meta">{{ activeWorkspace ? `#${activeWorkspace.id}` : '—' }}</span></div><label>选择工作空间<select v-model="workspaceId" :disabled="loading || !workspaces.length" @change="changeWorkspace"><option v-for="workspace in workspaces" :key="workspace.id" :value="String(workspace.id)">{{ workspace.name }}</option></select></label><div v-if="activeWorkspace" class="settings-facts"><span>所有者</span><strong>用户 #{{ activeWorkspace.ownerUserId }}</strong><span>成员数</span><strong>{{ members.length }} 人</strong></div></section>
      <section class="panel settings-card"><div class="settings-card-heading"><div><p class="eyebrow">成员</p><h2>成员与角色</h2></div><span class="section-meta">{{ members.length }} 人</span></div><form v-if="canManageMembers" class="member-form" @submit.prevent="submitMember"><label>用户 ID<input v-model="memberUserId" inputmode="numeric" min="1" placeholder="例如：2" /></label><label>角色<select v-model="memberRole"><option value="MEMBER">普通成员</option><option value="ADMIN">管理员</option></select></label><button class="primary-button" :disabled="loading || !memberUserId" type="submit">添加成员 <span>→</span></button></form><p v-else class="settings-notice">你是普通成员，只能查看当前空间成员。</p><div v-if="members.length" class="member-list"><article v-for="member in members" :key="member.userId" class="member-row"><span class="user-avatar">{{ String(member.userId).slice(-2) }}</span><div><strong>用户 #{{ member.userId }}</strong><small>{{ member.joinedAt ? new Date(member.joinedAt).toLocaleDateString('zh-CN') : '刚刚加入' }}</small></div><span class="status-pill" :class="member.role === 'OWNER' ? 'active' : ''">{{ roleLabel(member.role) }}</span></article></div><div v-else class="settings-empty">当前空间还没有成员信息。</div></section>
      <section class="panel settings-card settings-tools-card"><div class="settings-card-heading"><div><p class="eyebrow">开发者工具</p><h2>检索调试</h2></div></div><p class="settings-notice">并排运行向量 / 关键词 / 混合检索，检查召回与分值，用于调参和排查「为什么没检索到」。</p><button class="secondary-button" type="button" @click="router.push('/retrieval')">打开检索调试 <span>→</span></button></section>
    </section>
    <p v-if="message" class="message settings-message" :class="{ 'message-error': message.includes('失败') || message.includes('错误') }">{{ message }}</p>
  </main>
</template>
