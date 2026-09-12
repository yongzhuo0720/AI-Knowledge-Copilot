<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  listKnowledgeBases,
  listWorkspaces,
  runKnowledgeAgent,
  type KnowledgeAgentAnswer,
  type KnowledgeBase,
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
const question = ref('')
const result = ref<KnowledgeAgentAnswer | null>(null)
const loading = ref(false)
const message = ref('')

const activeKnowledgeBase = computed(() => knowledgeBases.value.find((item) => item.id === Number(knowledgeBaseId.value)))

async function loadContext() {
  currentUser.value = readStoredUser()
  if (!currentUser.value) return
  loading.value = true
  try {
    const response = await listWorkspaces(currentUser.value.id)
    workspaces.value = response.data
    if (!workspaceId.value || !workspaces.value.some((item) => String(item.id) === workspaceId.value)) workspaceId.value = workspaces.value.length ? String(workspaces.value[0].id) : ''
    if (workspaceId.value) await loadKnowledgeBases()
  } catch (error) { message.value = error instanceof Error ? error.message : 'Agent 加载失败' }
  finally { loading.value = false }
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
  result.value = null
  await loadKnowledgeBases()
}

async function runAgent() {
  if (!currentUser.value || !knowledgeBaseId.value || !question.value.trim() || loading.value) return
  loading.value = true
  message.value = ''
  try {
    result.value = (await runKnowledgeAgent(currentUser.value.id, Number(knowledgeBaseId.value), question.value.trim())).data
  } catch (error) { message.value = error instanceof Error ? error.message : 'Agent 执行失败' }
  finally { loading.value = false }
}

onMounted(() => { void loadContext() })
</script>

<template>
  <main class="agent-shell">
    <header class="agent-header"><div><p class="eyebrow">KNOWLEDGE AGENT</p><h1>让 Agent 先检索，再回答。</h1><p class="agent-summary">这是一个真实可解释的 Tool Calling 闭环：模型决定调用工具，工具只访问当前知识库，最后返回带引用的答案。</p></div><span class="agent-badge"><span class="status-dot online" />受权限保护</span></header>
    <section class="agent-toolbar panel"><label>Workspace<select v-model="workspaceId" :disabled="loading" @change="changeWorkspace"><option v-for="workspace in workspaces" :key="workspace.id" :value="String(workspace.id)">{{ workspace.name }}</option></select></label><label>Knowledge Base<select v-model="knowledgeBaseId" :disabled="loading || !knowledgeBases.length"><option value="" disabled>{{ knowledgeBases.length ? '选择知识库' : '暂无知识库' }}</option><option v-for="item in knowledgeBases" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label><label class="agent-question">Task / Question<input v-model="question" maxlength="1000" placeholder="例如：总结这套资料中的发布流程" @keyup.enter="runAgent" /></label><button class="primary-button" :disabled="loading || !knowledgeBaseId || !question.trim()" type="button" @click="runAgent">{{ loading ? '执行中…' : '运行 Agent' }} <span>→</span></button></section>
    <p v-if="message" class="message agent-message">{{ message }}</p>
    <section class="agent-flow"><article class="agent-flow-step panel"><span>01</span><strong>Plan</strong><small>理解问题并决定是否检索</small></article><span class="agent-flow-arrow">→</span><article class="agent-flow-step panel"><span>02</span><strong>Tool Call</strong><small>调用 knowledge_search</small></article><span class="agent-flow-arrow">→</span><article class="agent-flow-step panel"><span>03</span><strong>Answer</strong><small>基于来源生成回答</small></article></section>
    <section v-if="!result" class="agent-empty panel"><span class="answer-icon">✧</span><strong>准备执行一个知识任务</strong><span>{{ activeKnowledgeBase ? `当前知识库：${activeKnowledgeBase.name}` : '先选择一个知识库' }}</span></section>
    <section v-else class="agent-result-grid"><section class="agent-steps panel"><div class="agent-section-heading"><div><p class="eyebrow">EXECUTION TRACE</p><h2>执行轨迹</h2></div><span class="section-meta">{{ result.steps.length }} 个步骤</span></div><article v-for="(step, index) in result.steps" :key="`${step.tool}-${index}`" class="agent-step"><span class="source-index">{{ index + 1 }}</span><div><strong>{{ step.tool }}</strong><small>query · {{ step.query }}</small></div><span class="status-pill status-completed">{{ step.resultCount }} chunks</span></article></section><section class="agent-answer panel"><div class="agent-section-heading"><div><p class="eyebrow">AGENT ANSWER</p><h2>最终回答</h2></div><span class="status-pill status-completed">已完成</span></div><p class="agent-answer-text">{{ result.answer }}</p><div class="agent-sources"><span class="source-label">引用来源</span><span v-for="(source, index) in result.sources" :key="`${source.documentObjectKey}-${index}`" class="agent-source">[{{ index + 1 }}] {{ source.documentObjectKey }}</span></div></section></section>
  </main>
</template>
