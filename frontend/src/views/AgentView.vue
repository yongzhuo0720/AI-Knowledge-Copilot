<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import {
  askConversationAgent,
  createConversation,
  listConversationMessages,
  MAX_PAGE_SIZE,
  type KnowledgeAgentAnswer,
  type ConversationMessage,
  type ConversationSession,
} from '@/services/api'
import { looksLikeMarkdown, renderMarkdown } from '@/services/markdown'
import { resultUnit, toAgentAnswer } from '@/services/agentTrace'
import { SPARK_ICON } from '@/services/icons'
import { isSendHotkey } from '@/services/input'
import { useChatSessions } from '@/composables/useChatSessions'
import { useWorkspaceContext } from '@/composables/useWorkspaceContext'

const { currentUser, workspaces, workspaceId, knowledgeBases, loadContext, switchWorkspace, onContextChange } = useWorkspaceContext()

const knowledgeBaseId = ref('')

// Agent 页只是「选一个会话来复看执行轨迹」，会话列表与 Chat 页共用同一套
// 组合式函数，取最大的一页（100 条），不在侧栏里翻页。
const {
  sessions,
  sessionId,
  hasMoreSessions,
  selectSession,
  loadSessions,
  refreshFirstPage,
  reset: resetSessions,
} = useChatSessions({
  knowledgeBaseId,
  pageSize: MAX_PAGE_SIZE,
  onSessionSelected: loadSessionMessages,
  createSession: newSession,
})

const messages = ref<ConversationMessage[]>([])
const question = ref('')
const result = ref<KnowledgeAgentAnswer | null>(null)
const loading = ref(false)
const message = ref('')
// 连续切换会话 / 知识库时，先发出的请求可能后返回；用序号丢弃过期响应。
let messageRequestId = 0
let agentRunRequestId = 0

// Agent 页不翻页，「列表被 100 条截断」就是 hasMoreSessions 变成 true 的那一刻；
// 执行完一次任务后 refreshFirstPage 也会改写它，挂在 watch 上两处都能覆盖到提示。
watch(hasMoreSessions, (truncated) => {
  if (truncated) message.value = `会话较多，这里只列出最近 ${MAX_PAGE_SIZE} 个，请到 AI 会话页搜索更早的会话。`
})

const activeKnowledgeBase = computed(() => knowledgeBases.value.find((item) => item.id === Number(knowledgeBaseId.value)))

/** 上下文（登录态 / 工作空间 / 知识库）由 useWorkspaceContext 维护；这里只跟着刷新会话链。 */
onContextChange(({ workspaceChanged }) => {
  if (workspaceChanged) {
    knowledgeBaseId.value = ''
    resetConversationState()
  }
  if (!currentUser.value || !workspaceId.value) return
  if (!knowledgeBases.value.some((item) => String(item.id) === knowledgeBaseId.value)) {
    knowledgeBaseId.value = knowledgeBases.value.length ? String(knowledgeBases.value[0].id) : ''
  }
  void reloadSessions().catch((error) => { message.value = error instanceof Error ? error.message : 'Agent 加载失败' })
})

/** 清空会话与结果面板，并让在途的消息加载失效。 */
function resetConversationState() {
  messageRequestId += 1
  resetSessions()
  messages.value = []
  result.value = null
}

/** 重新加载会话列表；上下文不完整时只清空，并把在途的 Agent 执行一并作废。 */
async function reloadSessions() {
  if (!currentUser.value || !knowledgeBaseId.value) {
    // 切走时让在途的会话加载和 Agent 执行全部失效。
    agentRunRequestId += 1
    resetConversationState()
    return
  }
  await loadSessions()
}

async function loadSessionMessages(session: ConversationSession) {
  if (!knowledgeBaseId.value) return
  const requestId = ++messageRequestId
  // 消息同理：page=0 就是最近的一批，取满一页够看执行轨迹了。
  try {
    const response = await listConversationMessages(
      Number(knowledgeBaseId.value), session.id, 0, MAX_PAGE_SIZE,
    )
    if (requestId !== messageRequestId || sessionId.value !== session.id) return
    messages.value = response.data.items
    const latestAssistant = [...messages.value].reverse().find((item) => item.role === 'ASSISTANT')
    result.value = latestAssistant ? toAgentAnswer(latestAssistant) : null
  } catch (error) {
    if (requestId === messageRequestId) message.value = error instanceof Error ? error.message : '加载会话失败'
  }
}

async function newSession() {
  if (!knowledgeBaseId.value) return
  const response = await createConversation(Number(knowledgeBaseId.value), 'Agent 会话')
  sessions.value = [response.data, ...sessions.value]
  await selectSession(response.data)
}

async function runAgent() {
  if (!knowledgeBaseId.value || !question.value.trim() || loading.value) return
  const text = question.value.trim()
  const requestId = ++agentRunRequestId
  loading.value = true
  message.value = ''
  try {
    if (!sessionId.value) await newSession()
    const fromSessionId = sessionId.value
    const fromBaseId = Number(knowledgeBaseId.value)
    if (!fromSessionId) return
    const response = await askConversationAgent(fromBaseId, fromSessionId, text)
    // 执行期间切了知识库或会话：这次结果属于上一屏，写进来会串台。
    if (requestId !== agentRunRequestId || Number(knowledgeBaseId.value) !== fromBaseId || sessionId.value !== fromSessionId) return
    messages.value = [...messages.value, response.data.userMessage, response.data.assistantMessage]
    result.value = toAgentAnswer(response.data.assistantMessage)
    await refreshFirstPage()
  } catch (error) {
    if (requestId === agentRunRequestId) message.value = error instanceof Error ? error.message : 'Agent 执行失败'
  } finally {
    if (requestId === agentRunRequestId) loading.value = false
  }
}

function handleQuestionKeydown(event: KeyboardEvent) {
  if (!isSendHotkey(event)) return
  event.preventDefault()
  void runAgent()
}

onMounted(async () => {
  loading.value = true
  try { await loadContext() }
  catch (error) { message.value = error instanceof Error ? error.message : 'Agent 加载失败' }
  finally { loading.value = false }
})
</script>

<template>
  <main class="agent-shell">
    <header class="agent-header"><div><p class="eyebrow">知识库 Agent</p><h1>让 Agent 先检索，再回答</h1><p class="agent-summary">这是一个真实可解释的 Tool Calling 闭环：模型决定调用工具，工具只访问当前知识库，最后返回带引用的答案。</p></div><span class="agent-badge"><span class="status-dot online" />受权限保护</span></header>
    <section class="agent-toolbar panel"><label>工作空间<select :value="workspaceId" :disabled="loading" @change="switchWorkspace(($event.target as HTMLSelectElement).value)"><option v-for="workspace in workspaces" :key="workspace.id" :value="String(workspace.id)">{{ workspace.name }}</option></select></label><label>知识库<select v-model="knowledgeBaseId" :disabled="loading || !knowledgeBases.length" @change="reloadSessions"><option value="" disabled>{{ knowledgeBases.length ? '选择知识库' : '暂无知识库' }}</option><option v-for="item in knowledgeBases" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><label>会话<select v-model="sessionId" :disabled="loading || !sessions.length" @change="selectSession(sessions.find((item) => item.id === sessionId)!)"><option v-for="item in sessions" :key="item.id" :value="item.id">{{ item.title }}</option></select></label><label class="agent-question">任务 / 问题<input v-model="question" maxlength="1000" placeholder="例如：总结这套资料中的发布流程" @keydown="handleQuestionKeydown" /></label><button class="primary-button" :disabled="loading || !knowledgeBaseId || !question.trim()" type="button" @click="runAgent">{{ loading ? '执行中…' : '运行 Agent' }} <span>→</span></button></section>
    <p v-if="message" class="message agent-message">{{ message }}</p>
    <section class="agent-flow"><article class="agent-flow-step panel"><span>01</span><strong>规划</strong><small>理解问题并决定是否检索</small></article><span class="agent-flow-arrow">→</span><article class="agent-flow-step panel"><span>02</span><strong>工具调用</strong><small>调用 knowledge_search 等工具</small></article><span class="agent-flow-arrow">→</span><article class="agent-flow-step panel"><span>03</span><strong>生成回答</strong><small>基于来源生成回答</small></article></section>
    <section v-if="!result" class="agent-empty panel"><span class="answer-icon" v-html="SPARK_ICON" /><strong>准备执行一个知识任务</strong><span>{{ activeKnowledgeBase ? `当前知识库：${activeKnowledgeBase.name}` : '先选择一个知识库' }}</span></section>
    <section v-else class="agent-result-grid"><section class="agent-steps panel"><div class="agent-section-heading"><div><p class="eyebrow">执行轨迹</p><h2>执行轨迹</h2></div><span class="section-meta">{{ result.steps.length }} 个步骤</span></div><article v-for="(step, index) in result.steps" :key="`${step.tool}-${index}`" class="agent-step"><span class="source-index">{{ index + 1 }}</span><div><strong>{{ step.tool }}</strong><small>{{ step.query }}</small></div><span class="status-pill status-completed">{{ step.resultCount }} {{ resultUnit(step.tool) }}</span></article></section><section class="agent-answer panel"><div class="agent-section-heading"><div><p class="eyebrow">Agent 回答</p><h2>最终回答</h2></div><span class="status-pill status-completed">已完成 · {{ messages.length }} 条消息</span></div><div v-if="looksLikeMarkdown(result.answer)" class="markdown-body" v-html="renderMarkdown(result.answer)" /><p v-else class="agent-answer-text">{{ result.answer }}</p><div class="agent-sources"><span class="source-label">引用来源</span><span v-for="(source, index) in result.sources" :key="`${source.documentObjectKey}-${index}`" class="agent-source">[{{ index + 1 }}] {{ source.documentObjectKey }}</span></div></section></section>
  </main>
</template>
