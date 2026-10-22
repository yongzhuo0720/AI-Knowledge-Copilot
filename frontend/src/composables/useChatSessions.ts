import { ref, type Ref } from 'vue'

import { listConversations, type ConversationSession } from '@/services/api'

type UseChatSessionsOptions = {
  /** 当前知识库。为空时任何加载都退化为「清空列表」。 */
  knowledgeBaseId: Ref<string>
  /** 会话搜索词。传入后翻页 / 刷新都带着它，保证整个列表的筛选条件一致。 */
  searchQuery?: Ref<string>
  pageSize: number
  /** 某个会话成为当前会话之后，视图侧要做的事（加载消息、重置输入区…）。 */
  onSessionSelected: (session: ConversationSession) => Promise<void> | void
  /** 列表为空且不自动创建时，视图侧清空会话内状态（消息流、引用来源…）。 */
  onNoSession?: () => void
  /** 列表为空且允许自动创建时，视图侧新建会话并选中它。 */
  createSession?: () => Promise<void>
}

/**
 * 会话列表的加载 / 翻页 / 选中，ChatView 与 AgentView 共用。
 *
 * 两个页面打同一个接口、踩同一套坑：翻页要按 id 去重（会话按最近更新排序，
 * 翻页期间有人发消息就会跨页出现两次）、先发的请求可能后返回、刷新列表时
 * 不能把用户翻出来的历史收缩回一页。这些坑只允许有一份实现。
 */
export function useChatSessions(options: UseChatSessionsOptions) {
  const { knowledgeBaseId, searchQuery, pageSize } = options
  const sessions = ref<ConversationSession[]>([])
  const sessionId = ref<number | null>(null)
  // `sessionPage` 记的是「当前已经加载到第几页」，不是「下一页」——
  // 这样「是否还有下一页」只由 hasMoreSessions 一个来源决定，不会出现两个变量打架。
  const sessionPage = ref(0)
  const hasMoreSessions = ref(false)
  const loadingMoreSessions = ref(false)
  // 请求序号：连续切换知识库 / 搜索词时，先发出的请求可能后返回。
  // 每次发起新请求就 +1，响应回来时序号已变则丢弃，避免过期数据覆盖当前界面。
  let listRequestId = 0

  function fetchSessions(page: number) {
    return listConversations(Number(knowledgeBaseId.value), searchQuery?.value ?? '', page, pageSize)
  }

  /** 选中会话并触发视图侧的消息加载。先赋 sessionId：视图的过期守卫要靠它比对。 */
  async function selectSession(session: ConversationSession) {
    sessionId.value = session.id
    await options.onSessionSelected(session)
  }

  /** 重新加载第一页并尽量保持当前选中；换知识库 / 改搜索词 / 首次进入都走这里。 */
  async function loadSessions(createIfEmpty = true) {
    const requestId = ++listRequestId
    if (!knowledgeBaseId.value) {
      reset()
      return
    }
    // 换知识库 / 改搜索词都相当于「重新开一页」，页码必须归零 ——
    // 否则会把上一次的第 3 页条件带进新列表，直接翻出一片空白。
    sessionPage.value = 0
    const response = await fetchSessions(0)
    // 请求期间用户又切了知识库或改了搜索词：这份响应属于上一屏，直接丢弃。
    if (requestId !== listRequestId) return
    sessions.value = response.data.items
    hasMoreSessions.value = response.data.hasNext
    const selected = sessions.value.find((item) => item.id === sessionId.value) ?? sessions.value[0]
    if (selected) await selectSession(selected)
    else if (createIfEmpty) await options.createSession?.()
    else {
      sessionId.value = null
      options.onNoSession?.()
    }
  }

  /** 侧栏的「加载更多」。请求失败原样抛出，由视图决定怎么提示。 */
  async function loadMoreSessions() {
    if (!knowledgeBaseId.value || !hasMoreSessions.value || loadingMoreSessions.value) return
    loadingMoreSessions.value = true
    const requestId = listRequestId
    try {
      const nextPage = sessionPage.value + 1
      const response = await fetchSessions(nextPage)
      // 翻页期间换了知识库 / 搜索词：这一页属于上一份列表，不能拼进新列表。
      if (requestId !== listRequestId) return
      // 按 id 去重：会话按「最近更新」排序，翻页期间只要有人发了条消息，
      // 它就会从第 2 页跳到第 1 页，于是两页里都有它 —— 不去重会出现两个一模一样的会话。
      const known = new Set(sessions.value.map((item) => item.id))
      sessions.value = [...sessions.value, ...response.data.items.filter((item) => !known.has(item.id))]
      sessionPage.value = nextPage
      hasMoreSessions.value = response.data.hasNext
    } finally {
      loadingMoreSessions.value = false
    }
  }

  /**
   * 只刷新第一页：发送消息 / 执行 Agent 之后会话的标题与排序可能变了。
   * 不能直接复用 loadSessions() —— 它会重置页码、重新选中会话，甚至在没有会话时
   * 新建一个，对「刚发完一条消息」来说这些都太重，还会把用户翻出来的历史收缩回一页。
   * 所以第一页之外的旧会话保留在列表里，失败也静默：列表刷新只是锦上添花，
   * 不能把已经成功的回答报成失败。
   */
  async function refreshFirstPage() {
    if (!knowledgeBaseId.value) return
    const requestId = listRequestId
    try {
      const response = await fetchSessions(0)
      if (requestId !== listRequestId) return
      const firstPageIds = new Set(response.data.items.map((item) => item.id))
      sessions.value = [...response.data.items, ...sessions.value.filter((item) => !firstPageIds.has(item.id))]
      if (sessionPage.value === 0) hasMoreSessions.value = response.data.hasNext
    } catch {
      // 静默：见上。
    }
  }

  /** 清空列表与选中，并让在途请求失效。切换上下文时用。 */
  function reset() {
    listRequestId += 1
    sessions.value = []
    sessionId.value = null
    sessionPage.value = 0
    hasMoreSessions.value = false
    loadingMoreSessions.value = false
  }

  /** 只让在途请求失效，不动已显示的数据。视图卸载时用。 */
  function invalidate() {
    listRequestId += 1
  }

  return {
    sessions, sessionId, sessionPage, hasMoreSessions, loadingMoreSessions,
    selectSession, loadSessions, loadMoreSessions, refreshFirstPage, reset, invalidate,
  }
}
