<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

import {
  createConversation,
  deleteConversation,
  listConversationMessages,
  renameConversation,
  streamConversation,
  type ConversationMessage,
  type ConversationSession,
  type KnowledgeRetrievalChunk,
} from '@/services/api'
import { looksLikeMarkdown, renderMarkdown } from '@/services/markdown'
import { retrieverLabel } from '@/services/labels'
import { SPARK_ICON } from '@/services/icons'
import { isSendHotkey } from '@/services/input'
import { useChatSessions } from '@/composables/useChatSessions'
import { useWorkspaceContext } from '@/composables/useWorkspaceContext'

const { currentUser, workspaces, workspaceId, knowledgeBases, loadContext, switchWorkspace, onContextChange } = useWorkspaceContext()

/**
 * 每页条数。会话列表和消息流取不同的值，是因为两者的「一屏」大小不一样：
 * 会话是侧边栏里的一行行文字，20 条差不多正好铺满；消息是气泡，一屏放不下几条。
 */
const SESSION_PAGE_SIZE = 20
const MESSAGE_PAGE_SIZE = 30

const knowledgeBaseId = ref('')
const sessionSearch = ref('')

// 会话列表的加载 / 翻页 / 选中由 useChatSessions 维护（与 Agent 页共用一份实现），
// 本页只负责会话内的消息流与流式输出。
const {
  sessions,
  sessionId,
  hasMoreSessions,
  loadingMoreSessions,
  selectSession,
  loadSessions,
  loadMoreSessions,
  refreshFirstPage,
  reset: resetSessions,
  invalidate: invalidateSessionLoads,
} = useChatSessions({
  knowledgeBaseId,
  searchQuery: sessionSearch,
  pageSize: SESSION_PAGE_SIZE,
  onSessionSelected: loadSessionMessages,
  onNoSession: () => {
    messageRequestId += 1
    messages.value = []
    sources.value = []
  },
  createSession: newSession,
})

const messages = ref<ConversationMessage[]>([])
const sources = ref<KnowledgeRetrievalChunk[]>([])
const question = ref('')
const streamingText = ref('')
const lastQuestion = ref('')
const streaming = ref(false)
const loading = ref(false)
const message = ref('')
const editingSessionId = ref<number | null>(null)
const editingTitle = ref('')
const sessionActionLoading = ref(false)
// 消息分页。`messagePage` 记的是「当前已经加载到第几页」，不是「下一页」——
// 这样「是否还有下一页」只由 hasMoreMessages 一个来源决定，不会出现两个变量打架。
const messagePage = ref(0)
const hasMoreMessages = ref(false)
const loadingEarlierMessages = ref(false)
const creatingSession = ref(false)
let streamController: AbortController | null = null
// 请求序号：连续切换知识库 / 会话时，先发出的请求可能后返回。
// 每次发起新请求就 +1，响应回来时序号已变则丢弃，避免过期数据覆盖当前界面。
let messageRequestId = 0

const activeKnowledgeBase = () => knowledgeBases.value.find((item) => item.id === Number(knowledgeBaseId.value))

/** 上下文（登录态 / 工作空间 / 知识库）由 useWorkspaceContext 维护；这里跟着重置并刷新会话。 */
onContextChange(({ workspaceChanged }) => {
  if (workspaceChanged) {
    abortStream()
    knowledgeBaseId.value = ''
    resetConversationState()
  }
  if (!currentUser.value) {
    knowledgeBaseId.value = ''
    resetConversationState()
    return
  }
  if (!workspaceId.value) return
  if (!knowledgeBases.value.some((item) => String(item.id) === knowledgeBaseId.value)) {
    knowledgeBaseId.value = knowledgeBases.value.length ? String(knowledgeBases.value[0].id) : ''
  }
  void loadSessions().catch((error) => { message.value = error instanceof Error ? error.message : 'Chat 加载失败' })
})

/** 清空与「当前会话」有关的全部状态，并让所有在途请求失效。切换工作空间时用。 */
function resetConversationState() {
  resetSessions()
  messageRequestId += 1
  messages.value = []
  sources.value = []
  streamingText.value = ''
  messagePage.value = 0
  hasMoreMessages.value = false
}

function abortStream() {
  // 只 abort，不在这里改 streaming：send() 的 finally 负责收尾。
  // 提前改会让紧随其后的新请求与旧请求互相覆盖 streamController。
  streamController?.abort()
}

async function loadMore() {
  try {
    await loadMoreSessions()
  } catch (error) {
    message.value = error instanceof Error ? error.message : '加载更多会话失败'
  }
}

async function selectKnowledgeBase() {
  abortStream()
  message.value = ''
  resetConversationState()
  try {
    await loadSessions()
  } catch (error) {
    message.value = error instanceof Error ? error.message : '加载会话失败'
  }
}

/** 会话被选中（useChatSessions 已记下 sessionId）之后，本页负责加载它的消息流。 */
async function loadSessionMessages(session: ConversationSession) {
  if (!knowledgeBaseId.value) return
  // 切换会话前停掉上一个会话可能还在进行的生成流；
  // send() 的 fromSessionId 守卫负责丢弃 abort 之后仍迟到的缓冲事件。
  abortStream()
  messagePage.value = 0
  messages.value = []
  sources.value = []
  hasMoreMessages.value = false
  const requestId = ++messageRequestId
  try {
    const response = await listConversationMessages(
      Number(knowledgeBaseId.value), session.id, 0, MESSAGE_PAGE_SIZE,
    )
    // 连续点两个会话时先发的请求可能后返回；序号或选中会话对不上就说明这份响应已过期。
    if (requestId !== messageRequestId || sessionId.value !== session.id) return
    // page=0 拿到的就是**最近**的一批消息（后端从最新往回数），
    // 所以打开会话先看到的正是最新对话，不需要先滚到最后。
    messages.value = response.data.items
    hasMoreMessages.value = response.data.hasNext
    const latestUser = [...messages.value].reverse().find((item) => item.role === 'USER')
    lastQuestion.value = latestUser?.content ?? ''
    const latestAssistant = [...messages.value].reverse().find((item) => item.role === 'ASSISTANT')
    sources.value = latestAssistant?.sources ?? []
  } catch (error) {
    if (requestId === messageRequestId) message.value = error instanceof Error ? error.message : '加载消息失败'
  }
}

async function loadEarlierMessages() {
  if (!knowledgeBaseId.value || sessionId.value === null
    || !hasMoreMessages.value || loadingEarlierMessages.value) return
  loadingEarlierMessages.value = true
  const requestId = messageRequestId
  const forSessionId = sessionId.value
  try {
    const nextPage = messagePage.value + 1
    const response = await listConversationMessages(
      Number(knowledgeBaseId.value), sessionId.value, nextPage, MESSAGE_PAGE_SIZE,
    )
    // 加载期间切了会话：这一页是上一个会话的历史，拼进来会串台。
    if (requestId !== messageRequestId || sessionId.value !== forSessionId) return
    const known = new Set(messages.value.map((item) => item.id))
    // 旧的一页要拼到**前面**：page 越大越古老。
    // 拼到后面的话，用户点「加载更早的消息」会看到消息出现在对话框底部，明显是错的。
    messages.value = [...response.data.items.filter((item) => !known.has(item.id)), ...messages.value]
    messagePage.value = nextPage
    hasMoreMessages.value = response.data.hasNext
  } catch (error) {
    message.value = error instanceof Error ? error.message : '加载更早的消息失败'
  } finally {
    loadingEarlierMessages.value = false
  }
}

async function newSession() {
  // 连点「新建」会创建出多个空会话，用一个在途标记挡住第二次。
  if (!knowledgeBaseId.value || creatingSession.value) return
  creatingSession.value = true
  try {
    const response = await createConversation(Number(knowledgeBaseId.value))
    sessions.value = [response.data, ...sessions.value]
    await selectSession(response.data)
  } catch (error) {
    message.value = error instanceof Error ? error.message : '新建会话失败'
  } finally {
    creatingSession.value = false
  }
}

function startRename(session: ConversationSession) {
  editingSessionId.value = session.id
  editingTitle.value = session.title
}

function cancelRename() {
  editingSessionId.value = null
  editingTitle.value = ''
}

async function saveRename(session: ConversationSession) {
  if (!knowledgeBaseId.value || !editingTitle.value.trim()) return
  sessionActionLoading.value = true
  try {
    const response = await renameConversation(
      Number(knowledgeBaseId.value),
      session.id,
      editingTitle.value.trim(),
    )
    sessions.value = sessions.value.map((item) => item.id === session.id ? response.data : item)
    cancelRename()
  } catch (error) {
    message.value = error instanceof Error ? error.message : '重命名会话失败'
  } finally {
    sessionActionLoading.value = false
  }
}

async function removeSession(session: ConversationSession) {
  if (!knowledgeBaseId.value || !window.confirm(`确定删除“${session.title}”吗？`)) return
  sessionActionLoading.value = true
  try {
    await deleteConversation(Number(knowledgeBaseId.value), session.id)
    sessions.value = sessions.value.filter((item) => item.id !== session.id)
    if (session.id === sessionId.value) {
      const nextSession = sessions.value[0]
      if (nextSession) await selectSession(nextSession)
      else await newSession()
    }
    message.value = '会话已删除。'
  } catch (error) {
    message.value = error instanceof Error ? error.message : '删除会话失败'
  } finally {
    sessionActionLoading.value = false
  }
}

async function send() {
  if (!knowledgeBaseId.value || !sessionId.value || !question.value.trim() || streaming.value) return
  const currentQuestion = question.value.trim()
  // 记住这次流是从哪个会话 / 知识库发出的：中途切走之后，迟到的 delta/sources/complete
  // 都属于上一个上下文，必须丢弃，否则别的会话的回答会混进当前消息流。
  const fromSessionId = sessionId.value
  const fromKnowledgeBaseId = Number(knowledgeBaseId.value)
  const controller = new AbortController()
  streamController = controller
  lastQuestion.value = currentQuestion
  question.value = ''
  message.value = ''
  sources.value = []
  streamingText.value = ''
  streaming.value = true
  try {
    await streamConversation(fromKnowledgeBaseId, fromSessionId, currentQuestion, controller.signal, (event) => {
      if (streamController !== controller || sessionId.value !== fromSessionId) return
      if (event.event === 'user') messages.value = [...messages.value, event.data]
      if (event.event === 'delta') streamingText.value += event.data.content
      if (event.event === 'sources') sources.value = event.data.sources
      if (event.event === 'complete') {
        messages.value = [...messages.value, event.data.assistantMessage]
        sources.value = event.data.assistantMessage.sources
      }
      if (event.event === 'error') message.value = event.data.message
    })
    await refreshFirstPage()
  } catch (error) {
    // 已经被更新的请求接管（例如停止后立刻重新提问）：旧流的事件和收尾都不该再碰状态。
    if (streamController !== controller) return
    if ((error as DOMException)?.name === 'AbortError') {
      // 只有「还停留在原会话」时才需要重拉消息；切走触发的 abort 由 selectSession 自己负责。
      if (sessionId.value === fromSessionId && Number(knowledgeBaseId.value) === fromKnowledgeBaseId) {
        const active = sessions.value.find((session) => session.id === fromSessionId)
        if (active) await selectSession(active)
      }
    } else message.value = error instanceof Error ? error.message : '流式问答失败'
  } finally {
    if (streamController === controller) {
      streaming.value = false
      streamController = null
      streamingText.value = ''
    }
  }
}

async function regenerate() {
  if (!lastQuestion.value || streaming.value) return
  question.value = lastQuestion.value
  await send()
}

function stop() {
  streamController?.abort()
  message.value = '已停止本次生成。'
}

async function copyAnswer(content: string) {
  try {
    if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(content)
    message.value = '回答已复制。'
  } catch {
    // 非安全上下文（直接走 http 的局域网地址）里 navigator.clipboard 不可用，
    // 退回到临时 textarea + execCommand —— 这是仍然被广泛支持的路径。
    const textarea = document.createElement('textarea')
    textarea.value = content
    textarea.setAttribute('readonly', '')
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    const copied = document.execCommand('copy')
    document.body.removeChild(textarea)
    message.value = copied ? '回答已复制。' : '复制失败，请手动选中文本复制。'
  }
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (!isSendHotkey(event)) return
  event.preventDefault()
  void send()
}

onMounted(async () => {
  loading.value = true
  try { await loadContext() }
  catch (error) { message.value = error instanceof Error ? error.message : 'Chat 加载失败' }
  finally { loading.value = false }
})

onBeforeUnmount(() => {
  abortStream()
  invalidateSessionLoads()
  messageRequestId += 1
})
</script>

<template>
  <main class="chat-shell">
    <header class="chat-header"><div><p class="eyebrow">AI 会话</p><h1>和你的知识库对话</h1><p>回答实时生成，并保留每个来源片段。</p></div><div class="chat-context"><label>工作空间<select :value="workspaceId" @change="switchWorkspace(($event.target as HTMLSelectElement).value)"><option v-for="workspace in workspaces" :key="workspace.id" :value="String(workspace.id)">{{ workspace.name }}</option></select></label><label>知识库<select v-model="knowledgeBaseId" :disabled="!knowledgeBases.length" @change="selectKnowledgeBase"><option v-for="item in knowledgeBases" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label></div></header>
    <section v-if="!activeKnowledgeBase()" class="chat-empty panel"><strong>先选择一个知识库</strong><span>创建知识库并上传文档后，AI 会话才能基于企业资料回答。</span></section>
    <section v-else class="chat-layout">
      <aside class="chat-sessions panel"><div class="chat-panel-heading"><div><p class="eyebrow">会话列表</p><h2>会话</h2></div><button class="secondary-button" :disabled="streaming || sessionActionLoading || creatingSession" type="button" @click="newSession">新建</button></div><form class="chat-session-search" @submit.prevent="loadSessions(false)"><input v-model="sessionSearch" maxlength="100" placeholder="搜索标题或消息" aria-label="搜索会话" /><button class="text-button" type="submit">搜索</button></form><div v-for="session in sessions" :key="session.id" class="chat-session-row"><button v-if="editingSessionId !== session.id" class="chat-session-item" :class="{ active: session.id === sessionId }" type="button" @click="selectSession(session)"><strong>{{ session.title }}</strong><small>{{ new Date(session.updatedAt).toLocaleDateString('zh-CN') }}</small></button><form v-else class="chat-session-edit" @submit.prevent="saveRename(session)"><input v-model="editingTitle" maxlength="200" aria-label="会话名称" /><button class="text-button" :disabled="sessionActionLoading" type="submit">保存</button><button class="text-button" :disabled="sessionActionLoading" type="button" @click="cancelRename">取消</button></form><div v-if="editingSessionId !== session.id" class="chat-session-actions"><button class="text-button" :disabled="streaming || sessionActionLoading" type="button" @click="startRename(session)">重命名</button><button class="text-button danger" :disabled="streaming || sessionActionLoading" type="button" @click="removeSession(session)">删除</button></div></div><button v-if="hasMoreSessions" class="text-button chat-load-more" :disabled="loadingMoreSessions" type="button" @click="loadMore">{{ loadingMoreSessions ? '加载中…' : '加载更多会话' }}</button></aside>
      <section class="chat-main panel"><div class="chat-main-heading"><div><span class="status-dot online" />{{ activeKnowledgeBase()?.name }}</div><span v-if="streaming" class="streaming-label">正在生成…</span></div><div class="chat-messages"><button v-if="hasMoreMessages" class="text-button chat-load-more" :disabled="loadingEarlierMessages" type="button" @click="loadEarlierMessages">{{ loadingEarlierMessages ? '加载中…' : '↑ 加载更早的消息' }}</button><div v-if="!messages.length && !streamingText" class="chat-welcome"><span class="answer-icon" v-html="SPARK_ICON" /><strong>从一个问题开始</strong><span>试试询问文档中的流程、规则或关键结论。</span></div><article v-for="item in messages" :key="item.id" class="chat-bubble" :class="item.role.toLowerCase()"><small>{{ item.role === 'USER' ? '你' : 'AI Copilot' }}</small><p v-if="item.role === 'USER'">{{ item.content }}</p><template v-else><div v-if="looksLikeMarkdown(item.content)" class="markdown-body" v-html="renderMarkdown(item.content)" /><p v-else>{{ item.content }}</p></template><div v-if="item.role === 'ASSISTANT'" class="bubble-actions"><button class="text-button" type="button" @click="copyAnswer(item.content)">复制</button><button v-if="item.id === messages[messages.length - 1]?.id" class="text-button" type="button" @click="regenerate">重新生成</button></div></article><article v-if="streamingText" class="chat-bubble assistant streaming-bubble"><small>AI Copilot · 实时生成</small><div v-if="looksLikeMarkdown(streamingText)" class="markdown-body" v-html="renderMarkdown(streamingText)" /><p v-else>{{ streamingText }}</p><span class="typing-cursor" /></article></div><p v-if="message" class="message chat-message">{{ message }}</p><form class="chat-composer" @submit.prevent="send"><textarea v-model="question" :disabled="streaming" rows="2" maxlength="1000" placeholder="询问你的知识库…" @keydown="handleComposerKeydown" /><button v-if="streaming" class="secondary-button" type="button" @click="stop">停止生成</button><button v-else class="primary-button" :disabled="loading || !question.trim()" type="submit">发送 <span>→</span></button></form></section>
      <aside class="chat-sources panel"><div class="chat-panel-heading"><div><p class="eyebrow">引用来源</p><h2>引用来源</h2></div><span v-if="sources.length" class="section-meta">{{ sources.length }} 个片段</span></div><div v-if="sources.length" class="source-list"><article v-for="(source, index) in sources" :key="`${source.documentObjectKey}-${index}`" class="source-card"><span class="source-index">{{ index + 1 }}</span><div><strong>{{ source.documentObjectKey.split('/').pop() }}</strong><small>相关度 {{ source.score.toFixed(2) }} · <span class="retriever-badge" :class="`retriever-${source.retriever ?? 'vector'}`">{{ retrieverLabel(source.retriever) }}</span></small><p>{{ source.content }}</p></div></article></div><div v-else class="source-empty">发送问题后，检索到的文档片段会显示在这里。</div></aside>
    </section>
  </main>
</template>
