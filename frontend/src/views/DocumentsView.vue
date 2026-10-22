<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { AUTH_CHANGED_EVENT, WORKSPACE_CHANGED_EVENT } from '@/services/crossTabSync'

import {
  listKnowledgeBases,
  listWorkspaceDocuments,
  listWorkspaces,
  reparseDocument,
  refreshDocumentProcessingStatus,
  retryDocumentProcessing,
  uploadDocument,
  type KnowledgeBase,
  type Workspace,
  type WorkspaceDocument,
} from '@/services/api'
import {
  isSupportedDocument,
  SUPPORTED_DOCUMENT_ACCEPT,
  SUPPORTED_DOCUMENT_LABEL,
} from '@/services/documentFormats'

type StoredUser = { id: number; username: string }

function readStoredUser(): StoredUser | null {
  const stored = localStorage.getItem('currentUser')
  if (!stored) return null
  try { return JSON.parse(stored) as StoredUser } catch { return null }
}

const currentUser = ref<StoredUser | null>(readStoredUser())
const workspaces = ref<Workspace[]>([])
const knowledgeBases = ref<KnowledgeBase[]>([])
const documents = ref<WorkspaceDocument[]>([])
const workspaceId = ref(localStorage.getItem('activeWorkspaceId') ?? '')
const knowledgeBaseId = ref('')
const searchQuery = ref('')
const statusFilter = ref('ALL')
const selectedFile = ref<File | null>(null)
const isDragging = ref(false)
const loading = ref(false)
const uploading = ref(false)
const message = ref('')
// 筛选切换很快时，先发的请求可能后返回；用序号丢弃过期响应。
let documentsRequestId = 0
let pollTimer: number | undefined
const POLL_INTERVAL = 5000
const PENDING_STATUSES = new Set(['ACCEPTED', 'PROCESSING', 'RETRYING'])
/** 有解析中的文档时才值得轮询，否则每 5 秒白打一次接口。 */
const hasPendingDocuments = computed(() => documents.value.some((item) => PENDING_STATUSES.has(item.status)))

const UPLOAD_ICON = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 16 12 12 8 16"/><line x1="12" y1="12" x2="12" y2="21"/><path d="M20.39 18.39A5 5 0 0 0 18 9h-1.26A8 8 0 1 0 3 16.3"/></svg>'
const INBOX_ICON = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 16 12 14 15 10 15 8 12 2 12"/><path d="M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/></svg>'

/** 任务状态显示为中文（数据本身仍是英文枚举） */
const STATUS_LABELS: Record<string, string> = {
  COMPLETED: '已完成', PROCESSING: '处理中', ACCEPTED: '已受理', RETRYING: '重试中', FAILED: '失败', ACTIVE: '正常',
}
const statusLabel = (value: string) => STATUS_LABELS[value] ?? value

const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'FAILED', label: '失败' },
  { value: 'RETRYING', label: '重试中' },
]

function formatSize(value: number) {
  if (value < 1024 * 1024) return `${Math.max(1, Math.ceil(value / 1024))} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'short', day: 'numeric' }).format(new Date(value))
}

function fileType(document: WorkspaceDocument) {
  return document.contentType.split('/').pop()?.slice(0, 4).toUpperCase() || 'FILE'
}

async function loadContext() {
  currentUser.value = readStoredUser()
  workspaceId.value = localStorage.getItem('activeWorkspaceId') ?? ''
  if (!currentUser.value) return
  loading.value = true
  message.value = ''
  try {
    const workspaceResponse = await listWorkspaces(currentUser.value.id)
    workspaces.value = workspaceResponse.data
    if (!workspaceId.value || !workspaces.value.some((item) => String(item.id) === workspaceId.value)) {
      workspaceId.value = workspaces.value.length ? String(workspaces.value[0].id) : ''
      if (workspaceId.value) localStorage.setItem('activeWorkspaceId', workspaceId.value)
    }
    await loadKnowledgeBases()
    await loadDocuments()
  } catch (error) {
    message.value = error instanceof Error ? error.message : '文档空间加载失败'
  } finally { loading.value = false }
}

async function loadKnowledgeBases() {
  if (!currentUser.value || !workspaceId.value) { knowledgeBases.value = []; return }
  const response = await listKnowledgeBases(currentUser.value.id, Number(workspaceId.value))
  knowledgeBases.value = response.data
  if (knowledgeBaseId.value && !knowledgeBases.value.some((item) => String(item.id) === knowledgeBaseId.value)) knowledgeBaseId.value = ''
}

/**
 * @param silent 轮询用：不往页面推错误提示，避免网络抖动时每 5 秒弹一次。
 */
async function loadDocuments(silent = false) {
  const requestId = ++documentsRequestId
  if (!currentUser.value || !workspaceId.value) { documents.value = []; return }
  try {
    const response = await listWorkspaceDocuments(currentUser.value.id, Number(workspaceId.value), {
      query: searchQuery.value,
      status: statusFilter.value,
      knowledgeBaseId: knowledgeBaseId.value ? Number(knowledgeBaseId.value) : undefined,
    })
    if (requestId !== documentsRequestId) return
    documents.value = response.data
  } catch (error) {
    if (!silent && requestId === documentsRequestId) message.value = error instanceof Error ? error.message : '加载文档失败'
  }
}

/** 模板里的搜索/筛选/刷新统一走这里 —— 直接绑 loadDocuments 会把事件对象当成 silent。 */
function applyFilters() {
  void loadDocuments()
}

async function changeWorkspace() {
  if (!workspaceId.value) return
  localStorage.setItem('activeWorkspaceId', workspaceId.value)
  knowledgeBaseId.value = ''
  documentsRequestId += 1
  documents.value = []
  loading.value = true
  try { await loadKnowledgeBases(); await loadDocuments(); window.dispatchEvent(new CustomEvent(WORKSPACE_CHANGED_EVENT, { detail: { workspaceId: workspaceId.value } })) }
  catch (error) { message.value = error instanceof Error ? error.message : '切换工作空间失败' }
  finally { loading.value = false }
}

function chooseFile(file: File | undefined) {
  if (!file) return
  if (!isSupportedDocument(file.name)) { message.value = `仅支持 ${SUPPORTED_DOCUMENT_LABEL} 文件`; return }
  selectedFile.value = file
  message.value = ''
}

function selectFile(event: Event) { chooseFile((event.target as HTMLInputElement).files?.[0]) }
function dropFile(event: DragEvent) { isDragging.value = false; chooseFile(event.dataTransfer?.files?.[0]) }

async function upload() {
  if (!currentUser.value || !knowledgeBaseId.value || !selectedFile.value) { message.value = '请先选择目标知识库和文件'; return }
  uploading.value = true
  try {
    const response = await uploadDocument(currentUser.value.id, Number(knowledgeBaseId.value), selectedFile.value)
    selectedFile.value = null
    message.value = `已上传 ${response.data.originalFilename}，解析任务正在后台运行。`
    await loadDocuments()
  } catch (error) { message.value = error instanceof Error ? error.message : '上传失败' }
  finally { uploading.value = false }
}

async function refresh(document: WorkspaceDocument) {
  if (!currentUser.value) return
  try {
    const response = await refreshDocumentProcessingStatus(currentUser.value.id, document.knowledgeBaseId, document.id)
    documents.value = documents.value.map((item) => item.id === document.id ? { ...item, ...response.data, knowledgeBaseName: document.knowledgeBaseName } : item)
  } catch (error) { message.value = error instanceof Error ? error.message : '查询状态失败' }
}

async function reparse(document: WorkspaceDocument) {
  if (!currentUser.value) return
  try { await reparseDocument(currentUser.value.id, document.knowledgeBaseId, document.id); message.value = `已提交「${document.originalFilename}」重新解析。`; await loadDocuments() }
  catch (error) { message.value = error instanceof Error ? error.message : '重新解析失败' }
}

async function retry(document: WorkspaceDocument) {
  if (!currentUser.value) return
  try { await retryDocumentProcessing(currentUser.value.id, document.knowledgeBaseId, document.id); message.value = `已提交「${document.originalFilename}」重试。`; await loadDocuments() }
  catch (error) { message.value = error instanceof Error ? error.message : '重试失败' }
}

onMounted(() => {
  window.addEventListener(AUTH_CHANGED_EVENT, loadContext)
  window.addEventListener(WORKSPACE_CHANGED_EVENT, loadContext)
  // 文档从上传到可检索要经过解析、切片、embedding，界面必须自己跟上状态，
  // 否则用户只能靠手动点「查询」才能知道处理完没有。
  pollTimer = window.setInterval(() => {
    if (hasPendingDocuments.value && !uploading.value) void loadDocuments(true)
  }, POLL_INTERVAL)
  void loadContext()
})

onBeforeUnmount(() => {
  window.clearInterval(pollTimer)
  documentsRequestId += 1
  window.removeEventListener(AUTH_CHANGED_EVENT, loadContext)
  window.removeEventListener(WORKSPACE_CHANGED_EVENT, loadContext)
})
</script>

<template>
  <main class="documents-shell">
    <header class="documents-header"><div><p class="eyebrow">文档资产库</p><h1>文档资产</h1><p class="documents-summary">集中查看工作空间中的全部文档，追踪解析状态并管理失败任务。</p></div><div class="documents-header-meta"><span class="status-pill active">{{ documents.length }} 个结果</span><span>真实数据 · MySQL</span></div></header>
    <section class="documents-toolbar panel"><div class="toolbar-field toolbar-search"><label>搜索文档<input v-model="searchQuery" placeholder="按文件名搜索…" @keyup.enter="applyFilters" /></label></div><label class="toolbar-field">工作空间<select v-model="workspaceId" :disabled="loading" @change="changeWorkspace"><option v-for="workspace in workspaces" :key="workspace.id" :value="String(workspace.id)">{{ workspace.name }}</option></select></label><label class="toolbar-field">知识库<select v-model="knowledgeBaseId" :disabled="loading || !knowledgeBases.length" @change="applyFilters"><option value="">全部知识库</option><option v-for="item in knowledgeBases" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><label class="toolbar-field">状态<select v-model="statusFilter" :disabled="loading" @change="applyFilters"><option v-for="status in statusOptions" :key="status.value" :value="status.value">{{ status.label }}</option></select></label><button class="secondary-button toolbar-button" :disabled="loading" type="button" @click="applyFilters">刷新</button></section>
    <section class="document-upload-layout"><div class="panel document-upload-card" :class="{ dragging: isDragging }" @dragenter.prevent="isDragging = true" @dragover.prevent="isDragging = true" @dragleave.prevent="isDragging = false" @drop.prevent="dropFile"><div class="upload-icon" v-html="UPLOAD_ICON" /><div><strong>{{ selectedFile ? selectedFile.name : '拖拽文件到这里' }}</strong><span>{{ selectedFile ? `${formatSize(selectedFile.size)} · 已准备上传` : '或点击选择，支持 ' + SUPPORTED_DOCUMENT_LABEL }}</span></div><label class="file-button">选择文件<input type="file" :accept="SUPPORTED_DOCUMENT_ACCEPT" @change="selectFile" /></label><button class="primary-button" :disabled="uploading || !selectedFile || !knowledgeBaseId" type="button" @click="upload">{{ uploading ? '上传中…' : '上传文档' }}</button></div><div class="panel document-upload-note"><p class="eyebrow">处理流水线</p><strong>上传后自动解析和索引</strong><span>文件会进入 MinIO，随后由 AI Service 完成文本提取、切分、Embedding 和 Milvus 索引。</span><span v-if="!knowledgeBaseId" class="failure-reason">请先在筛选栏选择一个目标知识库。</span></div></section>
    <p v-if="message" class="message documents-message">{{ message }}</p>
    <section class="panel document-table-card"><div class="document-table-heading"><div><p class="eyebrow">全部文档</p><h2>工作空间文档</h2></div><span class="section-meta">{{ loading ? '加载中…' : `${documents.length} 个文件` }}</span></div><div v-if="documents.length" class="document-table"><article v-for="document in documents" :key="document.id" class="document-table-row"><span class="file-type">{{ fileType(document) }}</span><div class="document-table-main"><strong>{{ document.originalFilename }}</strong><span>{{ document.knowledgeBaseName }} · {{ formatSize(document.fileSize) }} · {{ formatDate(document.createdAt) }}</span><small v-if="document.processingFailureReason" class="failure-reason">{{ document.processingFailureReason }}</small><small v-if="document.processingRetryCount">已重试 {{ document.processingRetryCount }} 次</small></div><span class="status-pill" :class="`status-${document.status.toLowerCase()}`">{{ statusLabel(document.status) }}</span><div class="document-table-actions"><button class="text-button" type="button" @click="refresh(document)">查询</button><button class="text-button" type="button" @click="reparse(document)">重新解析</button><button v-if="document.status === 'FAILED'" class="text-button danger" type="button" @click="retry(document)">重试</button></div></article></div><div v-else class="empty-state documents-empty"><span class="empty-icon" v-html="INBOX_ICON" /><strong>{{ loading ? '正在加载文档' : '没有匹配的文档' }}</strong><span>{{ loading ? '正在读取当前工作空间的文档资产。' : '调整搜索或筛选条件，或者上传第一份资料。' }}</span></div></section>
  </main>
</template>
