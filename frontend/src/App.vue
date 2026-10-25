<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { AUTH_CHANGED_EVENT } from '@/services/crossTabSync'

import { logout } from '@/services/api'
import { clearAuthState } from '@/services/auth'
import { MOON_ICON, SUN_ICON, featherIcon } from '@/services/icons'
import { initTheme, onThemeChange, currentTheme, toggleTheme, type Theme } from '@/services/theme'
import { useWorkspaceContext } from '@/composables/useWorkspaceContext'

const route = useRoute()
const router = useRouter()
const { currentUser, workspaces, workspaceId: activeWorkspaceId, loading: workspaceLoading, loadContext, switchWorkspace } = useWorkspaceContext()

const accessToken = ref(localStorage.getItem('accessToken'))
const workspaceError = ref('')

const isAuthenticated = computed(() => Boolean(accessToken.value && currentUser.value))
const showShell = computed(() => isAuthenticated.value)
const isAdmin = computed(() => Boolean(currentUser.value?.isAdmin))
const activeWorkspace = computed(() => workspaces.value.find((workspace) => String(workspace.id) === activeWorkspaceId.value))
const routeTitle = computed(() => route.path === '/knowledge' ? '知识库与问答' : route.path === '/documents' ? '文档资产' : route.path === '/chat' ? 'AI 会话' : route.path === '/retrieval' ? '检索调试' : route.path === '/agents' ? '知识库 Agent' : route.path === '/settings' ? '工作空间设置' : route.path === '/admin' ? '管理后台' : '工作空间总览')

const primaryNavigation = [
  { label: '总览', caption: '工作空间总览', to: '/' },
  { label: '知识库', caption: '知识库与问答', to: '/knowledge' },
  { label: '文档', caption: '文档资产管理', to: '/documents' },
  { label: 'AI 会话', caption: '会话与来源', to: '/chat' },
  { label: 'Agent', caption: 'Agent 与工具调用', to: '/agents' },
]

/**
 * 图标统一用 feather 风格（stroke，24x24），不用 emoji。
 * 以字符串返回、template 里 v-html 渲染 —— 内容全部来自本文件的常量，无注入风险。
 * 工厂函数在 services/icons.ts，这里只保留导航专属的图标映射。
 */
const NAV_ICONS: Record<string, string> = {
  '总览': featherIcon('<path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M9 22V12h6v10"/>'),
  '知识库': featherIcon('<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>'),
  '文档': featherIcon('<path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><path d="M13 2v7h7"/>'),
  'AI 会话': featherIcon('<path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/>'),
  'Agent': featherIcon('<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>'),
}
const SETTINGS_ICON = featherIcon('<line x1="4" y1="21" x2="4" y2="14"/><line x1="4" y1="10" x2="4" y2="3"/><line x1="12" y1="21" x2="12" y2="12"/><line x1="12" y1="8" x2="12" y2="3"/><line x1="20" y1="21" x2="20" y2="16"/><line x1="20" y1="12" x2="20" y2="3"/><line x1="1" y1="14" x2="7" y2="14"/><line x1="9" y1="8" x2="15" y2="8"/><line x1="17" y1="16" x2="23" y2="16"/>')
const ADMIN_ICON = featherIcon('<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>')
const BRAND_ICON = featherIcon('<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/>')

const navIcon = (label: string) => NAV_ICONS[label] ?? NAV_ICONS['总览']

// 主题切换：currentTheme 的解析在 index.html 内联脚本里已做过一次（防闪烁），
// 这里只是把它变成响应式并在挂载后接管系统偏好监听。
const theme = ref<Theme>(currentTheme())
const themeIcon = computed(() => (theme.value === 'dark' ? SUN_ICON : MOON_ICON))
const themeTitle = computed(() => (theme.value === 'dark' ? '切换到浅色主题' : '切换到深色主题'))

/**
 * 登录态变化时只需要刷新 App 自己的 token 视图；currentUser / workspaces
 * 由 useWorkspaceContext 在同一个事件里同步。未登录则把用户带回登录页。
 */
function syncAuthState() {
  accessToken.value = localStorage.getItem('accessToken')
  if (!isAuthenticated.value && route.path !== '/knowledge') void router.replace('/knowledge')
}

async function signOut() {
  // 先通知后端作废 token，再清本地状态（顺序不能反：请求头依赖 localStorage 里的 token）。
  // clearAuthState 会广播 AUTH_CHANGED_EVENT：上下文模块重置共享状态，这里同步 token 并跳转。
  await logout()
  clearAuthState()
}

onMounted(() => {
  window.addEventListener(AUTH_CHANGED_EVENT, syncAuthState)
  initTheme()
  onThemeChange((next) => { theme.value = next })
  if (isAuthenticated.value) {
    void loadContext().catch((error) => {
      workspaceError.value = error instanceof Error ? error.message : '工作空间加载失败'
    })
  }
})

onBeforeUnmount(() => {
  window.removeEventListener(AUTH_CHANGED_EVENT, syncAuthState)
})
</script>

<template>
  <div v-if="showShell" class="app-shell">
    <aside class="app-sidebar">
      <RouterLink class="sidebar-brand" to="/">
        <span class="brand-mark" v-html="BRAND_ICON" />
        <span><strong>AI Knowledge Copilot</strong><small>企业知识库工作台</small></span>
      </RouterLink>

      <section class="workspace-picker">
        <span class="sidebar-label">当前工作空间</span>
        <select :value="activeWorkspaceId" :disabled="workspaceLoading || !workspaces.length" aria-label="选择工作空间" @change="switchWorkspace(($event.target as HTMLSelectElement).value)">
          <option value="" disabled>{{ workspaceLoading ? '加载中…' : workspaces.length ? '选择工作空间' : '暂无工作空间' }}</option>
          <option v-for="workspace in workspaces" :key="workspace.id" :value="String(workspace.id)">{{ workspace.name }}</option>
        </select>
        <small v-if="activeWorkspace">当前空间 · {{ activeWorkspace.name }}</small>
        <small v-else-if="workspaceError" class="sidebar-error">{{ workspaceError }}</small>
      </section>

      <nav class="app-navigation" aria-label="工作区导航">
        <span class="sidebar-label">导航</span>
        <RouterLink v-for="item in primaryNavigation" :key="item.to" class="nav-item" :to="item.to" :title="item.caption">
          <span class="nav-icon" v-html="navIcon(item.label)" />
          <span>{{ item.label }}</span>
        </RouterLink>

        <RouterLink class="nav-item" to="/settings" title="工作空间设置"><span class="nav-icon" v-html="SETTINGS_ICON" /><span>设置</span></RouterLink>
        <RouterLink v-if="isAdmin" class="nav-item" to="/admin" title="管理后台"><span class="nav-icon" v-html="ADMIN_ICON" /><span>管理后台</span></RouterLink>

      </nav>

      <div class="sidebar-footer">
        <span class="services-hint">文档解析 · 向量检索 · AI 问答</span>
        <small>Enterprise knowledge workspace</small>
      </div>
    </aside>

    <div class="app-main">
      <header class="app-topbar">
        <div>
          <span class="breadcrumb-root">工作空间</span>
          <span class="breadcrumb-separator">/</span>
          <strong>{{ routeTitle }}</strong>
        </div>
        <div class="app-user-menu">
          <button class="ghost-button theme-toggle" type="button" :title="themeTitle" :aria-label="themeTitle" @click="toggleTheme"><span class="theme-icon" v-html="themeIcon" /></button>
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
