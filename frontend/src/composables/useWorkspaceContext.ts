import { getCurrentScope, onScopeDispose, ref } from 'vue'
import { AUTH_CHANGED_EVENT, WORKSPACE_CHANGED_EVENT } from '@/services/crossTabSync'
import { listKnowledgeBases, listWorkspaces, type KnowledgeBase, type Workspace } from '@/services/api'
import { readStoredUser, type StoredUser } from '@/services/auth'

/**
 * 全局工作空间上下文（模块级单例，所有视图共享同一份状态）。
 *
 * 之前每个视图都自带一套样板：读 localStorage 的 currentUser / activeWorkspaceId、
 * 监听 AUTH_CHANGED_EVENT / WORKSPACE_CHANGED_EVENT、加载 workspaces、再加载知识库。
 * 七个视图 × 四步样板，改一处状态同步就要改七处。这里把它收敛成单一数据源：
 *
 * - 窗口事件监听只装一次（随应用存活，不随组件卸载）；
 * - workspaces / knowledgeBases 全局只加载一份，视图直接消费；
 * - 视图通过 onContextChange 订阅「上下文变了」，再各刷各的业务数据。
 */

const currentUser = ref<StoredUser | null>(readStoredUser())
const workspaces = ref<Workspace[]>([])
const workspaceId = ref(localStorage.getItem('activeWorkspaceId') ?? '')
const knowledgeBases = ref<KnowledgeBase[]>([])
const loading = ref(false)

type ContextListener = (change: { workspaceChanged: boolean }) => void
const listeners = new Set<ContextListener>()

function notify(workspaceChanged: boolean) {
  listeners.forEach((listener) => listener({ workspaceChanged }))
}

/** 把 localStorage 的最新值同步进 refs（登出/401/跨标签页切换都靠它感知）。 */
function syncFromStorage() {
  currentUser.value = readStoredUser()
  workspaceId.value = localStorage.getItem('activeWorkspaceId') ?? ''
}

async function loadKnowledgeBases(): Promise<void> {
  if (!currentUser.value || !workspaceId.value) {
    knowledgeBases.value = []
    return
  }
  const response = await listKnowledgeBases(Number(workspaceId.value))
  knowledgeBases.value = response.data
}

/**
 * 全量恢复上下文：读存储 → 加载工作空间（失效的选中项回退到第一个）→ 加载知识库。
 *
 * 出错时抛给调用方展示；订阅者在 finally 里收到通知，视图各自的加载函数
 * 会因上下文为空而自然短路。`workspaceChanged` 显式传入时以它为准
 * （switchWorkspace 在调用前就已更新了 ref，靠存储 diff 判断不出来）。
 */
async function loadContext(explicit?: { workspaceChanged: boolean }): Promise<void> {
  const previousWorkspaceId = workspaceId.value
  syncFromStorage()
  let workspaceChanged = explicit?.workspaceChanged ?? workspaceId.value !== previousWorkspaceId
  if (!currentUser.value) {
    workspaces.value = []
    knowledgeBases.value = []
    notify(workspaceChanged)
    return
  }
  loading.value = true
  try {
    const response = await listWorkspaces()
    workspaces.value = response.data
    if (!workspaceId.value || !workspaces.value.some((item) => String(item.id) === workspaceId.value)) {
      workspaceId.value = workspaces.value.length ? String(workspaces.value[0].id) : ''
      workspaceChanged = true
    }
    if (workspaceId.value) localStorage.setItem('activeWorkspaceId', workspaceId.value)
    await loadKnowledgeBases()
  } finally {
    loading.value = false
    notify(workspaceChanged)
  }
}

/**
 * 切换工作空间：先落盘，再走一次 loadContext（所有视图经 onContextChange 感知）。
 * 同标签页不需要再派发 WORKSPACE_CHANGED_EVENT —— 本模块自己就是事件源；
 * 其它标签页由 crossTabSync 的 storage 监听桥接过去。
 */
async function switchWorkspace(id: string): Promise<void> {
  if (!id) return
  const workspaceChanged = id !== workspaceId.value
  workspaceId.value = id
  localStorage.setItem('activeWorkspaceId', id)
  await loadContext({ workspaceChanged })
}

let installed = false
function installListeners() {
  if (installed) return
  installed = true
  // 背景同步没有调用方兜错误：静默吞掉，视图自己的数据加载失败时会各自提示
  const reload = () => { void loadContext().catch(() => {}) }
  window.addEventListener(AUTH_CHANGED_EVENT, reload)
  window.addEventListener(WORKSPACE_CHANGED_EVENT, reload)
}

/**
 * 订阅上下文变化。在组件 setup 里调用时随组件卸载自动取消订阅；
 * 也可以手动持有返回的取消函数。
 */
function onContextChange(listener: ContextListener): () => void {
  listeners.add(listener)
  if (getCurrentScope()) onScopeDispose(() => { listeners.delete(listener) })
  return () => { listeners.delete(listener) }
}

/** 按视图本地的知识库选中值找到对应知识库（视图存字符串，后端 id 是数字）。 */
function findKnowledgeBase(knowledgeBaseId: string): KnowledgeBase | undefined {
  return knowledgeBases.value.find((item) => item.id === Number(knowledgeBaseId))
}

export function useWorkspaceContext() {
  installListeners()
  return {
    currentUser,
    workspaces,
    workspaceId,
    knowledgeBases,
    loading,
    loadContext,
    loadKnowledgeBases,
    switchWorkspace,
    onContextChange,
    findKnowledgeBase,
  }
}
