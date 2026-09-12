<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

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

async function loadDocuments() {
  if (!currentUser.value || !workspaceId.value) { documents.value = []; return }
  const response = await listWorkspaceDocuments(currentUser.value.id, Number(workspaceId.value), {
    query: searchQuery.value,
    status: statusFilter.value,
    knowledgeBaseId: knowledgeBaseId.value ? Number(knowledgeBaseId.value) : undefined,
  })
  documents.value = response.data
}

async function changeWorkspace() {
  if (!workspaceId.value) return
  localStorage.setItem('activeWorkspaceId', workspaceId.value)
  knowledgeBaseId.value = ''
  loading.value = true
  try { await loadKnowledgeBases(); await loadDocuments(); window.dispatchEvent(new CustomEvent('aicopilot-workspace-changed', { detail: { workspaceId: workspaceId.value } })) }
  catch (error) { message.value = error instanceof Error ? error.message : '切换 Workspace 失败' }
  finally { loading.value = false }
}

function chooseFile(file: File | undefined) {
  if (!file) return
  const supported = ['.txt', '.md', '.csv', '.pdf', '.docx']
  if (!supported.some((extension) => file.name.toLowerCase().endsWith(extension))) { message.value = '仅支持 TXT、MD、CSV、PDF、DOCX 文件'; return }
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
  window.addEventListener('aicopilot-auth-changed', loadContext)
  window.addEventListener('aicopilot-workspace-changed', loadContext)
  void loadContext()
})

onBeforeUnmount(() => {
  window.removeEventListener('aicopilot-auth-changed', loadContext)
  window.removeEventListener('aicopilot-workspace-changed', loadContext)
})
</script>

<template>
  <main class="documents-shell">
    <header class="documents-header"><div><p class="eyebrow">DOCUMENT LIBRARY</p><h1>文档资产</h1><p class="documents-summary">集中查看 Workspace 中的全部文档，追踪解析状态并管理失败任务。</p></div><div class="documents-header-meta"><span class="status-pill active">{{ documents.length }} 个结果</span><span>真实数据 · MySQL</span></div></header>
    <section class="documents-toolbar panel"><div class="toolbar-field toolbar-search"><label>搜索文档<input v-model="searchQuery" placeholder="按文件名搜索…" @keyup.enter="loadDocuments" /></label></div><label class="toolbar-field">Workspace<select v-model="workspaceId" :disabled="loading" @change="changeWorkspace"><option v-for="workspace in workspaces" :key="workspace.id" :value="String(workspace.id)">{{ workspace.name }}</option></select></label><label class="toolbar-field">知识库<select v-model="knowledgeBaseId" :disabled="loading || !knowledgeBases.length" @change="loadDocuments"><option value="">全部知识库</option><option v-for="item in knowledgeBases" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><label class="toolbar-field">状态<select v-model="statusFilter" :disabled="loading" @change="loadDocuments"><option v-for="status in statusOptions" :key="status.value" :value="status.value">{{ status.label }}</option></select></label><button class="secondary-button toolbar-button" :disabled="loading" type="button" @click="loadDocuments">刷新</button></section>
    <section class="document-upload-layout"><div class="panel document-upload-card" :class="{ dragging: isDragging }" @dragenter.prevent="isDragging = true" @dragover.prevent="isDragging = true" @dragleave.prevent="isDragging = false" @drop.prevent="dropFile"><div class="upload-icon">↑</div><div><strong>{{ selectedFile ? selectedFile.name : '拖拽文件到这里' }}</strong><span>{{ selectedFile ? `${formatSize(selectedFile.size)} · 已准备上传` : '或点击选择，支持 TXT、MD、CSV、PDF、DOCX' }}</span></div><label class="file-button">选择文件<input type="file" accept=".txt,.md,.csv,.pdf,.docx" @change="selectFile" /></label><button class="primary-button" :disabled="uploading || !selectedFile || !knowledgeBaseId" type="button" @click="upload">{{ uploading ? '上传中…' : '上传文档' }}</button></div><div class="panel document-upload-note"><p class="eyebrow">PROCESSING PIPELINE</p><strong>上传后自动解析和索引</strong><span>文件会进入 MinIO，随后由 AI Service 完成文本提取、切分、Embedding 和 Milvus 索引。</span><span v-if="!knowledgeBaseId" class="failure-reason">请先在筛选栏选择一个目标知识库。</span></div></section>
    <p v-if="message" class="message documents-message">{{ message }}</p>
    <section class="panel document-table-card"><div class="document-table-heading"><div><p class="eyebrow">ALL DOCUMENTS</p><h2>Workspace 文档</h2></div><span class="section-meta">{{ loading ? '加载中…' : `${documents.length} 个文件` }}</span></div><div v-if="documents.length" class="document-table"><article v-for="document in documents" :key="document.id" class="document-table-row"><span class="file-type">{{ fileType(document) }}</span><div class="document-table-main"><strong>{{ document.originalFilename }}</strong><span>{{ document.knowledgeBaseName }} · {{ formatSize(document.fileSize) }} · {{ formatDate(document.createdAt) }}</span><small v-if="document.processingFailureReason" class="failure-reason">{{ document.processingFailureReason }}</small><small v-if="document.processingRetryCount">已重试 {{ document.processingRetryCount }} 次</small></div><span class="status-pill" :class="`status-${document.status.toLowerCase()}`">{{ document.status }}</span><div class="document-table-actions"><button class="text-button" type="button" @click="refresh(document)">查询</button><button class="text-button" type="button" @click="reparse(document)">重新解析</button><button v-if="document.status === 'FAILED'" class="text-button danger" type="button" @click="retry(document)">重试</button></div></article></div><div v-else class="empty-state documents-empty"><span class="empty-icon">◫</span><strong>{{ loading ? '正在加载文档' : '没有匹配的文档' }}</strong><span>{{ loading ? '正在读取当前 Workspace 的文档资产。' : '调整搜索或筛选条件，或者上传第一份资料。' }}</span></div></section>
  </main>
</template>
