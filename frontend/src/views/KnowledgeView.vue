<script setup lang="ts">
import { ref } from 'vue'

import {
  createKnowledgeBase,
  askKnowledge,
  listDocuments,
  reparseDocument,
  refreshDocumentProcessingStatus,
  retryDocumentProcessing,
  searchKnowledge,
  uploadDocument,
  type KnowledgeBase,
  type KnowledgeAnswer,
  type KnowledgeDocument,
  type KnowledgeRetrievalChunk,
} from '@/services/api'

const workspaceId = ref('')
const userId = ref('')
const name = ref('')
const description = ref('')
const knowledgeBase = ref<KnowledgeBase | null>(null)
const documents = ref<KnowledgeDocument[]>([])
const message = ref('')
const loading = ref(false)
const selectedFile = ref<File | null>(null)
const query = ref('')
const searchResults = ref<KnowledgeRetrievalChunk[]>([])
const answer = ref<KnowledgeAnswer | null>(null)

async function create() {
  message.value = ''
  loading.value = true
  try {
    const response = await createKnowledgeBase(Number(userId.value), Number(workspaceId.value), name.value, description.value)
    knowledgeBase.value = response.data
    documents.value = []
    message.value = '知识库创建成功，可以登记文档元数据。'
  } catch (error) {
    message.value = error instanceof Error ? error.message : '创建失败'
  } finally {
    loading.value = false
  }
}

async function search() {
  if (!knowledgeBase.value || !query.value.trim()) return
  loading.value = true
  try {
    const response = await searchKnowledge(Number(userId.value), knowledgeBase.value.id, query.value.trim())
    searchResults.value = response.data
  } catch (error) {
    message.value = error instanceof Error ? error.message : '检索失败'
  } finally {
    loading.value = false
  }
}

async function ask() {
  if (!knowledgeBase.value || !query.value.trim()) return
  loading.value = true
  try {
    const response = await askKnowledge(Number(userId.value), knowledgeBase.value.id, query.value.trim())
    answer.value = response.data
  } catch (error) {
    message.value = error instanceof Error ? error.message : '问答失败'
  } finally {
    loading.value = false
  }
}

function selectFile(event: Event) {
  selectedFile.value = (event.target as HTMLInputElement).files?.[0] ?? null
}

async function upload() {
  if (!knowledgeBase.value || !selectedFile.value) return
  loading.value = true
  try {
    const response = await uploadDocument(Number(userId.value), knowledgeBase.value.id, selectedFile.value)
    documents.value = [response.data, ...documents.value]
    message.value = '文档上传成功，已登记并提交解析任务。'
  } catch (error) {
    message.value = error instanceof Error ? error.message : '登记失败'
  } finally {
    loading.value = false
  }
}

async function loadDocuments() {
  if (!knowledgeBase.value) return
  loading.value = true
  try {
    const response = await listDocuments(Number(userId.value), knowledgeBase.value.id)
    documents.value = response.data
  } catch (error) {
    message.value = error instanceof Error ? error.message : '获取文档失败'
  } finally {
    loading.value = false
  }
}

async function refreshStatus(documentId: number) {
  if (!knowledgeBase.value) return
  loading.value = true
  try {
    const response = await refreshDocumentProcessingStatus(Number(userId.value), knowledgeBase.value.id, documentId)
    documents.value = documents.value.map((item) => item.id === documentId ? response.data : item)
  } catch (error) {
    message.value = error instanceof Error ? error.message : '查询解析状态失败'
  } finally {
    loading.value = false
  }
}

async function reparse(documentId: number) {
  if (!knowledgeBase.value) return
  loading.value = true
  try {
    const response = await reparseDocument(Number(userId.value), knowledgeBase.value.id, documentId)
    documents.value = documents.value.map((item) => item.id === documentId ? response.data : item)
    message.value = '已删除旧向量并提交重新解析任务。'
  } catch (error) {
    message.value = error instanceof Error ? error.message : '重新解析失败'
  } finally {
    loading.value = false
  }
}

async function retry(documentId: number) {
  if (!knowledgeBase.value) return
  loading.value = true
  try {
    const response = await retryDocumentProcessing(Number(userId.value), knowledgeBase.value.id, documentId)
    documents.value = documents.value.map((item) => item.id === documentId ? response.data : item)
    message.value = '已提交失败任务重试。'
  } catch (error) {
    message.value = error instanceof Error ? error.message : '重试失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="workspace-shell">
    <header class="workspace-header">
      <p class="eyebrow">KNOWLEDGE WORKSPACE</p>
      <h1>把团队知识，整理成可协作的资产。</h1>
      <p class="summary">创建知识库后上传文档，系统会将文件保存到对象存储并提交 AI 解析。</p>
    </header>

    <section class="workspace-grid">
      <form class="panel" @submit.prevent="create">
        <h2>创建知识库</h2>
        <label>当前用户 ID<input v-model="userId" type="number" min="1" required /></label>
        <label>工作空间 ID<input v-model="workspaceId" type="number" min="1" required /></label>
        <label>名称<input v-model="name" maxlength="128" required placeholder="例如：研发规范" /></label>
        <label>描述<textarea v-model="description" maxlength="500" placeholder="可选" /></label>
        <button :disabled="loading" type="submit">{{ loading ? '处理中…' : '创建知识库' }}</button>
      </form>

      <section class="panel result-panel">
        <h2>当前知识库</h2>
        <div v-if="knowledgeBase" class="result-card">
          <strong>{{ knowledgeBase.name }}</strong>
          <span>编号 #{{ knowledgeBase.id }} · {{ knowledgeBase.status }}</span>
          <label class="file-picker">选择文档<input type="file" @change="selectFile" /></label>
          <button :disabled="loading || !selectedFile" type="button" @click="upload">{{ loading ? '上传中…' : '上传并解析' }}</button>
          <button :disabled="loading" class="secondary" type="button" @click="loadDocuments">刷新文档列表</button>
        </div>
        <p v-else class="empty">创建后将在这里显示知识库信息。</p>
        <div v-if="documents.length" class="document-list">
          <article v-for="item in documents" :key="item.id" class="document-row">
            <div>
              <strong>{{ item.originalFilename }}</strong>
              <span>{{ item.contentType }} · {{ Math.ceil(item.fileSize / 1024) }} KB</span>
              <small v-if="item.processingTaskId">任务 {{ item.processingTaskId }}</small>
              <small v-if="item.processingFailureReason" class="failure-reason">失败：{{ item.processingFailureReason }}</small>
              <small v-if="item.processingRetryCount">已重试 {{ item.processingRetryCount }} 次</small>
            </div>
            <div class="document-actions">
              <span class="status">{{ item.status }}</span>
              <button :disabled="loading" class="secondary" type="button" @click="refreshStatus(item.id)">查询状态</button>
              <button :disabled="loading" class="secondary" type="button" @click="reparse(item.id)">重新解析</button>
              <button v-if="item.status === 'FAILED'" :disabled="loading" class="secondary" type="button" @click="retry(item.id)">重试任务</button>
            </div>
          </article>
        </div>
        <form v-if="knowledgeBase" class="search-form" @submit.prevent="search">
          <label>检索知识库<input v-model="query" maxlength="1000" placeholder="输入问题或关键词" /></label>
          <button :disabled="loading || !query.trim()" type="submit">检索</button>
          <button :disabled="loading || !query.trim()" type="button" @click="ask">生成回答</button>
        </form>
        <article v-if="answer" class="answer-card">
          <h3>回答</h3><p>{{ answer.answer }}</p>
          <small v-for="source in answer.sources" :key="source.documentObjectKey">来源：{{ source.documentObjectKey }}</small>
        </article>
        <div v-if="searchResults.length" class="search-results">
          <article v-for="result in searchResults" :key="`${result.documentObjectKey}-${result.content}`" class="search-result">
            <small>{{ result.documentObjectKey }} · 相关度 {{ result.score.toFixed(2) }}</small>
            <p>{{ result.content }}</p>
          </article>
        </div>
        <p v-if="message" class="message">{{ message }}</p>
      </section>
    </section>
  </main>
</template>
