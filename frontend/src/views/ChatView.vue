<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

import {
  createConversation,
  deleteConversation,
  listConversationMessages,
  listConversations,
  listKnowledgeBases,
  listWorkspaces,
  renameConversation,
  streamConversation,
  type ConversationMessage,
  type ConversationSession,
  type KnowledgeBase,
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
const sessions = ref<ConversationSession[]>([])
const messages = ref<ConversationMessage[]>([])
const sources = ref<KnowledgeRetrievalChunk[]>([])
const workspaceId = ref(localStorage.getItem('activeWorkspaceId') ?? '')
const knowledgeBaseId = ref('')
const sessionId = ref<number | null>(null)
const sessionSearch = ref('')
const question = ref('')
const streamingText = ref('')
const lastQuestion = ref('')
const streaming = ref(false)
const loading = ref(false)
const message = ref('')
const editingSessionId = ref<number | null>(null)
const editingTitle = ref('')
const sessionActionLoading = ref(false)
let streamController: AbortController | null = null

const activeKnowledgeBase = () => knowledgeBases.value.find((item) => item.id === Number(knowledgeBaseId.value))

async function loadContext() {
  currentUser.value = readStoredUser()
  workspaceId.value = localStorage.getItem('activeWorkspaceId') ?? ''
  if (!currentUser.value) return
  loading.value = true
  try {
    const workspaceResponse = await listWorkspaces(currentUser.value.id)
    workspaces.value = workspaceResponse.data
    if (!workspaceId.value || !workspaces.value.some((item) => String(item.id) === workspaceId.value)) workspaceId.value = workspaces.value.length ? String(workspaces.value[0].id) : ''
    if (workspaceId.value) await loadKnowledgeBases()
  } catch (error) { message.value = error instanceof Error ? error.message : 'Chat 加载失败' }
  finally { loading.value = false }
}

async function loadKnowledgeBases() {
  if (!currentUser.value || !workspaceId.value) return
  const response = await listKnowledgeBases(currentUser.value.id, Number(workspaceId.value))
  knowledgeBases.value = response.data
  if (!knowledgeBases.value.some((item) => String(item.id) === knowledgeBaseId.value)) knowledgeBaseId.value = knowledgeBases.value.length ? String(knowledgeBases.value[0].id) : ''
  await loadSessions()
}

async function loadSessions(createIfEmpty = true) {
  if (!currentUser.value || !knowledgeBaseId.value) { sessions.value = []; sessionId.value = null; messages.value = []; return }
  const response = await listConversations(currentUser.value.id, Number(knowledgeBaseId.value), sessionSearch.value)
  sessions.value = response.data
  const selected = sessions.value.find((item) => item.id === sessionId.value) ?? sessions.value[0]
  if (selected) await selectSession(selected)
  else if (createIfEmpty) await newSession()
  else { sessionId.value = null; messages.value = []; sources.value = [] }
}

async function searchSessions() {
  if (!knowledgeBaseId.value) return
  await loadSessions(false)
}

async function changeWorkspace() {
  if (!workspaceId.value) return
  localStorage.setItem('activeWorkspaceId', workspaceId.value)
  knowledgeBaseId.value = ''
  await loadKnowledgeBases()
}

async function selectKnowledgeBase() {
  await loadSessions()
}

async function selectSession(session: ConversationSession) {
  if (!currentUser.value || !knowledgeBaseId.value) return
  sessionId.value = session.id
  const response = await listConversationMessages(currentUser.value.id, Number(knowledgeBaseId.value), session.id)
  messages.value = response.data
  const latestUser = [...messages.value].reverse().find((item) => item.role === 'USER')
  if (latestUser) lastQuestion.value = latestUser.content
  const latestAssistant = [...messages.value].reverse().find((item) => item.role === 'ASSISTANT')
  sources.value = latestAssistant?.sources ?? []
}

async function newSession() {
  if (!currentUser.value || !knowledgeBaseId.value) return
  const response = await createConversation(currentUser.value.id, Number(knowledgeBaseId.value))
  sessions.value = [response.data, ...sessions.value]
  await selectSession(response.data)
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
  if (!currentUser.value || !knowledgeBaseId.value || !editingTitle.value.trim()) return
  sessionActionLoading.value = true
  try {
    const response = await renameConversation(
      currentUser.value.id,
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
  if (!currentUser.value || !knowledgeBaseId.value || !window.confirm(`确定删除“${session.title}”吗？`)) return
  sessionActionLoading.value = true
  try {
    await deleteConversation(currentUser.value.id, Number(knowledgeBaseId.value), session.id)
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
  if (!currentUser.value || !knowledgeBaseId.value || !sessionId.value || !question.value.trim() || streaming.value) return
  const currentQuestion = question.value.trim()
  lastQuestion.value = currentQuestion
  question.value = ''
  message.value = ''
  sources.value = []
  streamingText.value = ''
  streaming.value = true
  streamController = new AbortController()
  try {
    await streamConversation(currentUser.value.id, Number(knowledgeBaseId.value), sessionId.value, currentQuestion, streamController.signal, (event) => {
      if (event.event === 'user') messages.value = [...messages.value, event.data]
      if (event.event === 'delta') streamingText.value += event.data.content
      if (event.event === 'sources') sources.value = event.data.sources
      if (event.event === 'complete') {
        messages.value = [...messages.value, event.data.assistantMessage]
        sources.value = event.data.assistantMessage.sources
      }
      if (event.event === 'error') message.value = event.data.message
    })
    await loadSessions()
  } catch (error) {
    if ((error as DOMException)?.name === 'AbortError') {
      if (sessionId.value) await selectSession(sessions.value.find((session) => session.id === sessionId.value) as ConversationSession)
    } else message.value = error instanceof Error ? error.message : '流式问答失败'
  } finally {
    streaming.value = false
    streamController = null
    streamingText.value = ''
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

function copyAnswer(content: string) {
  void navigator.clipboard?.writeText(content)
  message.value = '回答已复制。'
}

onMounted(() => {
  window.addEventListener('aicopilot-auth-changed', loadContext)
  window.addEventListener('aicopilot-workspace-changed', loadContext)
  void loadContext()
})

onBeforeUnmount(() => {
  streamController?.abort()
  window.removeEventListener('aicopilot-auth-changed', loadContext)
  window.removeEventListener('aicopilot-workspace-changed', loadContext)
})
</script>

<template>
  <main class="chat-shell">
    <header class="chat-header"><div><p class="eyebrow">AI CHAT</p><h1>和你的知识库对话。</h1><p>回答实时生成，并保留每个来源片段。</p></div><div class="chat-context"><label>Workspace<select v-model="workspaceId" @change="changeWorkspace"><option v-for="workspace in workspaces" :key="workspace.id" :value="String(workspace.id)">{{ workspace.name }}</option></select></label><label>Knowledge Base<select v-model="knowledgeBaseId" :disabled="!knowledgeBases.length" @change="selectKnowledgeBase"><option v-for="item in knowledgeBases" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label></div></header>
    <section v-if="!activeKnowledgeBase()" class="chat-empty panel"><strong>先选择一个知识库</strong><span>创建知识库并上传文档后，AI Chat 才能基于企业资料回答。</span></section>
    <section v-else class="chat-layout">
      <aside class="chat-sessions panel"><div class="chat-panel-heading"><div><p class="eyebrow">CONVERSATIONS</p><h2>会话</h2></div><button class="secondary-button" :disabled="streaming || sessionActionLoading" type="button" @click="newSession">新建</button></div><form class="chat-session-search" @submit.prevent="searchSessions"><input v-model="sessionSearch" maxlength="100" placeholder="搜索标题或消息" aria-label="搜索会话" /><button class="text-button" type="submit">搜索</button></form><div v-for="session in sessions" :key="session.id" class="chat-session-row"><button v-if="editingSessionId !== session.id" class="chat-session-item" :class="{ active: session.id === sessionId }" type="button" @click="selectSession(session)"><strong>{{ session.title }}</strong><small>{{ new Date(session.updatedAt).toLocaleDateString('zh-CN') }}</small></button><form v-else class="chat-session-edit" @submit.prevent="saveRename(session)"><input v-model="editingTitle" maxlength="200" aria-label="会话名称" /><button class="text-button" :disabled="sessionActionLoading" type="submit">保存</button><button class="text-button" :disabled="sessionActionLoading" type="button" @click="cancelRename">取消</button></form><div v-if="editingSessionId !== session.id" class="chat-session-actions"><button class="text-button" :disabled="streaming || sessionActionLoading" type="button" @click="startRename(session)">重命名</button><button class="text-button danger" :disabled="streaming || sessionActionLoading" type="button" @click="removeSession(session)">删除</button></div></div></aside>
      <section class="chat-main panel"><div class="chat-main-heading"><div><span class="status-dot online" />{{ activeKnowledgeBase()?.name }}</div><span v-if="streaming" class="streaming-label">正在生成…</span></div><div class="chat-messages"><div v-if="!messages.length && !streamingText" class="chat-welcome"><span class="answer-icon">✦</span><strong>从一个问题开始</strong><span>试试询问文档中的流程、规则或关键结论。</span></div><article v-for="item in messages" :key="item.id" class="chat-bubble" :class="item.role.toLowerCase()"><small>{{ item.role === 'USER' ? '你' : 'AI Copilot' }}</small><p>{{ item.content }}</p><div v-if="item.role === 'ASSISTANT'" class="bubble-actions"><button class="text-button" type="button" @click="copyAnswer(item.content)">复制</button><button v-if="item.id === messages[messages.length - 1]?.id" class="text-button" type="button" @click="regenerate">重新生成</button></div></article><article v-if="streamingText" class="chat-bubble assistant streaming-bubble"><small>AI Copilot · 实时生成</small><p>{{ streamingText }}<span class="typing-cursor" /></p></article></div><p v-if="message" class="message chat-message">{{ message }}</p><form class="chat-composer" @submit.prevent="send"><textarea v-model="question" :disabled="streaming" rows="2" maxlength="1000" placeholder="询问你的知识库…" @keydown.enter.exact.prevent="send" /><button v-if="streaming" class="secondary-button" type="button" @click="stop">停止生成</button><button v-else class="primary-button" :disabled="loading || !question.trim()" type="submit">发送 <span>→</span></button></form></section>
      <aside class="chat-sources panel"><div class="chat-panel-heading"><div><p class="eyebrow">SOURCES</p><h2>引用来源</h2></div><span v-if="sources.length" class="section-meta">{{ sources.length }} 个片段</span></div><div v-if="sources.length" class="source-list"><article v-for="(source, index) in sources" :key="`${source.documentObjectKey}-${index}`" class="source-card"><span class="source-index">{{ index + 1 }}</span><div><strong>{{ source.documentObjectKey.split('/').pop() }}</strong><small>相关度 {{ source.score.toFixed(2) }}</small><p>{{ source.content }}</p></div></article></div><div v-else class="source-empty">发送问题后，检索到的文档片段会显示在这里。</div></aside>
    </section>
  </main>
</template>
