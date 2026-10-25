<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import {
  deleteDocument,
  listWorkspaceDocuments,
  previewDocument,
  refreshDocumentProcessingStatus,
  reparseDocument,
  retryDocumentProcessing,
  uploadDocument,
  type DocumentPreview,
  type WorkspaceDocument,
} from '@/services/api'
import {
  isSupportedDocument,
  SUPPORTED_DOCUMENT_ACCEPT,
  SUPPORTED_DOCUMENT_LABEL,
} from '@/services/documentFormats'
import { stageLabel, statusLabel } from '@/services/labels'
import { fileType, formatSize } from '@/services/format'
import { useWorkspaceContext } from '@/composables/useWorkspaceContext'

const { currentUser, workspaces, workspaceId, knowledgeBases, loadContext, switchWorkspace, onContextChange } = useWorkspaceContext()

const knowledgeBaseId = ref('')
const documents = ref<WorkspaceDocument[]>([])
const searchQuery = ref('')
const statusFilter = ref('ALL')
const selectedFile = ref<File | null>(null)
const isDragging = ref(false)
const loading = ref(false)
const uploading = ref(false)
const message = ref('')
// 预览弹层的状态。previewFor 记录「这份预览属于哪一行」：ai-service 要重新下载
// 原文做抽取，请求期间用户可能换筛选/换空间，回填前必须核对还是同一篇文档。
const previewFor = ref<WorkspaceDocument | null>(null)
const previewData = ref<DocumentPreview | null>(null)
const previewLoading = ref(false)
const previewError = ref('')
// 筛选切换很快时，先发的请求可能后返回；用序号丢弃过期响应。
let documentsRequestId = 0
let pollTimer: number | undefined
const POLL_INTERVAL = 5000
const PENDING_STATUSES = new Set(['ACCEPTED', 'PROCESSING', 'RETRYING'])
/** 有解析中的文档时才值得轮询，否则每 5 秒白打一次接口。 */
const hasPendingDocuments = computed(() => documents.value.some((item) => PENDING_STATUSES.has(item.status)))

/** 解析中行的进度条：没有上报进度时按状态给个保底值，避免空条闪烁。 */
function progressOf(document: WorkspaceDocument) {
  if (document.processingProgress != null) return document.processingProgress
  return document.status === 'ACCEPTED' ? 3 : 8
}

function stageTextOf(document: WorkspaceDocument) {
  return stageLabel(document.processingStage) ?? statusLabel(document.status)
}

const UPLOAD_ICON = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="16 16 12 12 8 16"/><line x1="12" y1="12" x2="12" y2="21"/><path d="M20.39 18.39A5 5 0 0 0 18 9h-1.26A8 8 0 1 0 3 16.3"/></svg>'
const INBOX_ICON = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 16 12 14 15 10 15 8 12 2 12"/><path d="M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/></svg>'

/** 任务状态机的终态。到达后不会再自发回到处理中（重试是用户显式触发的独立端点）。 */
const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED'])

const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'FAILED', label: '失败' },
  { value: 'RETRYING', label: '重试中' },
]

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'short', day: 'numeric' }).format(new Date(value))
}

/**
 * 上下文（登录态 / 工作空间 / 知识库列表）由 useWorkspaceContext 统一维护；
 * 这里只负责：工作空间变了就清空知识库筛选，然后刷新文档列表。
 */
onContextChange(({ workspaceChanged }) => {
  if (workspaceChanged) knowledgeBaseId.value = ''
  if (knowledgeBaseId.value && !knowledgeBases.value.some((item) => String(item.id) === knowledgeBaseId.value)) knowledgeBaseId.value = ''
  void loadDocuments()
})

/**
 * @param silent 轮询用：不往页面推错误提示，避免网络抖动时每 5 秒弹一次。
 */
async function loadDocuments(silent = false) {
  const requestId = ++documentsRequestId
  if (!workspaceId.value) { documents.value = []; return }
  try {
    const response = await listWorkspaceDocuments(Number(workspaceId.value), {
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
    const response = await uploadDocument(Number(knowledgeBaseId.value), selectedFile.value)
    selectedFile.value = null
    message.value = `已上传 ${response.data.originalFilename}，解析任务正在后台运行。`
    await loadDocuments()
  } catch (error) { message.value = error instanceof Error ? error.message : '上传失败' }
  finally { uploading.value = false }
}

/**
 * 拉取单篇文档的实时任务数据（含阶段进度）并合并进行内。
 * @param silent 轮询用：不往页面推错误提示，避免网络抖动时每 5 秒弹一次。
 */
async function refresh(document: WorkspaceDocument, silent = false) {
  try {
    const response = await refreshDocumentProcessingStatus(document.knowledgeBaseId, document.id)
    // 轮询/手动查询回来时文档可能已被删除或换行：按 id 找不到就丢弃
    const existing = documents.value.find((item) => item.id === document.id)
    if (!existing) return
    // 轮询同时发「列表刷新」和「单行任务刷新」两个请求，返回顺序不确定。
    // 任务快照更旧时（读到 PROCESSING、列表已读到 COMPLETED）直接合并会把终态行
    // 拉回「处理中」，进度条闪回、轮询多跑一轮。终态是单调的，守住它即可。
    if (TERMINAL_STATUSES.has(existing.status)) return
    documents.value = documents.value.map((item) => item.id === document.id ? { ...item, ...response.data, knowledgeBaseName: document.knowledgeBaseName } : item)
  } catch (error) {
    if (!silent) message.value = error instanceof Error ? error.message : '查询状态失败'
  }
}

/** 打开预览弹层并加载内容。 */
async function openPreview(document: WorkspaceDocument) {
  previewFor.value = document
  previewData.value = null
  previewError.value = ''
  previewLoading.value = true
  // Escape 关闭挂在 window 上：弹层里的滚动区会吃掉焦点，弹层自己的 keydown 收不到
  window.addEventListener('keydown', handlePreviewKeydown)
  try {
    const response = await previewDocument(document.knowledgeBaseId, document.id)
    // 请求期间用户可能已关掉弹层或换到另一篇：对不上就丢弃
    if (previewFor.value?.id !== document.id) return
    previewData.value = response.data
  } catch (error) {
    if (previewFor.value?.id === document.id) {
      previewError.value = error instanceof Error ? error.message : '获取文档预览失败'
    }
  } finally {
    if (previewFor.value?.id === document.id) previewLoading.value = false
  }
}

function closePreview() {
  previewFor.value = null
  previewData.value = null
  previewError.value = ''
  previewLoading.value = false
  window.removeEventListener('keydown', handlePreviewKeydown)
}

function handlePreviewKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closePreview()
}

async function reparse(document: WorkspaceDocument) {
  try { await reparseDocument(document.knowledgeBaseId, document.id); message.value = `已提交「${document.originalFilename}」重新解析。`; await loadDocuments() }
  catch (error) { message.value = error instanceof Error ? error.message : '重新解析失败' }
}

async function retry(document: WorkspaceDocument) {
  // FAILED 且已提交过解析任务才走「重试既有任务」；否则按新任务重新解析。
  if (document.status === 'FAILED' && document.processingTaskId) {
    try { await retryDocumentProcessing(document.knowledgeBaseId, document.id); message.value = `已提交「${document.originalFilename}」重试。`; await loadDocuments() }
    catch (error) { message.value = error instanceof Error ? error.message : '重试失败' }
    return
  }
  await reparse(document)
}

async function removeDocument(document: WorkspaceDocument) {
  if (!window.confirm(`确定删除「${document.originalFilename}」吗？向量索引与存储对象会一并清理，操作不可撤销。`)) return
  try {
    await deleteDocument(document.knowledgeBaseId, document.id)
    message.value = `已删除「${document.originalFilename}」。`
    await loadDocuments()
  } catch (error) { message.value = error instanceof Error ? error.message : '删除失败' }
}

onMounted(async () => {
  // 文档从上传到可检索要经过解析、切片、embedding，界面必须自己跟上状态，
  // 否则用户只能靠手动点「查询」才能知道处理完没有。
  pollTimer = window.setInterval(() => {
    if (uploading.value) return
    const pending = documents.value.filter((item) => PENDING_STATUSES.has(item.status))
    if (!pending.length) return
    // 列表刷新兜状态流转（COMPLETED 等）；单行刷新兜阶段进度——进度只存在于
    // ai-service 的任务响应里，列表接口（只读 MySQL）拿不到。
    void loadDocuments(true)
    for (const item of pending) void refresh(item, true)
  }, POLL_INTERVAL)
  loading.value = true
  try { await loadContext() }
  catch (error) { message.value = error instanceof Error ? error.message : '文档空间加载失败' }
  finally { loading.value = false }
})

onBeforeUnmount(() => {
  window.clearInterval(pollTimer)
  window.removeEventListener('keydown', handlePreviewKeydown)
  documentsRequestId += 1
})
</script>

<template>
  <main class="documents-shell">
    <header class="documents-header"><div><p class="eyebrow">文档资产库</p><h1>文档资产</h1><p class="documents-summary">集中查看工作空间中的全部文档，追踪解析状态并管理失败任务。</p></div><div class="documents-header-meta"><span class="status-pill active">{{ documents.length }} 个结果</span><span>真实数据 · MySQL</span></div></header>
    <section class="documents-toolbar panel"><div class="toolbar-field toolbar-search"><label>搜索文档<input v-model="searchQuery" placeholder="按文件名搜索…" @keyup.enter="applyFilters" /></label></div><label class="toolbar-field">工作空间<select :value="workspaceId" :disabled="loading" @change="switchWorkspace(($event.target as HTMLSelectElement).value)"><option v-for="workspace in workspaces" :key="workspace.id" :value="String(workspace.id)">{{ workspace.name }}</option></select></label><label class="toolbar-field">知识库<select v-model="knowledgeBaseId" :disabled="loading || !knowledgeBases.length" @change="applyFilters"><option value="">全部知识库</option><option v-for="item in knowledgeBases" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><label class="toolbar-field">状态<select v-model="statusFilter" :disabled="loading" @change="applyFilters"><option v-for="status in statusOptions" :key="status.value" :value="status.value">{{ status.label }}</option></select></label><button class="secondary-button toolbar-button" :disabled="loading" type="button" @click="applyFilters">刷新</button></section>
    <section class="document-upload-layout"><div class="panel document-upload-card" :class="{ dragging: isDragging }" @dragenter.prevent="isDragging = true" @dragover.prevent="isDragging = true" @dragleave.prevent="isDragging = false" @drop.prevent="dropFile"><div class="upload-icon" v-html="UPLOAD_ICON" /><div><strong>{{ selectedFile ? selectedFile.name : '拖拽文件到这里' }}</strong><span>{{ selectedFile ? `${formatSize(selectedFile.size)} · 已准备上传` : '或点击选择，支持 ' + SUPPORTED_DOCUMENT_LABEL }}</span></div><label class="file-button">选择文件<input type="file" :accept="SUPPORTED_DOCUMENT_ACCEPT" @change="selectFile" /></label><button class="primary-button" :disabled="uploading || !selectedFile || !knowledgeBaseId" type="button" @click="upload">{{ uploading ? '上传中…' : '上传文档' }}</button></div><div class="panel document-upload-note"><p class="eyebrow">处理流水线</p><strong>上传后自动解析和索引</strong><span>文件会进入 MinIO，随后由 AI Service 完成文本提取、切分、Embedding 和 Milvus 索引。</span><span v-if="!knowledgeBaseId" class="failure-reason">请先在筛选栏选择一个目标知识库。</span></div></section>
    <p v-if="message" class="message documents-message">{{ message }}</p>
    <section class="panel document-table-card"><div class="document-table-heading"><div><p class="eyebrow">全部文档</p><h2>工作空间文档</h2></div><span class="section-meta">{{ loading ? '加载中…' : `${documents.length} 个文件` }}</span></div><div v-if="documents.length" class="document-table"><article v-for="document in documents" :key="document.id" class="document-table-row"><span class="file-type">{{ fileType(document.contentType) }}</span><div class="document-table-main"><strong>{{ document.originalFilename }}</strong><span>{{ document.knowledgeBaseName }} · {{ formatSize(document.fileSize) }} · {{ formatDate(document.createdAt) }}</span><small v-if="document.processingFailureReason" class="failure-reason">{{ document.processingFailureReason }}</small><small v-if="document.processingRetryCount">已重试 {{ document.processingRetryCount }} 次</small><div v-if="PENDING_STATUSES.has(document.status)" class="parse-progress"><div class="progress-track"><div class="progress-fill" :style="{ width: `${progressOf(document)}%` }" /></div><small class="progress-copy">{{ stageTextOf(document) }} · {{ progressOf(document) }}%</small></div></div><span class="status-pill" :class="`status-${document.status.toLowerCase()}`">{{ statusLabel(document.status) }}</span><div class="document-table-actions"><button class="text-button" type="button" @click="openPreview(document)">预览</button><button class="text-button" type="button" @click="refresh(document)">查询</button><button class="text-button" type="button" @click="retry(document)">{{ document.status === 'FAILED' ? '重试' : '重新解析' }}</button><button class="text-button danger" type="button" @click="removeDocument(document)">删除</button></div></article></div><div v-else class="empty-state documents-empty"><span class="empty-icon" v-html="INBOX_ICON" /><strong>{{ loading ? '正在加载文档' : '没有匹配的文档' }}</strong><span>{{ loading ? '正在读取当前工作空间的文档资产。' : '调整搜索或筛选条件，或者上传第一份资料。' }}</span></div></section>
  <div v-if="previewFor" class="preview-overlay" role="dialog" aria-modal="true" :aria-label="`预览 ${previewFor.originalFilename}`" @click.self="closePreview">
    <section class="panel preview-modal">
      <header class="preview-modal-heading"><div><p class="eyebrow">文档预览</p><h2>{{ previewFor.originalFilename }}</h2><span class="content-subtitle">按解析时的结构化分节展示，用于上传前快速核对内容。</span></div><button class="secondary-button" type="button" @click="closePreview">关闭</button></header>
      <div v-if="previewLoading" class="preview-state"><span class="loading-orb" />正在读取并解析原文…</div>
      <div v-else-if="previewError" class="preview-state preview-state-error"><strong>暂时无法预览</strong><span>{{ previewError }}</span><button class="secondary-button" type="button" @click="openPreview(previewFor)">重试</button></div>
      <template v-else-if="previewData">
        <p v-if="previewData.truncated" class="preview-truncated">文档较长，仅展示前 {{ previewData.sectionCount }} 节（约 {{ previewData.blockCount }} 段）。完整内容仍会全部进入检索。</p>
        <div v-if="!previewData.sections.length" class="empty-state"><strong>没有可展示的文本</strong><span>这份文档可能为空，或只包含无法抽取的内容（例如纯图片扫描件）。</span></div>
        <article v-for="(section, index) in previewData.sections" :key="index" class="preview-section">
          <p v-if="section.titlePath.length" class="preview-section-title">{{ section.titlePath.join(' > ') }}</p>
          <p v-for="(block, blockIndex) in section.blocks" :key="blockIndex" class="preview-block">{{ block }}</p>
        </article>
      </template>
    </section>
  </div>
  </main>
</template>
