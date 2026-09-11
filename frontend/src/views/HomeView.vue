<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { getWorkspaceOverview, type WorkspaceOverview } from '@/services/api'

type StoredUser = { id: number; username: string }

function readStoredUser(): StoredUser | null {
  const stored = localStorage.getItem('currentUser')
  if (!stored) return null
  try { return JSON.parse(stored) as StoredUser } catch { return null }
}

const currentUser = ref<StoredUser | null>(readStoredUser())
const workspaceId = ref(localStorage.getItem('activeWorkspaceId') ?? '')
const overview = ref<WorkspaceOverview | null>(null)
const loading = ref(false)
const error = ref('')
const hasWorkspace = computed(() => Boolean(workspaceId.value))

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric' }).format(new Date(value))
}

function formatSize(value: number) {
  if (value < 1024 * 1024) return `${Math.max(1, Math.ceil(value / 1024))} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

async function loadOverview() {
  currentUser.value = readStoredUser()
  workspaceId.value = localStorage.getItem('activeWorkspaceId') ?? ''
  if (!currentUser.value || !workspaceId.value) { overview.value = null; return }
  loading.value = true
  error.value = ''
  try {
    const response = await getWorkspaceOverview(currentUser.value.id, Number(workspaceId.value))
    overview.value = response.data
  } catch (requestError) {
    error.value = requestError instanceof Error ? requestError.message : '概览加载失败'
  } finally { loading.value = false }
}

onMounted(() => {
  window.addEventListener('aicopilot-auth-changed', loadOverview)
  window.addEventListener('aicopilot-workspace-changed', loadOverview)
  void loadOverview()
})

onBeforeUnmount(() => {
  window.removeEventListener('aicopilot-auth-changed', loadOverview)
  window.removeEventListener('aicopilot-workspace-changed', loadOverview)
})
</script>

<template>
  <main class="dashboard-shell">
    <header class="dashboard-header"><div><p class="eyebrow">{{ currentUser ? 'WORKSPACE OVERVIEW' : 'AI KNOWLEDGE COPILOT' }}</p><h1>{{ currentUser ? `欢迎回来，${currentUser.username}。` : '企业知识，从理解开始。' }}</h1><p class="dashboard-summary">{{ currentUser ? '从一处查看知识资产、处理任务和最近的 AI 工作。' : '统一管理知识资产，用可靠的 AI 助手连接团队经验与业务决策。' }}</p></div><RouterLink class="primary-button dashboard-action" to="/knowledge">{{ currentUser ? '进入知识库' : '登录并开始' }} <span>→</span></RouterLink></header>
    <section v-if="loading" class="dashboard-state"><span class="loading-orb" />正在读取 Workspace 数据…</section>
    <section v-else-if="error" class="dashboard-state dashboard-state-error"><strong>暂时无法加载概览</strong><span>{{ error }}</span><button class="secondary-button" type="button" @click="loadOverview">重新加载</button></section>
    <section v-else-if="!hasWorkspace" class="dashboard-state"><strong>先创建一个 Workspace</strong><span>Workspace 是知识库、文档和会话的协作边界。</span><RouterLink class="secondary-button" to="/knowledge">开始设置</RouterLink></section>
    <template v-else-if="overview">
      <section class="metric-grid"><article class="metric-card"><span class="metric-label">KNOWLEDGE BASES</span><strong>{{ overview.knowledgeBaseCount }}</strong><small>当前 Workspace</small></article><article class="metric-card"><span class="metric-label">DOCUMENTS</span><strong>{{ overview.documentCount }}</strong><small>已纳入管理的文件</small></article><article class="metric-card"><span class="metric-label">INDEXED CHUNKS</span><strong>{{ overview.indexedChunkCount ?? '—' }}</strong><small>{{ overview.indexedChunkCount === null ? 'AI Service 暂不可用' : 'Milvus 向量索引' }}</small></article><article class="metric-card"><span class="metric-label">CONVERSATIONS</span><strong>{{ overview.conversationCount }}</strong><small>我的会话历史</small></article></section>
      <section class="dashboard-columns"><div class="dashboard-main-column"><section class="dashboard-section panel"><div class="dashboard-section-heading"><div><p class="eyebrow">KNOWLEDGE ASSETS</p><h2>最近知识库</h2></div><RouterLink class="section-link" to="/knowledge">查看全部 →</RouterLink></div><div v-if="overview.recentKnowledgeBases.length" class="overview-list"><RouterLink v-for="item in overview.recentKnowledgeBases" :key="item.id" class="overview-row" to="/knowledge"><span class="overview-icon">▦</span><span class="overview-row-copy"><strong>{{ item.name }}</strong><small>{{ item.documentCount }} 个文档 · 更新于 {{ formatDate(item.updatedAt) }}</small></span><span class="status-pill active">{{ item.status }}</span></RouterLink></div><div v-else class="mini-empty">还没有知识库，先创建一个知识库开始积累资料。</div></section><section class="dashboard-section panel"><div class="dashboard-section-heading"><div><p class="eyebrow">RECENT FILES</p><h2>最近文档</h2></div><span class="section-meta">{{ overview.documentCount }} 个文件</span></div><div v-if="overview.recentDocuments.length" class="overview-list"><div v-for="item in overview.recentDocuments" :key="item.id" class="overview-row"><span class="file-type">{{ item.contentType.split('/').pop()?.slice(0, 4).toUpperCase() }}</span><span class="overview-row-copy"><strong>{{ item.originalFilename }}</strong><small>{{ item.knowledgeBaseName }} · {{ formatSize(item.fileSize) }}</small></span><span class="status-pill" :class="`status-${item.status.toLowerCase()}`">{{ item.status }}</span></div></div><div v-else class="mini-empty">上传第一份文档后，最近活动会显示在这里。</div></section></div><aside class="dashboard-side-column"><section class="dashboard-section panel processing-card"><div class="dashboard-section-heading"><div><p class="eyebrow">PROCESSING</p><h2>处理任务</h2></div><span class="task-count">{{ overview.processingTaskCount }}</span></div><p>{{ overview.processingTaskCount ? '有文档正在解析或等待重试。' : '当前没有待处理任务。' }}</p><RouterLink class="section-link" to="/knowledge">管理文档 →</RouterLink></section><section class="dashboard-section panel"><div class="dashboard-section-heading"><div><p class="eyebrow">AI ACTIVITY</p><h2>最近会话</h2></div></div><div v-if="overview.recentConversations.length" class="overview-list"><RouterLink v-for="item in overview.recentConversations" :key="item.id" class="overview-row" to="/knowledge"><span class="overview-icon conversation-icon">◌</span><span class="overview-row-copy"><strong>{{ item.title }}</strong><small>{{ item.knowledgeBaseName }} · {{ formatDate(item.updatedAt) }}</small></span></RouterLink></div><div v-else class="mini-empty">创建会话后，最近的 AI 工作会显示在这里。</div></section></aside></section>
    </template>
  </main>
</template>
