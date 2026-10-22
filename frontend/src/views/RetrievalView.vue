<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { WORKSPACE_CHANGED_EVENT } from '@/services/crossTabSync'

import {
  askKnowledge,
  listKnowledgeBases,
  listWorkspaces,
  searchKnowledge,
  type KnowledgeBase,
  type KnowledgeAnswer,
  type KnowledgeRetrievalChunk,
  type RetrievalMode,
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

/** 当前检索模式。默认 hybrid —— 和服务端默认值保持一致。 */
const mode = ref<RetrievalMode>('hybrid')
/** 三路对比结果，key 是模式名 */
const comparison = ref<Record<RetrievalMode, KnowledgeRetrievalChunk[]> | null>(null)
/** 每发起一次检索就 +1：切知识库/改查询后，旧请求迟到的响应必须丢弃。 */
let runRequestId = 0

const MODE_LABELS: Record<RetrievalMode, string> = {
  vector: '向量召回',
  keyword: 'BM25 关键词',
  hybrid: '混合（RRF 融合）',
}
const RETRIEVER_LABELS: Record<string, string> = {
  vector: '向量',
  keyword: '关键词',
  hybrid: '双路命中',
}
const RETRIEVAL_MODES: RetrievalMode[] = ['vector', 'keyword', 'hybrid']

const SEARCH_ICON = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>'

function retrieverLabel(value?: RetrievalMode | null) { return RETRIEVER_LABELS[value ?? 'vector'] ?? '向量' }

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
  if (!currentUser.value || !workspaceId.value) { knowledgeBases.value = []; return }
  try {
    const response = await listKnowledgeBases(currentUser.value.id, Number(workspaceId.value))
    knowledgeBases.value = response.data
    if (!knowledgeBases.value.some((item) => String(item.id) === knowledgeBaseId.value)) knowledgeBaseId.value = knowledgeBases.value.length ? String(knowledgeBases.value[0].id) : ''
  } catch (error) {
    message.value = error instanceof Error ? error.message : '加载知识库失败'
  }
}

/** 让在途检索失效，并清掉上一份结果 —— 否则新知识库下会短暂显示旧知识库的命中。 */
function resetResults() {
  runRequestId += 1
  loading.value = false
  retrievedChunks.value = []
  answer.value = null
  comparison.value = null
  hasRun.value = false
  message.value = ''
}

async function changeWorkspace() {
  if (!workspaceId.value) return
  localStorage.setItem('activeWorkspaceId', workspaceId.value)
  // 局部切换必须通知全局侧栏（App.vue 与跨标签同步都监听这个事件）
  window.dispatchEvent(new CustomEvent(WORKSPACE_CHANGED_EVENT, { detail: { workspaceId: workspaceId.value } }))
  knowledgeBaseId.value = ''
  resetResults()
  await loadKnowledgeBases()
}

function changeKnowledgeBase() {
  resetResults()
}

async function runDebug() {
  if (!currentUser.value || !knowledgeBaseId.value || !query.value.trim() || loading.value) return
  // 先把值取到局部变量：await 之后 currentUser / knowledgeBaseId 可能已被切走。
  const userId = currentUser.value.id
  const baseId = Number(knowledgeBaseId.value)
  const text = query.value.trim()
  const requestId = ++runRequestId
  loading.value = true
  message.value = ''
  hasRun.value = true
  answer.value = null
  comparison.value = null
  try {
    const searchResponse = await searchKnowledge(userId, baseId, text, mode.value, limit.value)
    if (requestId !== runRequestId) return
    retrievedChunks.value = searchResponse.data.slice(0, limit.value)
    const answerResponse = await askKnowledge(userId, baseId, text)
    if (requestId !== runRequestId) return
    answer.value = answerResponse.data
    // 检索结果面板保留「所选模式」的召回（调试的主角），不被回答面板的 sources 覆盖；
    // 回答的引用来源由 answer.sources 自己展示。
    if (!retrievedChunks.value.length) message.value = '没有命中相关 Chunk，请换个问题试试。'
  } catch (error) {
    if (requestId === runRequestId) message.value = error instanceof Error ? error.message : 'Retrieval 调试失败'
  } finally {
    // 旧请求收尾时不能碰 loading：新请求可能正在跑。
    if (requestId === runRequestId) loading.value = false
  }
}

/**
 * 同一个 Query 分别跑三种检索模式，并排看召回差异。
 * 这是验证混合检索是否真的有效的最直接办法 —— 单看一次结果无法判断。
 */
async function runComparison() {
  if (!currentUser.value || !knowledgeBaseId.value || !query.value.trim() || loading.value) return
  // 先把值取到局部变量：闭包里 TS 无法保留 currentUser.value 的非空收窄
  const userId = currentUser.value.id
  const baseId = Number(knowledgeBaseId.value)
  const text = query.value.trim()
  const requestId = ++runRequestId
  loading.value = true
  message.value = ''
  hasRun.value = true
  answer.value = null
  try {
    const responses = await Promise.all(RETRIEVAL_MODES.map((item) =>
      searchKnowledge(userId, baseId, text, item)))
    if (requestId !== runRequestId) return
    comparison.value = {
      vector: responses[0].data,
      keyword: responses[1].data,
      hybrid: responses[2].data,
    }
    retrievedChunks.value = comparison.value[mode.value].slice(0, limit.value)
    if (!Object.values(comparison.value).some((chunks) => chunks.length)) message.value = '三种模式都没有命中，换个提问方式试试。'
  } catch (error) {
    if (requestId === runRequestId) message.value = error instanceof Error ? error.message : '检索对比失败'
  } finally {
    if (requestId === runRequestId) loading.value = false
  }
}

/** 检索框的回车：输入法选词阶段不触发，Shift+Enter 留给换行。 */
function handleQueryKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey) return
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  void runDebug()
}

/** 某个片段是否被该模式召回 —— 用于对比视图里标出差异 */
function keysOf(chunks: KnowledgeRetrievalChunk[]) {
  return new Set(chunks.map((chunk) => `${chunk.documentObjectKey}::${chunk.content.slice(0, 80)}`))
}

function formatScore(score: number) { return score.toFixed(4) }

/** 侧栏（或另一标签页）切换工作空间时同步本页。幂等：自己派发的同一事件到达时直接返回。 */
function syncWorkspaceFromGlobal(event: Event) {
  const next = (event as CustomEvent<{ workspaceId?: string }>).detail?.workspaceId ?? localStorage.getItem('activeWorkspaceId') ?? ''
  if (!next || next === workspaceId.value) return
  workspaceId.value = next
  knowledgeBaseId.value = ''
  resetResults()
  void loadKnowledgeBases()
}

onMounted(() => {
  window.addEventListener(WORKSPACE_CHANGED_EVENT, syncWorkspaceFromGlobal)
  void loadContext()
})

onBeforeUnmount(() => {
  window.removeEventListener(WORKSPACE_CHANGED_EVENT, syncWorkspaceFromGlobal)
})
</script>

<template>
  <main class="retrieval-shell">
    <header class="retrieval-header"><div><p class="eyebrow">检索调试</p><h1>看见一次 RAG 是怎样发生的</h1><p class="retrieval-summary">用真实 Embedding 和 Milvus 检索结果，拆开查询、上下文、提示词与最终回答。</p></div><div class="retrieval-header-meta"><span class="status-dot online" />真实链路调试</div></header>
    <section class="retrieval-toolbar panel"><label class="retrieval-workspace">工作空间<select v-model="workspaceId" :disabled="loading" @change="changeWorkspace"><option v-for="workspace in workspaces" :key="workspace.id" :value="String(workspace.id)">{{ workspace.name }}</option></select></label><label class="retrieval-base">知识库<select v-model="knowledgeBaseId" :disabled="loading || !knowledgeBases.length" @change="changeKnowledgeBase"><option value="" disabled>{{ knowledgeBases.length ? '选择知识库' : '暂无知识库' }}</option><option v-for="item in knowledgeBases" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><label class="retrieval-query">查询<input v-model="query" maxlength="1000" placeholder="输入要调试的检索问题…" @keydown="handleQueryKeydown" /></label><label class="retrieval-limit">Top-K<select v-model.number="limit"><option :value="3">3</option><option :value="5">5</option><option :value="10">10</option></select></label><label class="retrieval-limit retrieval-mode">检索模式<select v-model="mode"><option v-for="item in RETRIEVAL_MODES" :key="item" :value="item">{{ MODE_LABELS[item] }}</option></select></label><div class="retrieval-actions"><button class="primary-button retrieval-run" :disabled="loading || !knowledgeBaseId || !query.trim()" type="button" @click="runDebug">{{ loading ? '运行中…' : '运行调试' }} <span>→</span></button><button class="secondary-button" :disabled="loading || !knowledgeBaseId || !query.trim()" type="button" title="同一个查询分别跑三种模式，并排对比召回差异" @click="runComparison">对比三种模式</button></div></section>
    <p v-if="message" class="message retrieval-message">{{ message }}</p>
    <section class="retrieval-overview"><article class="retrieval-stat panel"><span class="metric-label">查询向量</span><strong>1024</strong><small>text-embedding-v4 · 向量维度</small></article><article class="retrieval-stat panel"><span class="metric-label">命中片段</span><strong>{{ retrievedChunks.length }}</strong><small>{{ activeKnowledgeBase?.name || '尚未选择知识库' }}</small></article><article class="retrieval-stat panel"><span class="metric-label">最高相似度</span><strong>{{ retrievedChunks.length ? formatScore(retrievedChunks[0].score) : '—' }}</strong><small>Inner Product 相似度</small></article><article class="retrieval-stat panel"><span class="metric-label">回答</span><strong>{{ answer ? '就绪' : '—' }}</strong><small>DeepSeek · 带引用回答</small></article></section>
    <section v-if="comparison" class="retrieval-compare panel"><div class="retrieval-section-heading"><div><p class="eyebrow">三路对比</p><h2>三种检索模式对比</h2></div><span class="section-meta">同一个查询 · Top {{ limit }}</span></div><p class="retrieval-compare-hint">纯向量对专有名词、编号、缩写不敏感；BM25 靠字面精确匹配补上这块；混合模式用 RRF 把两路排名融合。下面的对比就是这个结论的直接证据。</p><div class="retrieval-compare-grid"><article v-for="item in RETRIEVAL_MODES" :key="item" class="retrieval-compare-column"><header><strong>{{ MODE_LABELS[item] }}</strong><small>{{ comparison[item].length }} 个片段</small></header><div v-if="comparison[item].length" class="retrieval-compare-list"><div v-for="(chunk, index) in comparison[item].slice(0, limit)" :key="`${item}-${index}`" class="retrieval-compare-item" :class="{ 'only-here': !RETRIEVAL_MODES.filter((other) => other !== item).some((other) => keysOf(comparison![other]).has(`${chunk.documentObjectKey}::${chunk.content.slice(0, 80)}`)) }"><span class="retrieval-compare-rank">{{ index + 1 }}</span><div><strong>{{ chunk.documentObjectKey.split('/').pop() }}</strong><small>{{ formatScore(chunk.score) }} · {{ retrieverLabel(chunk.retriever) }}</small><p>{{ chunk.content.slice(0, 120) }}</p></div></div></div><div v-else class="mini-empty">没有命中</div></article></div></section>
    <section v-if="!hasRun" class="retrieval-empty panel"><span class="answer-icon" v-html="SEARCH_ICON" /><strong>输入查询开始观察</strong><span>运行后这里会展示真实检索到的文档片段、上下文组装和最终回答。</span></section>
    <section v-else class="retrieval-grid"><section class="retrieval-results panel"><div class="retrieval-section-heading"><div><p class="eyebrow">01 · 检索</p><h2>命中片段</h2></div><span class="section-meta">按相似度降序</span></div><div v-if="retrievedChunks.length" class="retrieval-chunk-list"><article v-for="(chunk, index) in retrievedChunks" :key="`${chunk.documentObjectKey}-${index}`" class="retrieval-chunk"><div class="retrieval-chunk-top"><span class="source-index">{{ index + 1 }}</span><strong>{{ chunk.documentObjectKey.split('/').pop() }}</strong><span class="retriever-badge" :class="`retriever-${chunk.retriever ?? 'vector'}`">{{ retrieverLabel(chunk.retriever) }}</span><span class="retrieval-score">{{ formatScore(chunk.score) }}</span></div><small>{{ chunk.documentObjectKey }}</small><p>{{ chunk.content }}</p></article></div><div v-else class="mini-empty">没有返回片段。</div></section><div class="retrieval-side"><section class="retrieval-code panel"><div class="retrieval-section-heading"><div><p class="eyebrow">02 · 上下文</p><h2>上下文</h2></div></div><pre>{{ context || '运行检索后生成上下文。' }}</pre></section><section class="retrieval-code panel"><div class="retrieval-section-heading"><div><p class="eyebrow">03 · 提示词</p><h2>提示词预览</h2></div></div><pre>{{ promptPreview }}</pre></section></div><section class="retrieval-answer panel"><div class="retrieval-section-heading"><div><p class="eyebrow">04 · 生成</p><h2>最终回答</h2></div><span v-if="answer" class="status-pill status-completed">已完成</span></div><p v-if="answer" class="retrieval-answer-text">{{ answer.answer }}</p><div v-if="answer?.sources.length" class="retrieval-answer-sources"><span class="source-label">引用来源</span><span v-for="(source, index) in answer.sources" :key="`${source.documentObjectKey}-${index}`" class="retrieval-answer-source">[{{ index + 1 }}] {{ source.documentObjectKey }}</span></div><div v-else class="mini-empty">回答将在这里展示，并保留来源。</div></section></section>
  </main>
</template>
