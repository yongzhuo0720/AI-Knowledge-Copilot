<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AUTH_CHANGED_EVENT } from '@/services/crossTabSync'

import {
  createKnowledgeBase,
  createWorkspace as createWorkspaceApi,
  login,
  logout,
  registerUser,
  type KnowledgeBase,
} from '@/services/api'
import { clearAuthState } from '@/services/auth'
import { SPARK_ICON } from '@/services/icons'
import { useWorkspaceContext } from '@/composables/useWorkspaceContext'

type AuthMode = 'user' | 'admin' | 'register'

const router = useRouter()
const { currentUser, workspaces, workspaceId, knowledgeBases, loadContext, switchWorkspace, onContextChange } = useWorkspaceContext()

const loggedIn = ref(Boolean(localStorage.getItem('accessToken') && currentUser.value))
const authMode = ref<AuthMode>('user')
const email = ref('')
const password = ref('')
const username = ref('')
const knowledgeBaseId = ref('')
const workspaceName = ref('我的工作空间')
const name = ref('')
const description = ref('')
const knowledgeBase = ref<KnowledgeBase | null>(null)
const message = ref('')
const loading = ref(false)

/**
 * 登录态 / 工作空间 / 知识库列表由 useWorkspaceContext 统一维护。
 * 这里跟着同步「是否已登录」和「当前选中的知识库」。
 */
onContextChange(({ workspaceChanged }) => {
  loggedIn.value = Boolean(localStorage.getItem('accessToken') && currentUser.value)
  if (!loggedIn.value) {
    knowledgeBaseId.value = ''
    knowledgeBase.value = null
    return
  }
  if (workspaceChanged) {
    knowledgeBaseId.value = ''
    knowledgeBase.value = null
  }
  if (!knowledgeBases.value.some((item) => item.id === knowledgeBase.value?.id)) {
    knowledgeBaseId.value = knowledgeBases.value.length ? String(knowledgeBases.value[0].id) : ''
    knowledgeBase.value = knowledgeBases.value[0] ?? null
  }
})

function showError(error: unknown, fallback: string) {
  message.value = error instanceof Error ? error.message : fallback
}

async function submitAuth() {
  loading.value = true
  message.value = ''
  try {
    if (authMode.value === 'register') {
      await registerUser(username.value, email.value, password.value)
      authMode.value = 'user'
      password.value = ''
      message.value = '注册成功，请使用新账号登录。'
      return
    }
    const isAdminLogin = authMode.value === 'admin'
    const response = await login(email.value, password.value, isAdminLogin)
    currentUser.value = response.data.user
    localStorage.setItem('accessToken', response.data.accessToken)
    localStorage.setItem('currentUser', JSON.stringify(response.data.user))
    loggedIn.value = true
    message.value = `欢迎回来，${response.data.user.username}`
    window.dispatchEvent(new Event(AUTH_CHANGED_EVENT))
    if (isAdminLogin) void router.push('/admin')
  } catch (error) {
    // 后端返回的是英文，管理员登录失败时给一句明确的中文提示
    if (authMode.value === 'admin' && error instanceof Error && error.message.includes('administrator')) {
      message.value = '该账号不是管理员，无法进入管理后台。'
    } else {
      showError(error, authMode.value === 'register' ? '注册失败' : '登录失败')
    }
  } finally { loading.value = false }
}

function switchAuthMode(mode: AuthMode) { authMode.value = mode; message.value = '' }

async function signOut() {
  // 先通知后端作废 token，再清本地状态（顺序不能反：请求头依赖 localStorage 里的 token）
  await logout()
  clearAuthState()
  loggedIn.value = false
  knowledgeBaseId.value = ''
  knowledgeBase.value = null
}

function handleWorkspaceSelect(id: string) {
  void switchWorkspace(id)
}

async function selectKnowledgeBase(item: KnowledgeBase) {
  knowledgeBase.value = item
  knowledgeBaseId.value = String(item.id)
}

async function handleKnowledgeBaseChange() {
  const selected = knowledgeBases.value.find((item) => item.id === Number(knowledgeBaseId.value))
  if (selected) await selectKnowledgeBase(selected)
}

async function createWorkspace() {
  const userId = currentUser.value?.id
  if (!userId || !workspaceName.value.trim()) return
  loading.value = true; message.value = ''
  try {
    const response = await createWorkspaceApi(userId, workspaceName.value.trim())
    // 切到新空间：switchWorkspace 会落盘并经 loadContext 刷新共享的工作空间列表（侧栏跟着更新）
    await switchWorkspace(String(response.data.id))
    message.value = `工作空间已创建：${response.data.name}（ID ${response.data.id}）`
  } catch (error) { showError(error, '创建工作空间失败')
  } finally { loading.value = false }
}

async function create() {
  const userId = currentUser.value?.id
  if (!workspaceId.value || !userId || !name.value.trim()) return
  loading.value = true; message.value = ''
  try {
    const response = await createKnowledgeBase(Number(workspaceId.value), name.value, description.value)
    knowledgeBase.value = response.data; knowledgeBaseId.value = String(response.data.id)
    knowledgeBases.value = [...knowledgeBases.value, response.data]
    message.value = '知识库创建成功，现在可以上传文档。'
  } catch (error) { showError(error, '创建知识库失败')
  } finally { loading.value = false }
}

onMounted(async () => {
  if (!loggedIn.value) return
  loading.value = true
  try { await loadContext() }
  catch (error) { showError(error, '恢复工作空间失败') }
  finally { loading.value = false }
})
</script>

<style scoped>
.setup-column { align-content: start; }
.content-utility { display: flex; justify-content: flex-end; margin: 14px 0 0; }
.knowledge-next-steps { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; margin-top: 16px; }
.next-step-card { display: grid; gap: 4px; padding: 14px 16px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface, #fff); text-align: left; cursor: pointer; }
.next-step-card:hover { border-color: var(--accent, #4f6ef2); }
.next-step-card strong { font-size: 14px; }
.next-step-card span { color: var(--muted); font-size: 12px; }
</style>

<template>
  <main class="workspace-shell">
    <header class="workspace-header"><div><p class="eyebrow">知识工作台</p><h1>把团队知识，整理成可协作的资产</h1><p class="summary">上传资料，交给 AI 解析；用自然语言检索，并保留每次回答的来源。</p></div></header>

    <section v-if="!loggedIn" class="auth-layout">
      <div class="auth-intro"><p class="eyebrow">从文档到答案</p><h2>让知识真正成为团队的<em>共同记忆</em></h2><p>集中管理散落的资料，让每个答案都附带可追溯的引用来源。</p></div>
      <section class="panel auth-panel">
        <div class="auth-heading">
          <div><p class="eyebrow">{{ authMode === 'admin' ? '管理员' : '欢迎' }}</p><h2>{{ authMode === 'user' ? '登录工作台' : authMode === 'admin' ? '管理员登录' : '创建你的账号' }}</h2></div>
        </div>
        <div class="auth-tabs">
          <button :class="{ active: authMode === 'user' }" type="button" @click="switchAuthMode('user')">普通登录</button>
          <button :class="{ active: authMode === 'admin' }" type="button" @click="switchAuthMode('admin')">管理员登录</button>
          <button :class="{ active: authMode === 'register' }" type="button" @click="switchAuthMode('register')">注册</button>
        </div>
        <form @submit.prevent="submitAuth">
          <label v-if="authMode === 'register'">用户名<input v-model="username" required maxlength="64" placeholder="例如：研发小组" /></label>
          <label>邮箱<input v-model="email" type="email" required placeholder="name@company.com" autocomplete="email" /></label>
          <label>密码<input v-model="password" type="password" required minlength="8" autocomplete="current-password" placeholder="至少 8 位字符" /></label>
          <button class="primary-button wide-button" :disabled="loading" type="submit">{{ loading ? '处理中…' : authMode === 'user' ? '登录工作台' : authMode === 'admin' ? '进入管理后台' : '创建账号' }}</button>
        </form>
        <p class="form-hint">{{ authMode === 'admin' ? '仅限系统管理员，用于管理账号、工作空间与知识库。' : '业务数据使用 Bearer Token 保护，登录后即可开始创建工作空间。' }}</p>
        <p v-if="message" class="message" :class="{ 'message-error': message.includes('失败') || message.includes('错误') }">{{ message }}<button class="message-clear" type="button" title="关闭提示" @click="message = ''">×</button></p>
      </section>
    </section>

    <section v-else class="workspace-grid">
      <aside class="setup-column"><details class="panel setup-panel"><summary class="setup-summary"><span><p class="eyebrow">01 · 准备</p><h2>准备工作空间</h2></span><span class="setup-state">{{ knowledgeBase ? '已就绪' : '待完成' }}</span></summary><div class="setup-body"><p class="section-copy">选择已有空间，或创建一个新的工作空间。</p><label>当前工作空间<select :value="workspaceId" :disabled="loading || !workspaces.length" @change="handleWorkspaceSelect(($event.target as HTMLSelectElement).value)"><option value="" disabled>{{ workspaces.length ? '请选择工作空间' : '暂时没有工作空间' }}</option><option v-for="workspace in workspaces" :key="workspace.id" :value="String(workspace.id)">{{ workspace.name }}</option></select></label></div></details>
      <details class="panel setup-panel"><summary class="setup-summary"><span><p class="eyebrow">02 · 创建</p><h2>创建知识库</h2></span><span class="setup-state">新建</span></summary><div class="setup-body"><p class="section-copy">为资料集合设置一个清晰的名字和描述。</p><form @submit.prevent="create"><label>知识库名称<input v-model="name" maxlength="128" required placeholder="例如：研发规范" /></label><label>描述<textarea v-model="description" maxlength="500" placeholder="可选：这套资料主要解决什么问题？" /></label><button class="primary-button wide-button" :disabled="loading || !workspaceId || !name.trim()" type="submit">{{ loading ? '处理中…' : '创建知识库' }}</button></form></div></details>
        <details class="panel setup-panel"><summary class="setup-summary"><span><p class="eyebrow">03 · 新空间</p><h2>新建工作空间</h2></span><span class="setup-state">可选</span></summary><div class="setup-body"><p class="section-copy">工作空间是知识库、文档和会话的协作边界。</p><div class="inline-create"><label>新空间名称<input v-model="workspaceName" maxlength="128" placeholder="例如：产品研发部" /></label><button class="secondary-button" :disabled="loading || !workspaceName.trim()" type="button" @click="createWorkspace">一键创建</button></div></div></details>
      <p v-if="message" class="message side-message" :class="{ 'message-error': message.includes('失败') || message.includes('错误') }">{{ message }}<button class="message-clear" type="button" title="关闭提示" @click="message = ''">×</button></p></aside>

      <section class="panel content-panel"><div class="section-heading content-heading"><div><p class="eyebrow">知识库</p><h2>{{ knowledgeBase ? knowledgeBase.name : '当前知识库' }}</h2><span v-if="knowledgeBase" class="content-subtitle">{{ knowledgeBase.description || '团队知识资产与 AI 问答空间' }}</span></div><div class="knowledge-selector"><span v-if="knowledgeBase" class="status-pill active">已连接</span><select v-model="knowledgeBaseId" :disabled="loading || !knowledgeBases.length" @change="handleKnowledgeBaseChange"><option value="" disabled>{{ knowledgeBases.length ? '选择知识库' : '暂无知识库' }}</option><option v-for="item in knowledgeBases" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></div></div>
        <div v-if="knowledgeBase" class="knowledge-content">
          <p class="section-copy">「{{ knowledgeBase.name }}」已连接。文档的上传与解析在「文档资产」页完成，流式问答与 Agent 调试在「AI 会话」页进行。</p>
          <div class="knowledge-next-steps">
            <button class="next-step-card" type="button" @click="router.push('/documents')"><strong>文档资产</strong><span>上传资料、跟踪解析状态、删除文档</span></button>
            <button class="next-step-card" type="button" @click="router.push('/chat')"><strong>AI 问答</strong><span>基于知识库的流式对话，回答自带引用</span></button>
            <button class="next-step-card" type="button" @click="router.push('/agent')"><strong>Agent 模式</strong><span>模型自主调用工具，并展示执行轨迹</span></button>
          </div>
          <div class="content-utility"><button class="ghost-button" type="button" @click="signOut">退出登录</button></div>
        </div><div v-else class="empty-state large-empty"><span class="empty-icon" v-html="SPARK_ICON" /><strong>你的知识库会显示在这里</strong><span>完成左侧设置后，就可以上传文档并开始提问。</span></div>
      </section>
    </section>
  </main>
</template>
