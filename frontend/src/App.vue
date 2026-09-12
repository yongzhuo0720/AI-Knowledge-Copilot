<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'

import { listWorkspaces, type Workspace } from '@/services/api'

type StoredUser = { id: number; username: string; email: string; status: string }

function readStoredUser(): StoredUser | null {
  const stored = localStorage.getItem('currentUser')
  if (!stored) return null
  try { return JSON.parse(stored) as StoredUser } catch { return null }
}

const route = useRoute()
const router = useRouter()
const currentUser = ref<StoredUser | null>(readStoredUser())
const accessToken = ref(localStorage.getItem('accessToken'))
const workspaces = ref<Workspace[]>([])
const activeWorkspaceId = ref(localStorage.getItem('activeWorkspaceId') ?? '')
const workspaceLoading = ref(false)
const workspaceError = ref('')

const isAuthenticated = computed(() => Boolean(accessToken.value && currentUser.value))
const showShell = computed(() => isAuthenticated.value)
const activeWorkspace = computed(() => workspaces.value.find((workspace) => String(workspace.id) === activeWorkspaceId.value))
const routeTitle = computed(() => route.path === '/knowledge' ? 'Knowledge workspace' : route.path === '/documents' ? 'Document library' : route.path === '/chat' ? 'AI Chat' : 'Workspace overview')

const primaryNavigation = [
  { label: 'Overview', caption: '工作空间总览', to: '/' },
  { label: 'Knowledge Base', caption: '知识库与问答', to: '/knowledge' },
  { label: 'Documents', caption: '文档资产管理', to: '/documents' },
  { label: 'AI Chat', caption: '会话与来源', to: '/chat' },
]

const plannedNavigation = [
  { label: 'Retrieval', caption: '检索链路调试' },
  { label: 'Agents', caption: 'Agent 与工具' },
  { label: 'Settings', caption: 'Workspace 设置' },
]

async function loadWorkspaces() {
  if (!currentUser.value) return
  workspaceLoading.value = true
  workspaceError.value = ''
  try {
    const response = await listWorkspaces(currentUser.value.id)
    workspaces.value = response.data
    const storedWorkspaceExists = workspaces.value.some((workspace) => String(workspace.id) === activeWorkspaceId.value)
    if (!storedWorkspaceExists) activeWorkspaceId.value = workspaces.value.length ? String(workspaces.value[0].id) : ''
    if (activeWorkspaceId.value) localStorage.setItem('activeWorkspaceId', activeWorkspaceId.value)
  } catch (error) {
    workspaceError.value = error instanceof Error ? error.message : '工作空间加载失败'
  } finally {
    workspaceLoading.value = false
  }
}

function syncAuthState() {
  accessToken.value = localStorage.getItem('accessToken')
  currentUser.value = readStoredUser()
  if (isAuthenticated.value) void loadWorkspaces()
  else {
    workspaces.value = []
    if (route.path !== '/knowledge') void router.replace('/knowledge')
  }
}

function selectWorkspace() {
  if (!activeWorkspaceId.value) return
  localStorage.setItem('activeWorkspaceId', activeWorkspaceId.value)
  window.dispatchEvent(new CustomEvent('aicopilot-workspace-changed', { detail: { workspaceId: activeWorkspaceId.value } }))
}

function signOut() {
  localStorage.removeItem('accessToken')
  localStorage.removeItem('currentUser')
  localStorage.removeItem('activeWorkspaceId')
  syncAuthState()
  window.dispatchEvent(new Event('aicopilot-auth-changed'))
  void router.push('/knowledge')
}

onMounted(() => {
  window.addEventListener('aicopilot-auth-changed', syncAuthState)
  window.addEventListener('aicopilot-workspaces-changed', loadWorkspaces)
  if (isAuthenticated.value) void loadWorkspaces()
})

onBeforeUnmount(() => {
  window.removeEventListener('aicopilot-auth-changed', syncAuthState)
  window.removeEventListener('aicopilot-workspaces-changed', loadWorkspaces)
})
</script>

<template>
  <div v-if="showShell" class="app-shell">
    <aside class="app-sidebar">
      <RouterLink class="sidebar-brand" to="/">
        <span class="brand-mark">✦</span>
        <span><strong>AI Knowledge</strong><small>Copilot workspace</small></span>
      </RouterLink>

      <section class="workspace-picker">
        <span class="sidebar-label">CURRENT WORKSPACE</span>
        <select v-model="activeWorkspaceId" :disabled="workspaceLoading || !workspaces.length" aria-label="选择工作空间" @change="selectWorkspace">
          <option value="" disabled>{{ workspaceLoading ? '加载中…' : workspaces.length ? '选择工作空间' : '暂无工作空间' }}</option>
          <option v-for="workspace in workspaces" :key="workspace.id" :value="String(workspace.id)">{{ workspace.name }}</option>
        </select>
        <small v-if="activeWorkspace">当前空间 · {{ activeWorkspace.name }}</small>
        <small v-else-if="workspaceError" class="sidebar-error">{{ workspaceError }}</small>
      </section>

      <nav class="app-navigation" aria-label="Workspace navigation">
        <span class="sidebar-label">WORKSPACE</span>
        <RouterLink v-for="item in primaryNavigation" :key="item.to" class="nav-item" :to="item.to" :title="item.caption">
          <span class="nav-icon">{{ item.label === 'Overview' ? '⌂' : '▦' }}</span>
          <span>{{ item.label }}</span>
        </RouterLink>

        <span class="sidebar-label nav-group-label">BUILD NEXT</span>
        <span v-for="item in plannedNavigation" :key="item.label" class="nav-item nav-item-disabled" :title="`${item.caption} · 即将开放`">
          <span class="nav-icon">{{ item.label === 'Documents' ? '◫' : item.label === 'AI Chat' ? '◌' : item.label === 'Retrieval' ? '⌕' : item.label === 'Agents' ? '✧' : '⚙' }}</span>
          <span>{{ item.label }}</span>
          <small>规划中</small>
        </span>
      </nav>

      <div class="sidebar-footer">
        <span class="service-status"><span class="service-dot" /> Core services connected</span>
        <small>Enterprise knowledge workspace</small>
      </div>
    </aside>

    <div class="app-main">
      <header class="app-topbar">
        <div>
          <span class="breadcrumb-root">WORKSPACE</span>
          <span class="breadcrumb-separator">/</span>
          <strong>{{ routeTitle }}</strong>
        </div>
        <div class="app-user-menu">
          <div class="app-user-copy"><strong>{{ currentUser?.username }}</strong><small>{{ activeWorkspace?.name ?? '未选择工作空间' }}</small></div>
          <span class="user-avatar">{{ currentUser?.username.slice(0, 1).toUpperCase() }}</span>
          <button class="ghost-button" type="button" @click="signOut">退出登录</button>
        </div>
      </header>
      <main class="app-page"><RouterView /></main>
    </div>
  </div>

  <RouterView v-else />
</template>
