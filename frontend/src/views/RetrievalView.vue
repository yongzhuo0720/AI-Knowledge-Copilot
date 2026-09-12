<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  askKnowledge,
  listKnowledgeBases,
  listWorkspaces,
  searchKnowledge,
  type KnowledgeBase,
  type KnowledgeAnswer,
  type KnowledgeRetrievalChunk,
  type Workspace,
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
const workspaceId = ref(localStorage.getItem('activeWorkspaceId') ?? '')
const knowledgeBaseId = ref('')
const query = ref('')
const limit = ref(5)
const retrievedChunks = ref<KnowledgeRetrievalChunk[]>([])
const answer = ref<KnowledgeAnswer | null>(null)
const loading = ref(false)
const message = ref('')
const hasRun = ref(false)

const activeKnowledgeBase = computed(() => knowledgeBases.value.find((item) => item.id === Number(knowledgeBaseId.value)))
const context = computed(() => retrievedChunks.value.map((chunk, index) => `[${index + 1}] ${chunk.content}`).join('\n\n'))
const promptPreview = computed(() => [
  '只依据以下资料用中文回答问题；资料不足时明确说明。不要编造事实。',
  '在回答相关句末使用 [1]、[2] 等编号引用资料。',
  '',
  `资料：\n${context.value || '（运行检索后生成）'}`,
  '',
  `问题：${query.value || '（请输入问题）'}`,
].join('\n'))

async function loadContext() {
  currentUser.value = readStoredUser()
  if (!currentUser.value) return
  loading.value = true
  try {
    const workspaceResponse = await listWorkspaces(currentUser.value.id)
    workspaces.value = workspaceResponse.data
    if (!workspaceId.value || !workspaces.value.some((item) => String(item.id) === workspaceId.value)) workspaceId.value = workspaces.value.length ? String(workspaces.value[0].id) : ''
    if (workspaceId.value) await loadKnowledgeBases()
  } catch (error) {
    message.value = error instanceof Error ? error.message : 'Retrieval 加载失败'
  } finally { loading.value = false }
}

async function loadKnowledgeBases() {
  if (!currentUser.value || !workspaceId.value) return
  const response = await listKnowledgeBases(currentUser.value.id, Number(workspaceId.value))
  knowledgeBases.value = response.data
  if (!knowledgeBases.value.some((item) => String(item.id) === knowledgeBaseId.value)) knowledgeBaseId.value = knowledgeBases.value.length ? String(knowledgeBases.value[0].id) : ''
}

async function changeWorkspace() {
  if (!workspaceId.value) return
  localStorage.setItem('activeWorkspaceId', workspaceId.value)
  knowledgeBaseId.value = ''
  retrievedChunks.value = []
  answer.value = null
  hasRun.value = false
  await loadKnowledgeBases()
}

async function runDebug() {
  if (!currentUser.value || !knowledgeBaseId.value || !query.value.trim() || loading.value) return
  loading.value = true
  message.value = ''
  hasRun.value = true
  answer.value = null
  try {
    const searchResponse = await searchKnowledge(currentUser.value.id, Number(knowledgeBaseId.value), query.value.trim())
    retrievedChunks.value = searchResponse.data.slice(0, limit.value)
    const answerResponse = await askKnowledge(currentUser.value.id, Number(knowledgeBaseId.value), query.value.trim())
    answer.value = answerResponse.data
    if (answer.value.sources.length) retrievedChunks.value = answer.value.sources
    if (!retrievedChunks.value.length) message.value = '没有命中相关 Chunk，请换个问题试试。'
  } catch (error) {
    message.value = error instanceof Error ? error.message : 'Retrieval 调试失败'
  } finally { loading.value = false }
}

function formatScore(score: number) { return score.toFixed(4) }

onMounted(() => { void loadContext() })
</script>

<template>
  <main class="retrieval-shell">
    <header class="retrieval-header"><div><p class="eyebrow">RETRIEVAL DEBUG</p><h1>看见一次 RAG 是怎样发生的。</h1><p class="retrieval-summary">用真实 Embedding 和 Milvus 检索结果，拆开 Query、Context、Prompt 与最终回答。</p></div><div class="retrieval-header-meta"><span class="status-dot online" />真实链路调试</div></header>
    <section class="retrieval-toolbar panel"><label class="retrieval-workspace">Workspace<select v-model="workspaceId" :disabled="loading" @change="changeWorkspace"><option v-for="workspace in workspaces" :key="workspace.id" :value="String(workspace.id)">{{ workspace.name }}</option></select></label><label class="retrieval-base">Knowledge Base<select v-model="knowledgeBaseId" :disabled="loading || !knowledgeBases.length"><option value="" disabled>{{ knowledgeBases.length ? '选择知识库' : '暂无知识库' }}</option><option v-for="item in knowledgeBases" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><label class="retrieval-query">Query<input v-model="query" maxlength="1000" placeholder="输入要调试的检索问题…" @keyup.enter="runDebug" /></label><label class="retrieval-limit">Top-K<select v-model.number="limit"><option :value="3">3</option><option :value="5">5</option><option :value="10">10</option></select></label><button class="primary-button retrieval-run" :disabled="loading || !knowledgeBaseId || !query.trim()" type="button" @click="runDebug">{{ loading ? '运行中…' : '运行调试' }} <span>→</span></button></section>
    <p v-if="message" class="message retrieval-message">{{ message }}</p>
    <section class="retrieval-overview"><article class="retrieval-stat panel"><span class="metric-label">QUERY EMBEDDING</span><strong>1024</strong><small>text-embedding-v4 · 向量维度</small></article><article class="retrieval-stat panel"><span class="metric-label">RETRIEVED CHUNKS</span><strong>{{ retrievedChunks.length }}</strong><small>{{ activeKnowledgeBase?.name || '尚未选择知识库' }}</small></article><article class="retrieval-stat panel"><span class="metric-label">TOP SCORE</span><strong>{{ retrievedChunks.length ? formatScore(retrievedChunks[0].score) : '—' }}</strong><small>Inner Product 相似度</small></article><article class="retrieval-stat panel"><span class="metric-label">ANSWER</span><strong>{{ answer ? 'READY' : '—' }}</strong><small>DeepSeek · 带引用回答</small></article></section>
    <section v-if="!hasRun" class="retrieval-empty panel"><span class="answer-icon">⌕</span><strong>输入 Query 开始观察</strong><span>运行后这里会展示真实检索到的文档片段、上下文组装和最终回答。</span></section>
    <section v-else class="retrieval-grid"><section class="retrieval-results panel"><div class="retrieval-section-heading"><div><p class="eyebrow">01 · RETRIEVAL</p><h2>Retrieved Chunks</h2></div><span class="section-meta">按 Score 降序</span></div><div v-if="retrievedChunks.length" class="retrieval-chunk-list"><article v-for="(chunk, index) in retrievedChunks" :key="`${chunk.documentObjectKey}-${index}`" class="retrieval-chunk"><div class="retrieval-chunk-top"><span class="source-index">{{ index + 1 }}</span><strong>{{ chunk.documentObjectKey.split('/').pop() }}</strong><span class="retrieval-score">{{ formatScore(chunk.score) }}</span></div><small>{{ chunk.documentObjectKey }}</small><p>{{ chunk.content }}</p></article></div><div v-else class="mini-empty">没有返回 Chunk。</div></section><div class="retrieval-side"><section class="retrieval-code panel"><div class="retrieval-section-heading"><div><p class="eyebrow">02 · CONTEXT</p><h2>Context</h2></div></div><pre>{{ context || '运行检索后生成上下文。' }}</pre></section><section class="retrieval-code panel"><div class="retrieval-section-heading"><div><p class="eyebrow">03 · PROMPT</p><h2>Prompt Preview</h2></div></div><pre>{{ promptPreview }}</pre></section></div><section class="retrieval-answer panel"><div class="retrieval-section-heading"><div><p class="eyebrow">04 · GENERATION</p><h2>Final Answer</h2></div><span v-if="answer" class="status-pill status-completed">已完成</span></div><p v-if="answer" class="retrieval-answer-text">{{ answer.answer }}</p><div v-if="answer?.sources.length" class="retrieval-answer-sources"><span class="source-label">引用来源</span><span v-for="(source, index) in answer.sources" :key="`${source.documentObjectKey}-${index}`" class="retrieval-answer-source">[{{ index + 1 }}] {{ source.documentObjectKey }}</span></div><div v-else class="mini-empty">回答将在这里展示，并保留来源。</div></section></section>
  </main>
</template>
