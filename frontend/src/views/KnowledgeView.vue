<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'

import {
  askConversation,
  createConversation,
  createKnowledgeBase,
  createWorkspace as createWorkspaceApi,
  listConversationMessages,
  listConversations,
  listDocuments,
  login,
  registerUser,
  reparseDocument,
  refreshDocumentProcessingStatus,
  retryDocumentProcessing,
  searchKnowledge,
  uploadDocument,
  type ConversationMessage,
  type ConversationSession,
  type KnowledgeAnswer,
  type KnowledgeBase,
  type KnowledgeDocument,
  type KnowledgeRetrievalChunk,
  type LoginResponse,
} from '@/services/api'

type AuthUser = LoginResponse['user']
type AuthMode = 'login' | 'register'

function readStoredUser(): AuthUser | null {
  const storedUser = localStorage.getItem('currentUser')
  if (!storedUser) return null
  try { return JSON.parse(storedUser) as AuthUser } catch { return null }
}

const currentUser = ref<AuthUser | null>(readStoredUser())
const userId = ref(currentUser.value ? String(currentUser.value.id) : '')
const loggedIn = ref(Boolean(localStorage.getItem('accessToken') && currentUser.value))
const authMode = ref<AuthMode>('login')
const email = ref('')
const password = ref('')
const username = ref('')
const workspaceId = ref('')
const workspaceName = ref('我的工作空间')
const name = ref('')
const description = ref('')
const knowledgeBase = ref<KnowledgeBase | null>(null)
const documents = ref<KnowledgeDocument[]>([])
const selectedFile = ref<File | null>(null)
const query = ref('')
const searchResults = ref<KnowledgeRetrievalChunk[]>([])
const answer = ref<KnowledgeAnswer | null>(null)
const sessions = ref<ConversationSession[]>([])
const currentSession = ref<ConversationSession | null>(null)
const messages = ref<ConversationMessage[]>([])
const message = ref('')
const loading = ref(false)

function showError(error: unknown, fallback: string) {
  message.value = error instanceof Error ? error.message : fallback
}

async function submitAuth() {
  loading.value = true
  message.value = ''
  try {
    if (authMode.value === 'register') {
      await registerUser(username.value, email.value, password.value)
      authMode.value = 'login'
      password.value = ''
      message.value = '注册成功，请使用新账号登录。'
      return
    }
    const response = await login(email.value, password.value)
    currentUser.value = response.data.user
    userId.value = String(response.data.user.id)
    localStorage.setItem('accessToken', response.data.accessToken)
    localStorage.setItem('currentUser', JSON.stringify(response.data.user))
    loggedIn.value = true
    message.value = `欢迎回来，${response.data.user.username}`
  } catch (error) { showError(error, authMode.value === 'login' ? '登录失败' : '注册失败')
  } finally { loading.value = false }
}

function switchAuthMode(mode: AuthMode) { authMode.value = mode; message.value = '' }

function signOut() {
  localStorage.removeItem('accessToken')
  localStorage.removeItem('currentUser')
  currentUser.value = null; userId.value = ''; loggedIn.value = false
  knowledgeBase.value = null; documents.value = []; sessions.value = []; messages.value = []; currentSession.value = null
}

async function createWorkspace() {
  if (!userId.value || !workspaceName.value.trim()) return
  loading.value = true; message.value = ''
  try {
    const response = await createWorkspaceApi(Number(userId.value), workspaceName.value.trim())
    workspaceId.value = String(response.data.id)
    message.value = `工作空间已创建：${response.data.name}（ID ${response.data.id}）`
  } catch (error) { showError(error, '创建工作空间失败')
  } finally { loading.value = false }
}

async function create() {
  if (!workspaceId.value || !name.value.trim()) return
  loading.value = true; message.value = ''
  try {
    const response = await createKnowledgeBase(Number(userId.value), Number(workspaceId.value), name.value, description.value)
    knowledgeBase.value = response.data; documents.value = []; searchResults.value = []; answer.value = null
    await loadConversations(); message.value = '知识库创建成功，现在可以上传文档。'
  } catch (error) { showError(error, '创建知识库失败')
  } finally { loading.value = false }
}

async function search() {
  if (!knowledgeBase.value || !query.value.trim()) return
  loading.value = true; message.value = ''
  try {
    const response = await searchKnowledge(Number(userId.value), knowledgeBase.value.id, query.value.trim())
    searchResults.value = response.data; answer.value = null
    if (!response.data.length) message.value = '没有找到相关内容，可以换个关键词试试。'
  } catch (error) { showError(error, '检索失败')
  } finally { loading.value = false }
}

async function ask() {
  if (!knowledgeBase.value || !currentSession.value || !query.value.trim()) return
  loading.value = true; message.value = ''
  try {
    const response = await askConversation(Number(userId.value), knowledgeBase.value.id, currentSession.value.id, query.value.trim())
    messages.value = [...messages.value, response.data.userMessage, response.data.assistantMessage]
    answer.value = { answer: response.data.assistantMessage.content, sources: response.data.assistantMessage.sources }
    await loadConversations()
  } catch (error) { showError(error, '问答失败')
  } finally { loading.value = false }
}

async function loadConversations() {
  if (!knowledgeBase.value) return
  const response = await listConversations(Number(userId.value), knowledgeBase.value.id)
  sessions.value = response.data
  if (!sessions.value.length) { const created = await createConversation(Number(userId.value), knowledgeBase.value.id); sessions.value = [created.data]; await selectSession(created.data) }
  else if (!currentSession.value || !sessions.value.some((session) => session.id === currentSession.value?.id)) await selectSession(sessions.value[0])
}

async function selectSession(session: ConversationSession) {
  if (!knowledgeBase.value) return
  currentSession.value = session
  const response = await listConversationMessages(Number(userId.value), knowledgeBase.value.id, session.id)
  messages.value = response.data; answer.value = null
}

async function newSession() {
  if (!knowledgeBase.value) return
  loading.value = true
  try { const response = await createConversation(Number(userId.value), knowledgeBase.value.id); sessions.value = [response.data, ...sessions.value]; await selectSession(response.data) }
  catch (error) { showError(error, '创建会话失败') } finally { loading.value = false }
}

function selectFile(event: Event) { selectedFile.value = (event.target as HTMLInputElement).files?.[0] ?? null }

async function upload() {
  if (!knowledgeBase.value || !selectedFile.value) return
  loading.value = true; message.value = ''
  try { const response = await uploadDocument(Number(userId.value), knowledgeBase.value.id, selectedFile.value); documents.value = [response.data, ...documents.value]; message.value = '文档已上传，解析任务正在后台运行。'; selectedFile.value = null }
  catch (error) { showError(error, '上传失败') } finally { loading.value = false }
}

async function loadDocuments() {
  if (!knowledgeBase.value) return
  loading.value = true
  try { const response = await listDocuments(Number(userId.value), knowledgeBase.value.id); documents.value = response.data }
  catch (error) { showError(error, '获取文档失败') } finally { loading.value = false }
}

async function refreshStatus(documentId: number) {
  if (!knowledgeBase.value) return
  loading.value = true
  try { const response = await refreshDocumentProcessingStatus(Number(userId.value), knowledgeBase.value.id, documentId); documents.value = documents.value.map((item) => item.id === documentId ? response.data : item) }
  catch (error) { showError(error, '查询解析状态失败') } finally { loading.value = false }
}

async function reparse(documentId: number) {
  if (!knowledgeBase.value) return
  loading.value = true
  try { const response = await reparseDocument(Number(userId.value), knowledgeBase.value.id, documentId); documents.value = documents.value.map((item) => item.id === documentId ? response.data : item); message.value = '已提交重新解析任务，将使用当前 Embedding 模型重建向量。' }
  catch (error) { showError(error, '重新解析失败') } finally { loading.value = false }
}

async function retry(documentId: number) {
  if (!knowledgeBase.value) return
  loading.value = true
  try { const response = await retryDocumentProcessing(Number(userId.value), knowledgeBase.value.id, documentId); documents.value = documents.value.map((item) => item.id === documentId ? response.data : item); message.value = '已提交失败任务重试。' }
  catch (error) { showError(error, '重试失败') } finally { loading.value = false }
}
</script>

<template>
  <main class="workspace-shell">
    <nav class="topbar"><RouterLink class="brand" to="/"><span class="brand-mark">✦</span><span>AI Knowledge Copilot</span></RouterLink><div v-if="loggedIn" class="user-menu"><span class="user-avatar">{{ currentUser?.username.slice(0, 1).toUpperCase() }}</span><span class="user-name">{{ currentUser?.username }}</span><button class="ghost-button" type="button" @click="signOut">退出登录</button></div></nav>
    <header class="workspace-header"><div><p class="eyebrow">KNOWLEDGE WORKSPACE</p><h1>把团队知识，整理成可协作的资产。</h1><p class="summary">上传资料，交给 AI 解析；用自然语言检索，并保留每次回答的来源。</p></div><div v-if="loggedIn" class="secure-badge"><span class="secure-dot" /> 已安全连接</div></header>

    <section v-if="!loggedIn" class="auth-layout">
      <div class="auth-intro"><p class="eyebrow">FROM FILES TO ANSWERS</p><h2>让知识真正<br />成为团队的<br /><em>共同记忆。</em></h2><div class="feature-list"><div><span>01</span><p><strong>集中管理</strong><br />让散落的资料变成可搜索的知识库。</p></div><div><span>02</span><p><strong>可靠问答</strong><br />每个答案都附带可追溯的引用来源。</p></div></div></div>
      <section class="panel auth-panel"><div class="auth-heading"><div><p class="eyebrow">WELCOME</p><h2>{{ authMode === 'login' ? '登录后继续' : '创建你的账号' }}</h2></div><span class="auth-step">{{ authMode === 'login' ? '1 / 1' : '1 / 2' }}</span></div><div class="auth-tabs"><button :class="{ active: authMode === 'login' }" type="button" @click="switchAuthMode('login')">登录</button><button :class="{ active: authMode === 'register' }" type="button" @click="switchAuthMode('register')">注册</button></div><form @submit.prevent="submitAuth"><label v-if="authMode === 'register'">用户名<input v-model="username" required maxlength="64" placeholder="例如：研发小组" /></label><label>邮箱<input v-model="email" type="email" required placeholder="name@company.com" autocomplete="email" /></label><label>密码<input v-model="password" type="password" required minlength="8" autocomplete="current-password" placeholder="至少 8 位字符" /></label><button class="primary-button wide-button" :disabled="loading" type="submit">{{ loading ? '处理中…' : authMode === 'login' ? '登录工作台' : '创建账号' }}</button></form><p class="form-hint">业务数据使用 Bearer Token 保护，登录后即可开始创建工作空间。</p><p v-if="message" class="message" :class="{ 'message-error': message.includes('失败') || message.includes('错误') }">{{ message }}</p></section>
    </section>

    <section v-else class="workspace-grid">
      <aside class="setup-column"><section class="panel setup-panel"><div class="section-heading"><div><p class="eyebrow">01 · SETUP</p><h2>准备工作空间</h2></div><span class="step-number">01</span></div><p class="section-copy">先创建一个属于你的工作空间，再在里面建立知识库。</p><div class="inline-create"><label>工作空间名称<input v-model="workspaceName" maxlength="128" placeholder="例如：产品研发部" /></label><button class="secondary-button" :disabled="loading || !workspaceName.trim()" type="button" @click="createWorkspace">一键创建</button></div><div class="divider"><span>或使用已有空间</span></div><label>工作空间 ID<input v-model="workspaceId" type="number" min="1" required placeholder="输入数字 ID" /></label></section>
      <form class="panel setup-panel" @submit.prevent="create"><div class="section-heading"><div><p class="eyebrow">02 · CREATE</p><h2>创建知识库</h2></div><span class="step-number">02</span></div><p class="section-copy">为资料集合设置一个清晰的名字和描述。</p><label>知识库名称<input v-model="name" maxlength="128" required placeholder="例如：研发规范" /></label><label>描述<textarea v-model="description" maxlength="500" placeholder="可选：这套资料主要解决什么问题？" /></label><button class="primary-button wide-button" :disabled="loading || !workspaceId || !name.trim()" type="submit">{{ loading ? '处理中…' : '创建知识库' }}</button></form><p v-if="message" class="message side-message" :class="{ 'message-error': message.includes('失败') || message.includes('错误') }">{{ message }}</p></aside>

      <section class="panel content-panel"><div class="section-heading content-heading"><div><p class="eyebrow">YOUR KNOWLEDGE</p><h2>{{ knowledgeBase ? knowledgeBase.name : '当前知识库' }}</h2></div><span v-if="knowledgeBase" class="status-pill active">{{ knowledgeBase.status }}</span></div>
        <div v-if="knowledgeBase" class="knowledge-content"><div class="knowledge-meta"><span>知识库 #{{ knowledgeBase.id }}</span><span>工作空间 #{{ knowledgeBase.workspaceId }}</span></div><div class="upload-zone"><div class="upload-icon">↑</div><div><strong>{{ selectedFile ? selectedFile.name : '上传一份资料' }}</strong><span>{{ selectedFile ? '已选择，准备开始解析' : '支持 TXT、MD、CSV、PDF、DOCX' }}</span></div><label class="file-button">选择文件<input type="file" accept=".txt,.md,.csv,.pdf,.docx" @change="selectFile" /></label><button class="primary-button" :disabled="loading || !selectedFile" type="button" @click="upload">{{ loading ? '上传中…' : '上传并解析' }}</button></div>
          <div class="content-section"><div class="subsection-heading"><div><h3>文档资产</h3><span>{{ documents.length }} 个文件</span></div><button class="ghost-button" :disabled="loading" type="button" @click="loadDocuments">刷新列表</button></div><div v-if="documents.length" class="document-list"><article v-for="item in documents" :key="item.id" class="document-row"><div class="document-info"><span class="file-type">{{ item.contentType.split('/').pop()?.slice(0, 4).toUpperCase() }}</span><div><strong>{{ item.originalFilename }}</strong><span>{{ Math.ceil(item.fileSize / 1024) }} KB · 任务 {{ item.processingTaskId || '未提交' }}</span><small v-if="item.processingFailureReason" class="failure-reason">{{ item.processingFailureReason }}</small><small v-if="item.processingRetryCount">已重试 {{ item.processingRetryCount }} 次</small></div></div><div class="document-actions"><span class="status-pill" :class="`status-${item.status.toLowerCase()}`">{{ item.status }}</span><div><button class="text-button" :disabled="loading" type="button" @click="refreshStatus(item.id)">查询</button><button class="text-button" :disabled="loading" type="button" @click="reparse(item.id)">重新解析</button><button v-if="item.status === 'FAILED'" class="text-button danger" :disabled="loading" type="button" @click="retry(item.id)">重试</button></div></div></article></div><div v-else class="empty-state"><span class="empty-icon">◌</span><strong>还没有文档</strong><span>上传第一份资料，开始构建你的知识库。</span></div></div>
          <div class="content-section conversation-section"><div class="subsection-heading"><div><h3>AI 问答</h3><span>回答会自动保存到当前会话</span></div><button class="ghost-button" :disabled="loading" type="button" @click="newSession">新建会话</button></div><div v-if="sessions.length" class="session-list"><button v-for="session in sessions" :key="session.id" class="session-item" :class="{ active: currentSession?.id === session.id }" type="button" @click="selectSession(session)">{{ session.title }}</button></div><form class="ask-form" @submit.prevent="search"><input v-model="query" maxlength="1000" placeholder="输入问题或关键词，例如：研发流程有哪些阶段？" /><button class="secondary-button" :disabled="loading || !query.trim()" type="submit">检索</button><button class="primary-button" :disabled="loading || !query.trim() || !currentSession" type="button" @click="ask">生成回答</button></form><div v-if="messages.length" class="message-list"><article v-for="item in messages" :key="item.id" class="chat-message" :class="item.role.toLowerCase()"><small>{{ item.role === 'USER' ? '我' : 'AI 助手' }}</small><p>{{ item.content }}</p><small v-for="source in item.sources" :key="`${item.id}-${source.documentObjectKey}`" class="source-label">引用：{{ source.documentObjectKey }}</small></article></div><article v-if="answer" class="answer-card"><div class="answer-title"><span class="answer-icon">✦</span><h3>回答</h3></div><p>{{ answer.answer }}</p><small v-for="source in answer.sources" :key="source.documentObjectKey" class="source-label">来源：{{ source.documentObjectKey }}</small></article><div v-if="searchResults.length" class="search-results"><div class="result-label">检索结果 · {{ searchResults.length }}</div><article v-for="result in searchResults" :key="`${result.documentObjectKey}-${result.content}`" class="search-result"><small>{{ result.documentObjectKey }} · 相关度 {{ result.score.toFixed(2) }}</small><p>{{ result.content }}</p></article></div></div>
        </div><div v-else class="empty-state large-empty"><span class="empty-icon">✦</span><strong>你的知识库会显示在这里</strong><span>完成左侧两步设置后，就可以上传文档并开始提问。</span></div>
      </section>
    </section>
  </main>
</template>
