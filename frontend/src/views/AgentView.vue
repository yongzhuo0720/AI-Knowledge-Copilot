<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  askConversationAgent,
  createConversation,
  listConversationMessages,
  listConversations,
  MAX_PAGE_SIZE,
  type KnowledgeAgentAnswer,
  type ConversationMessage,
  type ConversationSession,
} from '@/services/api'
import { looksLikeMarkdown, renderMarkdown } from '@/services/markdown'
import { resultUnit } from '@/services/agentTrace'
import { SPARK_ICON } from '@/services/icons'
import { useWorkspaceContext } from '@/composables/useWorkspaceContext'

const { currentUser, workspaces, workspaceId, knowledgeBases, loadContext, switchWorkspace, onContextChange } = useWorkspaceContext()

const knowledgeBaseId = ref('')
const sessions = ref<ConversationSession[]>([])
const sessionId = ref<number | null>(null)
const messages = ref<ConversationMessage[]>([])
const question = ref('')
const result = ref<KnowledgeAgentAnswer | null>(null)
const loading = ref(false)
const message = ref('')
// 连续切换会话 / 知识库时，先发出的请求可能后返回；用序号丢弃过期响应。
let sessionRequestId = 0
let agentRunRequestId = 0

const activeKnowledgeBase = computed(() => knowledgeBases.value.find((item) => item.id === Number(knowledgeBaseId.value)))

/** 上下文（登录态 / 工作空间 / 知识库）由 useWorkspaceContext 维护；这里只跟着刷新会话链。 */
onContextChange(({ workspaceChanged }) => {
  if (workspaceChanged) {
    knowledgeBaseId.value = ''
    sessionId.value = null
    messages.value = []
    result.value = null
  }
  if (!currentUser.value || !workspaceId.value) return
  if (!knowledgeBases.value.some((item) => String(item.id) === knowledgeBaseId.value)) {
    knowledgeBaseId.value = knowledgeBases.value.length ? String(knowledgeBases.value[0].id) : ''
  }
  void loadSessions().catch((error) => { message.value = error instanceof Error ? error.message : 'Agent 加载失败' })
})

async function loadSessions() {
  if (!currentUser.value || !knowledgeBaseId.value) {
    // 切走时让在途的会话加载和 Agent 执行全部失效。
    sessionRequestId += 1
    agentRunRequestId += 1
    sessions.value = []
    sessionId.value = null
    messages.value = []
    result.value = null
    return
  }
  // Agent 页只是「选一个会话来复看执行轨迹」，不需要在侧栏里翻页，
  // 所以直接要最大的一页（100 条）。但不能假装分页不存在：
  // 一旦超过一页，就把话说明白，而不是让用户以为会话丢了。
  const response = await listConversations(Number(knowledgeBaseId.value), '', 0, MAX_PAGE_SIZE)
  sessions.value = response.data.items
  if (response.data.hasNext) {
    message.value = `会话较多，这里只列出最近 ${MAX_PAGE_SIZE} 个，请到 AI 会话页搜索更早的会话。`
  }
  const selected = sessions.value.find((item) => item.id === sessionId.value) ?? sessions.value[0]
  if (selected) await selectSession(selected)
  else await newSession()
}

async function selectSession(session: ConversationSession) {
  if (!knowledgeBaseId.value) return
  sessionId.value = session.id
  const requestId = ++sessionRequestId
  // 消息同理：page=0 就是最近的一批，取满一页够看执行轨迹了。
  try {
    const response = await listConversationMessages(
      Number(knowledgeBaseId.value), session.id, 0, MAX_PAGE_SIZE,
    )
    if (requestId !== sessionRequestId || sessionId.value !== session.id) return
    messages.value = response.data.items
    const latestAssistant = [...messages.value].reverse().find((item) => item.role === 'ASSISTANT')
    result.value = latestAssistant ? {
      answer: latestAssistant.content,
      sources: latestAssistant.sources,
      steps: latestAssistant.agentSteps ?? [],
    } : null
  } catch (error) {
    if (requestId === sessionRequestId) message.value = error instanceof Error ? error.message : '加载会话失败'
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
    result.value = {
      answer: response.data.assistantMessage.content,
      sources: response.data.assistantMessage.sources,
      steps: response.data.assistantMessage.agentSteps ?? [],
    }
    await refreshSessionList(fromBaseId)
  } catch (error) {
    if (requestId === agentRunRequestId) message.value = error instanceof Error ? error.message : 'Agent 执行失败'
  } finally {
    if (requestId === agentRunRequestId) loading.value = false
  }
}

/** 只刷新会话标题/排序，不重新选中会话，避免执行完一次任务就把列表重置。 */
async function refreshSessionList(forBaseId: number) {
  try {
    const response = await listConversations(forBaseId, '', 0, MAX_PAGE_SIZE)
    if (Number(knowledgeBaseId.value) !== forBaseId) return
    sessions.value = response.data.items
    if (response.data.hasNext) message.value = `会话较多，这里只列出最近 ${MAX_PAGE_SIZE} 个，请到 AI 会话页搜索更早的会话。`
  } catch {
    // 列表刷新失败不影响本次执行结果。
  }
}

/** 输入法选词时的回车不能当作发送。 */
function handleQuestionKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey) return
  if (event.isComposing || event.keyCode === 229) return
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
    <section class="agent-toolbar panel"><label>工作空间<select :value="workspaceId" :disabled="loading" @change="switchWorkspace(($event.target as HTMLSelectElement).value)"><option v-for="workspace in workspaces" :key="workspace.id" :value="String(workspace.id)">{{ workspace.name }}</option></select></label><label>知识库<select v-model="knowledgeBaseId" :disabled="loading || !knowledgeBases.length" @change="loadSessions"><option value="" disabled>{{ knowledgeBases.length ? '选择知识库' : '暂无知识库' }}</option><option v-for="item in knowledgeBases" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><label>会话<select v-model="sessionId" :disabled="loading || !sessions.length" @change="selectSession(sessions.find((item) => item.id === sessionId)!)"><option v-for="item in sessions" :key="item.id" :value="item.id">{{ item.title }}</option></select></label><label class="agent-question">任务 / 问题<input v-model="question" maxlength="1000" placeholder="例如：总结这套资料中的发布流程" @keydown="handleQuestionKeydown" /></label><button class="primary-button" :disabled="loading || !knowledgeBaseId || !question.trim()" type="button" @click="runAgent">{{ loading ? '执行中…' : '运行 Agent' }} <span>→</span></button></section>
    <p v-if="message" class="message agent-message">{{ message }}</p>
    <section class="agent-flow"><article class="agent-flow-step panel"><span>01</span><strong>规划</strong><small>理解问题并决定是否检索</small></article><span class="agent-flow-arrow">→</span><article class="agent-flow-step panel"><span>02</span><strong>工具调用</strong><small>调用 knowledge_search 等工具</small></article><span class="agent-flow-arrow">→</span><article class="agent-flow-step panel"><span>03</span><strong>生成回答</strong><small>基于来源生成回答</small></article></section>
    <section v-if="!result" class="agent-empty panel"><span class="answer-icon" v-html="SPARK_ICON" /><strong>准备执行一个知识任务</strong><span>{{ activeKnowledgeBase ? `当前知识库：${activeKnowledgeBase.name}` : '先选择一个知识库' }}</span></section>
    <section v-else class="agent-result-grid"><section class="agent-steps panel"><div class="agent-section-heading"><div><p class="eyebrow">执行轨迹</p><h2>执行轨迹</h2></div><span class="section-meta">{{ result.steps.length }} 个步骤</span></div><article v-for="(step, index) in result.steps" :key="`${step.tool}-${index}`" class="agent-step"><span class="source-index">{{ index + 1 }}</span><div><strong>{{ step.tool }}</strong><small>{{ step.query }}</small></div><span class="status-pill status-completed">{{ step.resultCount }} {{ resultUnit(step.tool) }}</span></article></section><section class="agent-answer panel"><div class="agent-section-heading"><div><p class="eyebrow">Agent 回答</p><h2>最终回答</h2></div><span class="status-pill status-completed">已完成 · {{ messages.length }} 条消息</span></div><div v-if="looksLikeMarkdown(result.answer)" class="markdown-body" v-html="renderMarkdown(result.answer)" /><p v-else class="agent-answer-text">{{ result.answer }}</p><div class="agent-sources"><span class="source-label">引用来源</span><span v-for="(source, index) in result.sources" :key="`${source.documentObjectKey}-${index}`" class="agent-source">[{{ index + 1 }}] {{ source.documentObjectKey }}</span></div></section></section>
  </main>
</template>
