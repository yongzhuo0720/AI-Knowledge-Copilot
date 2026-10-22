<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import {
  askConversation,
  createConversation,
  createKnowledgeBase,
  createWorkspace as createWorkspaceApi,
  listConversationMessages,
  listConversations,
  listDocuments,
  listKnowledgeBases,
  listWorkspaces,
  login,
  logout,
  MAX_PAGE_SIZE,
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
  type Workspace,
} from '@/services/api'
import {
  isSupportedDocument,
  SUPPORTED_DOCUMENT_ACCEPT,
  SUPPORTED_DOCUMENT_LABEL,
} from '@/services/documentFormats'

type AuthUser = LoginResponse['user']
type AuthMode = 'user' | 'admin' | 'register'

const router = useRouter()

function readStoredUser(): AuthUser | null {
  const storedUser = localStorage.getItem('currentUser')
  if (!storedUser) return null
  try { return JSON.parse(storedUser) as AuthUser } catch { return null }
}

const currentUser = ref<AuthUser | null>(readStoredUser())
const userId = ref(currentUser.value ? String(currentUser.value.id) : '')
const loggedIn = ref(Boolean(localStorage.getItem('accessToken') && currentUser.value))
const authMode = ref<AuthMode>('user')
const email = ref('')
const password = ref('')
const username = ref('')
const workspaceId = ref('')
const workspaces = ref<Workspace[]>([])
const knowledgeBases = ref<KnowledgeBase[]>([])
const knowledgeBaseId = ref('')
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
const activeTab = ref<'overview' | 'documents' | 'retrieval' | 'chat' | 'settings'>('overview')

const POLL_INTERVAL = 5000
const PENDING_STATUSES = new Set(['ACCEPTED', 'PROCESSING', 'RETRYING'])
const hasPendingDocuments = computed(() => documents.value.some((item) => PENDING_STATUSES.has(item.status)))
let pollTimer: number | undefined
// 连续切换知识库时，先发的文档请求可能后返回；用序号丢弃过期响应。
let documentsRequestId = 0

const UPLOAD_ICON = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 16 12 12 8 16"/><line x1="12" y1="12" x2="12" y2="21"/><path d="M20.39 18.39A5 5 0 0 0 18 9h-1.26A8 8 0 1 0 3 16.3"/></svg>'
const INBOX_ICON = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 16 12 14 15 10 15 8 12 2 12"/><path d="M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/></svg>'
const SPARK_ICON = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>'

/** 任务状态显示为中文（数据本身仍是英文枚举） */
const STATUS_LABELS: Record<string, string> = {
  COMPLETED: '已完成', PROCESSING: '处理中', ACCEPTED: '已受理', RETRYING: '重试中', FAILED: '失败', ACTIVE: '正常',
}
const statusLabel = (value: string) => STATUS_LABELS[value] ?? value

onMounted(() => {
  window.addEventListener('aicopilot-auth-changed', syncAuthState)
  window.addEventListener('aicopilot-workspace-changed', handleGlobalWorkspaceChange)
  // 上传后的解析是后台任务，页面要自己跟上状态，不能让用户反复点「刷新列表」。
  pollTimer = window.setInterval(() => {
    if (hasPendingDocuments.value && !loading.value) void loadDocuments(true)
  }, POLL_INTERVAL)
  if (loggedIn.value) void restoreWorkspaceContext()
})

onBeforeUnmount(() => {
  window.clearInterval(pollTimer)
  documentsRequestId += 1
  window.removeEventListener('aicopilot-auth-changed', syncAuthState)
  window.removeEventListener('aicopilot-workspace-changed', handleGlobalWorkspaceChange)
})

function syncAuthState() {
  currentUser.value = readStoredUser()
  userId.value = currentUser.value ? String(currentUser.value.id) : ''
  loggedIn.value = Boolean(localStorage.getItem('accessToken') && currentUser.value)
  if (loggedIn.value) void restoreWorkspaceContext()
  else {
    workspaces.value = []; knowledgeBases.value = []; workspaceId.value = ''; knowledgeBaseId.value = ''
    knowledgeBase.value = null; documents.value = []; sessions.value = []; messages.value = []; currentSession.value = null
  }
}

function handleGlobalWorkspaceChange(event: Event) {
  const workspaceEvent = event as CustomEvent<{ workspaceId?: string }>
  const nextWorkspaceId = workspaceEvent.detail?.workspaceId
  if (!loggedIn.value || !nextWorkspaceId || nextWorkspaceId === workspaceId.value) return
  workspaceId.value = nextWorkspaceId
  void handleWorkspaceChange(false)
}

function showError(error: unknown, fallback: string) {
  message.value = error instanceof Error ? error.message : fallback
}

async function submitAuth() {
  loading.value = true
  message.value = ''
  try {
    if (authMode.value === 'register') {
      await registerUser(username.value, email.value, password.value)
      authMode.value = 'user'
      password.value = ''
      message.value = '注册成功，请使用新账号登录。'
      return
    }
    const isAdminLogin = authMode.value === 'admin'
    const response = await login(email.value, password.value, isAdminLogin)
    currentUser.value = response.data.user
    userId.value = String(response.data.user.id)
    localStorage.setItem('accessToken', response.data.accessToken)
    localStorage.setItem('currentUser', JSON.stringify(response.data.user))
    loggedIn.value = true
    message.value = `欢迎回来，${response.data.user.username}`
    window.dispatchEvent(new Event('aicopilot-auth-changed'))
    if (isAdminLogin) void router.push('/admin')
  } catch (error) {
    // 后端返回的是英文，管理员登录失败时给一句明确的中文提示
    if (authMode.value === 'admin' && error instanceof Error && error.message.includes('administrator')) {
      message.value = '该账号不是管理员，无法进入管理后台。'
    } else {
      showError(error, authMode.value === 'register' ? '注册失败' : '登录失败')
    }
  } finally { loading.value = false }
}

function switchAuthMode(mode: AuthMode) { authMode.value = mode; message.value = '' }

async function signOut() {
  // 先通知后端作废 token，再清本地状态（与 App.vue 的退出逻辑保持一致）
  await logout()
  localStorage.removeItem('accessToken')
  localStorage.removeItem('currentUser')
  localStorage.removeItem('activeWorkspaceId')
  currentUser.value = null; userId.value = ''; loggedIn.value = false
  workspaces.value = []; knowledgeBases.value = []; workspaceId.value = ''; knowledgeBaseId.value = ''
  knowledgeBase.value = null; documents.value = []; sessions.value = []; messages.value = []; currentSession.value = null
  window.dispatchEvent(new Event('aicopilot-auth-changed'))
}

async function restoreWorkspaceContext() {
  if (!userId.value) return
  loading.value = true
  try {
    const response = await listWorkspaces(Number(userId.value))
    workspaces.value = response.data
    const storedWorkspaceId = localStorage.getItem('activeWorkspaceId')
    if (storedWorkspaceId && workspaces.value.some((item) => String(item.id) === storedWorkspaceId)) workspaceId.value = storedWorkspaceId
    else if (!workspaceId.value && workspaces.value.length) workspaceId.value = String(workspaces.value[0].id)
    if (workspaceId.value) localStorage.setItem('activeWorkspaceId', workspaceId.value)
    if (workspaceId.value) await loadKnowledgeBases()
  } catch (error) { showError(error, '恢复工作空间失败')
  } finally { loading.value = false }
}

async function loadKnowledgeBases() {
  if (!workspaceId.value) return
  const response = await listKnowledgeBases(Number(userId.value), Number(workspaceId.value))
  knowledgeBases.value = response.data
  if (!knowledgeBases.value.some((item) => item.id === knowledgeBase.value?.id)) {
    knowledgeBaseId.value = knowledgeBases.value.length ? String(knowledgeBases.value[0].id) : ''
    if (knowledgeBases.value.length) await selectKnowledgeBase(knowledgeBases.value[0])
    else { knowledgeBase.value = null; documents.value = []; sessions.value = [] }
  }
}

async function handleWorkspaceChange(notifyShell = true) {
  if (workspaceId.value) {
    localStorage.setItem('activeWorkspaceId', workspaceId.value)
    if (notifyShell) window.dispatchEvent(new CustomEvent('aicopilot-workspace-changed', { detail: { workspaceId: workspaceId.value } }))
  }
  knowledgeBase.value = null
  knowledgeBaseId.value = ''
  documents.value = []
  sessions.value = []
  messages.value = []
  await loadKnowledgeBases()
}

function handleWorkspaceSelect() {
  void handleWorkspaceChange()
}

async function selectKnowledgeBase(item: KnowledgeBase) {
  knowledgeBase.value = item
  knowledgeBaseId.value = String(item.id)
  activeTab.value = 'overview'
  documents.value = []
  searchResults.value = []
  answer.value = null
  await Promise.all([loadDocuments(), loadConversations()])
}

async function handleKnowledgeBaseChange() {
  const selected = knowledgeBases.value.find((item) => item.id === Number(knowledgeBaseId.value))
  if (selected) await selectKnowledgeBase(selected)
}

function selectKnowledgeTab(tab: typeof activeTab.value) {
  activeTab.value = tab
}

async function createWorkspace() {
  if (!userId.value || !workspaceName.value.trim()) return
  loading.value = true; message.value = ''
  try {
    const response = await createWorkspaceApi(Number(userId.value), workspaceName.value.trim())
    workspaceId.value = String(response.data.id)
    localStorage.setItem('activeWorkspaceId', workspaceId.value)
    workspaces.value = [...workspaces.value, response.data]
    knowledgeBases.value = []
    knowledgeBase.value = null
    knowledgeBaseId.value = ''
    message.value = `工作空间已创建：${response.data.name}（ID ${response.data.id}）`
    window.dispatchEvent(new Event('aicopilot-workspaces-changed'))
    await handleWorkspaceChange(false)
  } catch (error) { showError(error, '创建工作空间失败')
  } finally { loading.value = false }
}

async function create() {
  if (!workspaceId.value || !name.value.trim()) return
  loading.value = true; message.value = ''
  try {
    const response = await createKnowledgeBase(Number(userId.value), Number(workspaceId.value), name.value, description.value)
    knowledgeBase.value = response.data; knowledgeBaseId.value = String(response.data.id)
    knowledgeBases.value = [...knowledgeBases.value, response.data]
    documents.value = []; searchResults.value = []; answer.value = null
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
  // 知识库页的侧栏是「会话预览」，不在这里翻页，直接取最大的一页。
  const response = await listConversations(Number(userId.value), knowledgeBase.value.id, '', 0, MAX_PAGE_SIZE)
  sessions.value = response.data.items
  if (!sessions.value.length) { const created = await createConversation(Number(userId.value), knowledgeBase.value.id); sessions.value = [created.data]; await selectSession(created.data) }
  else if (!currentSession.value || !sessions.value.some((session) => session.id === currentSession.value?.id)) await selectSession(sessions.value[0])
}

async function selectSession(session: ConversationSession) {
  if (!knowledgeBase.value) return
  currentSession.value = session
  // page=0 是最近的一批消息；这一页只用来预览，翻历史请到 AI 会话页。
  const response = await listConversationMessages(
    Number(userId.value), knowledgeBase.value.id, session.id, 0, MAX_PAGE_SIZE,
  )
  messages.value = response.data.items; answer.value = null
}

async function newSession() {
  if (!knowledgeBase.value) return
  loading.value = true
  try { const response = await createConversation(Number(userId.value), knowledgeBase.value.id); sessions.value = [response.data, ...sessions.value]; await selectSession(response.data) }
  catch (error) { showError(error, '创建会话失败') } finally { loading.value = false }
}

function selectFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0] ?? null
  if (file && !isSupportedDocument(file.name)) {
    selectedFile.value = null
    message.value = `不支持的文件类型，仅支持 ${SUPPORTED_DOCUMENT_LABEL} 文件`
    return
  }
  selectedFile.value = file
  message.value = ''
}

async function upload() {
  if (!knowledgeBase.value || !selectedFile.value) return
  loading.value = true; message.value = ''
  try { const response = await uploadDocument(Number(userId.value), knowledgeBase.value.id, selectedFile.value); documents.value = [response.data, ...documents.value]; message.value = '文档已上传，解析任务正在后台运行。'; selectedFile.value = null }
  catch (error) { showError(error, '上传失败') } finally { loading.value = false }
}

/** @param silent 轮询用：不动全局 loading，也不弹错误，避免每 5 秒锁一次页面。 */
async function loadDocuments(silent = false) {
  const base = knowledgeBase.value
  if (!base) return
  const requestId = ++documentsRequestId
  if (!silent) loading.value = true
  try {
    const response = await listDocuments(Number(userId.value), base.id)
    if (requestId !== documentsRequestId || knowledgeBase.value?.id !== base.id) return
    documents.value = response.data
  } catch (error) {
    if (!silent && requestId === documentsRequestId) showError(error, '获取文档失败')
  } finally {
    if (!silent && requestId === documentsRequestId) loading.value = false
  }
}

/** 模板绑定用：直接绑 loadDocuments 会把点击事件当成 silent。 */
function refreshDocuments() {
  void loadDocuments()
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

<style scoped>
.setup-column { align-content: start; }
.content-utility { display: flex; justify-content: flex-end; margin: 14px 0 0; }
.knowledge-advanced-facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px 18px; }
.knowledge-advanced-facts > div { display: grid; gap: 4px; min-width: 0; }
.knowledge-advanced-facts span { color: var(--muted); font-size: 12px; }
.knowledge-advanced-facts strong { overflow: hidden; color: var(--ink); font-family: var(--mono); font-size: 12px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.document-actions { display: flex; align-items: center; gap: 10px; }
</style>

<template>
  <main class="workspace-shell">
    <header class="workspace-header"><div><p class="eyebrow">知识工作台</p><h1>把团队知识，整理成可协作的资产</h1><p class="summary">上传资料，交给 AI 解析；用自然语言检索，并保留每次回答的来源。</p></div></header>

    <section v-if="!loggedIn" class="auth-layout">
      <div class="auth-intro"><p class="eyebrow">从文档到答案</p><h2>让知识真正成为团队的<em>共同记忆</em></h2><p>集中管理散落的资料，让每个答案都附带可追溯的引用来源。</p></div>
      <section class="panel auth-panel">
        <div class="auth-heading">
          <div><p class="eyebrow">{{ authMode === 'admin' ? '管理员' : '欢迎' }}</p><h2>{{ authMode === 'user' ? '登录工作台' : authMode === 'admin' ? '管理员登录' : '创建你的账号' }}</h2></div>
        </div>
        <div class="auth-tabs">
          <button :class="{ active: authMode === 'user' }" type="button" @click="switchAuthMode('user')">普通登录</button>
          <button :class="{ active: authMode === 'admin' }" type="button" @click="switchAuthMode('admin')">管理员登录</button>
          <button :class="{ active: authMode === 'register' }" type="button" @click="switchAuthMode('register')">注册</button>
        </div>
        <form @submit.prevent="submitAuth">
          <label v-if="authMode === 'register'">用户名<input v-model="username" required maxlength="64" placeholder="例如：研发小组" /></label>
          <label>邮箱<input v-model="email" type="email" required placeholder="name@company.com" autocomplete="email" /></label>
          <label>密码<input v-model="password" type="password" required minlength="8" autocomplete="current-password" placeholder="至少 8 位字符" /></label>
          <button class="primary-button wide-button" :disabled="loading" type="submit">{{ loading ? '处理中…' : authMode === 'user' ? '登录工作台' : authMode === 'admin' ? '进入管理后台' : '创建账号' }}</button>
        </form>
        <p class="form-hint">{{ authMode === 'admin' ? '仅限系统管理员，用于管理账号、工作空间与知识库。' : '业务数据使用 Bearer Token 保护，登录后即可开始创建工作空间。' }}</p>
        <p v-if="message" class="message" :class="{ 'message-error': message.includes('失败') || message.includes('错误') }">{{ message }}<button class="message-clear" type="button" title="关闭提示" @click="message = ''">×</button></p>
      </section>
    </section>

    <section v-else class="workspace-grid">
      <aside class="setup-column"><details class="panel setup-panel"><summary class="setup-summary"><span><p class="eyebrow">01 · 准备</p><h2>准备工作空间</h2></span><span class="setup-state">{{ knowledgeBase ? '已就绪' : '待完成' }}</span></summary><div class="setup-body"><p class="section-copy">选择已有空间，或创建一个新的工作空间。</p><label>当前工作空间<select v-model="workspaceId" :disabled="loading || !workspaces.length" @change="handleWorkspaceSelect"><option value="" disabled>{{ workspaces.length ? '请选择工作空间' : '暂时没有工作空间' }}</option><option v-for="workspace in workspaces" :key="workspace.id" :value="String(workspace.id)">{{ workspace.name }}</option></select></label></div></details>
      <details class="panel setup-panel"><summary class="setup-summary"><span><p class="eyebrow">02 · 创建</p><h2>创建知识库</h2></span><span class="setup-state">新建</span></summary><div class="setup-body"><p class="section-copy">为资料集合设置一个清晰的名字和描述。</p><form @submit.prevent="create"><label>知识库名称<input v-model="name" maxlength="128" required placeholder="例如：研发规范" /></label><label>描述<textarea v-model="description" maxlength="500" placeholder="可选：这套资料主要解决什么问题？" /></label><button class="primary-button wide-button" :disabled="loading || !workspaceId || !name.trim()" type="submit">{{ loading ? '处理中…' : '创建知识库' }}</button></form></div></details>
        <details class="panel setup-panel"><summary class="setup-summary"><span><p class="eyebrow">03 · 新空间</p><h2>新建工作空间</h2></span><span class="setup-state">可选</span></summary><div class="setup-body"><p class="section-copy">工作空间是知识库、文档和会话的协作边界。</p><div class="inline-create"><label>新空间名称<input v-model="workspaceName" maxlength="128" placeholder="例如：产品研发部" /></label><button class="secondary-button" :disabled="loading || !workspaceName.trim()" type="button" @click="createWorkspace">一键创建</button></div></div></details>
      <p v-if="message" class="message side-message" :class="{ 'message-error': message.includes('失败') || message.includes('错误') }">{{ message }}<button class="message-clear" type="button" title="关闭提示" @click="message = ''">×</button></p></aside>

      <section class="panel content-panel"><div class="section-heading content-heading"><div><p class="eyebrow">知识库</p><h2>{{ knowledgeBase ? knowledgeBase.name : '当前知识库' }}</h2><span v-if="knowledgeBase" class="content-subtitle">{{ knowledgeBase.description || '团队知识资产与 AI 问答空间' }}</span></div><div class="knowledge-selector"><span v-if="knowledgeBase" class="status-pill active">已连接</span><select v-model="knowledgeBaseId" :disabled="loading || !knowledgeBases.length" @change="handleKnowledgeBaseChange"><option value="" disabled>{{ knowledgeBases.length ? '选择知识库' : '暂无知识库' }}</option><option v-for="item in knowledgeBases" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></div></div>
        <div v-if="knowledgeBase" class="knowledge-content"><nav class="knowledge-tabs" aria-label="知识库分区"><button v-for="tab in [{ key: 'overview', label: '总览' }, { key: 'documents', label: '文档' }, { key: 'chat', label: 'AI 问答' }, { key: 'settings', label: '高级信息' }]" :key="tab.key" class="knowledge-tab" :class="{ active: activeTab === tab.key }" type="button" @click="selectKnowledgeTab(tab.key as typeof activeTab)">{{ tab.label }}</button></nav><div v-if="activeTab === 'overview' || activeTab === 'documents'" class="upload-zone"><div class="upload-icon" v-html="UPLOAD_ICON" /><div><strong>{{ selectedFile ? selectedFile.name : '上传一份资料' }}</strong><span>{{ selectedFile ? '已选择，准备开始解析' : '支持 ' + SUPPORTED_DOCUMENT_LABEL }}</span></div><label class="file-button">选择文件<input type="file" :accept="SUPPORTED_DOCUMENT_ACCEPT" @change="selectFile" /></label><button class="primary-button" :disabled="loading || !selectedFile" type="button" @click="upload">{{ loading ? '上传中…' : '上传并解析' }}</button></div>
          <div v-if="activeTab === 'overview' || activeTab === 'documents'" class="content-section"><div class="subsection-heading"><div><h3>文档资产</h3><span>{{ documents.length }} 个文件</span></div><button class="ghost-button" :disabled="loading" type="button" @click="refreshDocuments">刷新列表</button></div><div v-if="documents.length" class="document-list"><article v-for="item in documents" :key="item.id" class="document-row"><div class="document-info"><span class="file-type">{{ item.contentType.split('/').pop()?.slice(0, 4).toUpperCase() }}</span><div><strong>{{ item.originalFilename }}</strong><span>{{ Math.ceil(item.fileSize / 1024) }} KB · 任务 {{ item.processingTaskId || '未提交' }}</span><small v-if="item.processingFailureReason" class="failure-reason">{{ item.processingFailureReason }}</small><small v-if="item.processingRetryCount">已重试 {{ item.processingRetryCount }} 次</small></div></div><div class="document-actions"><span class="status-pill" :class="`status-${item.status.toLowerCase()}`">{{ statusLabel(item.status) }}</span><div><button class="text-button" :disabled="loading" type="button" @click="refreshStatus(item.id)">查询</button><button class="text-button" :disabled="loading" type="button" @click="reparse(item.id)">重新解析</button><button v-if="item.status === 'FAILED'" class="text-button danger" :disabled="loading" type="button" @click="retry(item.id)">重试</button></div></div></article></div><div v-else class="empty-state"><span class="empty-icon" v-html="INBOX_ICON" /><strong>还没有文档</strong><span>上传第一份资料，开始构建你的知识库。</span></div></div>
          <div v-if="activeTab === 'overview' || activeTab === 'chat'" class="content-section conversation-section"><div class="subsection-heading"><div><h3>AI 问答</h3><span>回答会自动保存到当前会话</span></div><button class="ghost-button" :disabled="loading" type="button" @click="newSession">新建会话</button></div><div v-if="sessions.length" class="session-list"><button v-for="session in sessions" :key="session.id" class="session-item" :class="{ active: currentSession?.id === session.id }" type="button" @click="selectSession(session)">{{ session.title }}</button></div><form class="ask-form" @submit.prevent="search"><input v-model="query" maxlength="1000" placeholder="输入问题或关键词，例如：研发流程有哪些阶段？" /><button class="secondary-button" :disabled="loading || !query.trim()" type="submit">检索</button><button class="primary-button" :disabled="loading || !query.trim() || !currentSession" type="button" @click="ask">生成回答</button></form><div v-if="messages.length" class="message-list"><article v-for="item in messages" :key="item.id" class="chat-message" :class="item.role.toLowerCase()"><small>{{ item.role === 'USER' ? '我' : 'AI 助手' }}</small><p>{{ item.content }}</p><small v-for="source in item.sources" :key="`${item.id}-${source.documentObjectKey}`" class="source-label">引用：{{ source.documentObjectKey }}</small></article></div><article v-if="answer" class="answer-card"><div class="answer-title"><span class="answer-icon" v-html="SPARK_ICON" /><h3>回答</h3></div><p>{{ answer.answer }}</p><small v-for="source in answer.sources" :key="source.documentObjectKey" class="source-label">来源：{{ source.documentObjectKey }}</small></article><div v-if="searchResults.length" class="search-results"><div class="result-label">检索结果 · {{ searchResults.length }}</div><article v-for="result in searchResults" :key="`${result.documentObjectKey}-${result.content}`" class="search-result"><small>{{ result.documentObjectKey }} · 相关度 {{ result.score.toFixed(2) }}</small><p>{{ result.content }}</p></article></div></div>
          <div v-if="activeTab === 'settings'" class="content-section"><div class="subsection-heading"><div><h3>知识库信息</h3><span>用于排查与对接的底层参数</span></div></div><div class="knowledge-advanced-facts"><div><span>知识库 ID</span><strong>#{{ knowledgeBase.id }}</strong></div><div><span>所属工作空间</span><strong>#{{ knowledgeBase.workspaceId }}</strong></div><div><span>状态</span><strong>{{ statusLabel(knowledgeBase.status) }}</strong></div><div><span>文档数量</span><strong>{{ documents.length }}</strong></div><div><span>Embedding 模型</span><strong>text-embedding-v4</strong></div><div><span>向量集合</span><strong>knowledge_chunks_v2</strong></div></div></div>
          <div class="content-utility"><button class="ghost-button" type="button" @click="signOut">退出登录</button></div>
        </div><div v-else class="empty-state large-empty"><span class="empty-icon" v-html="SPARK_ICON" /><strong>你的知识库会显示在这里</strong><span>完成左侧设置后，就可以上传文档并开始提问。</span></div>
      </section>
    </section>
  </main>
</template>
