<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { AUTH_CHANGED_EVENT, WORKSPACE_CHANGED_EVENT } from '@/services/crossTabSync'

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

/** 任务状态显示为中文（数据本身仍是英文枚举） */
const STATUS_LABELS: Record<string, string> = {
  COMPLETED: '已完成', PROCESSING: '处理中', ACCEPTED: '已受理', RETRYING: '重试中', FAILED: '失败', ACTIVE: '正常',
}
const statusLabel = (value: string) => STATUS_LABELS[value] ?? value

const BOOK_ICON = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>'
const CHAT_ICON = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>'

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
  window.addEventListener(AUTH_CHANGED_EVENT, loadOverview)
  window.addEventListener(WORKSPACE_CHANGED_EVENT, loadOverview)
  void loadOverview()
})

onBeforeUnmount(() => {
  window.removeEventListener(AUTH_CHANGED_EVENT, loadOverview)
  window.removeEventListener(WORKSPACE_CHANGED_EVENT, loadOverview)
})
</script>

<template>
  <main class="dashboard-shell">
    <header class="dashboard-header"><div><p class="eyebrow">{{ currentUser ? '工作空间总览' : 'AI Knowledge Copilot' }}</p><h1>{{ currentUser ? `欢迎回来，${currentUser.username}` : '企业知识，从理解开始' }}</h1><p class="dashboard-summary">{{ currentUser ? '从一处查看知识资产、处理任务和最近的 AI 工作。' : '统一管理知识资产，用可靠的 AI 助手连接团队经验与业务决策。' }}</p></div><RouterLink class="primary-button dashboard-action" to="/knowledge">{{ currentUser ? '进入知识库' : '登录并开始' }} <span>→</span></RouterLink></header>
    <section v-if="loading" class="dashboard-state"><span class="loading-orb" />正在读取工作空间数据…</section>
    <section v-else-if="error" class="dashboard-state dashboard-state-error"><strong>暂时无法加载概览</strong><span>{{ error }}</span><button class="secondary-button" type="button" @click="loadOverview">重新加载</button></section>
    <section v-else-if="!hasWorkspace" class="dashboard-state"><strong>先创建一个工作空间</strong><span>工作空间是知识库、文档和会话的协作边界。</span><RouterLink class="secondary-button" to="/knowledge">开始设置</RouterLink></section>
    <template v-else-if="overview">
      <section class="metric-grid"><article class="metric-card"><span class="metric-label">知识库</span><strong>{{ overview.knowledgeBaseCount }}</strong><small>当前工作空间</small></article><article class="metric-card"><span class="metric-label">文档</span><strong>{{ overview.documentCount }}</strong><small>已纳入管理的文件</small></article><article class="metric-card"><span class="metric-label">索引片段</span><strong>{{ overview.indexedChunkCount ?? '—' }}</strong><small>{{ overview.indexedChunkCount === null ? 'AI Service 暂不可用' : 'Milvus 向量索引' }}</small></article><article class="metric-card"><span class="metric-label">会话</span><strong>{{ overview.conversationCount }}</strong><small>我的会话历史</small></article></section>
      <section class="dashboard-columns"><div class="dashboard-main-column"><section class="dashboard-section panel"><div class="dashboard-section-heading"><div><p class="eyebrow">知识资产</p><h2>最近知识库</h2></div><RouterLink class="section-link" to="/knowledge">查看全部 →</RouterLink></div><div v-if="overview.recentKnowledgeBases.length" class="overview-list"><RouterLink v-for="item in overview.recentKnowledgeBases" :key="item.id" class="overview-row" to="/knowledge"><span class="overview-icon" v-html="BOOK_ICON" /><span class="overview-row-copy"><strong>{{ item.name }}</strong><small>{{ item.documentCount }} 个文档 · 更新于 {{ formatDate(item.updatedAt) }}</small></span><span class="status-pill active">{{ statusLabel(item.status) }}</span></RouterLink></div><div v-else class="mini-empty">还没有知识库，先创建一个知识库开始积累资料。</div></section><section class="dashboard-section panel"><div class="dashboard-section-heading"><div><p class="eyebrow">最近文件</p><h2>最近文档</h2></div><span class="section-meta">{{ overview.documentCount }} 个文件</span></div><div v-if="overview.recentDocuments.length" class="overview-list"><div v-for="item in overview.recentDocuments" :key="item.id" class="overview-row"><span class="file-type">{{ item.contentType.split('/').pop()?.slice(0, 4).toUpperCase() }}</span><span class="overview-row-copy"><strong>{{ item.originalFilename }}</strong><small>{{ item.knowledgeBaseName }} · {{ formatSize(item.fileSize) }}</small></span><span class="status-pill" :class="`status-${item.status.toLowerCase()}`">{{ statusLabel(item.status) }}</span></div></div><div v-else class="mini-empty">上传第一份文档后，最近活动会显示在这里。</div></section></div><aside class="dashboard-side-column"><section class="dashboard-section panel processing-card"><div class="dashboard-section-heading"><div><p class="eyebrow">处理中</p><h2>处理任务</h2></div><span class="task-count">{{ overview.processingTaskCount }}</span></div><p>{{ overview.processingTaskCount ? '有文档正在解析或等待重试。' : '当前没有待处理任务。' }}</p><RouterLink class="section-link" to="/knowledge">管理文档 →</RouterLink></section><section class="dashboard-section panel"><div class="dashboard-section-heading"><div><p class="eyebrow">AI 动态</p><h2>最近会话</h2></div></div><div v-if="overview.recentConversations.length" class="overview-list"><RouterLink v-for="item in overview.recentConversations" :key="item.id" class="overview-row" to="/knowledge"><span class="overview-icon conversation-icon" v-html="CHAT_ICON" /><span class="overview-row-copy"><strong>{{ item.title }}</strong><small>{{ item.knowledgeBaseName }} · {{ formatDate(item.updatedAt) }}</small></span></RouterLink></div><div v-else class="mini-empty">创建会话后，最近的 AI 工作会显示在这里。</div></section></aside></section>
    </template>
  </main>
</template>
