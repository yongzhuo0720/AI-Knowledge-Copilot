import { AUTH_CHANGED_EVENT } from '@/services/crossTabSync'
export interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

/**
 * 后端分页响应的统一外壳，对应 Java 侧的 `PageResponse<T>`。
 *
 * `hasNext` 其实可以由 page / size / totalElements 算出来，但仍然由后端返回：
 * 「要不要显示加载更多」是前端最高频的判断，让它少算一次就少一处算错。
 */
export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

/** 后端单页上限（PageQuery.MAX_SIZE）。超过这个值的 size 会被后端压回来。 */
export const MAX_PAGE_SIZE = 100

/** 默认每页条数，和后端 `PageQuery.DEFAULT_SIZE` 保持一致。 */
export const DEFAULT_PAGE_SIZE = 20

export interface KnowledgeBase {
  id: number
  workspaceId: number
  name: string
  description: string | null
  status: string
}

export interface KnowledgeDocument {
  id: number
  knowledgeBaseId: number
  originalFilename: string
  objectKey: string
  contentType: string
  fileSize: number
  status: string
  processingTaskId: string | null
  createdAt: string
  processingFailureReason?: string | null
  processingRetryCount?: number
}

export interface WorkspaceDocument {
  id: number
  knowledgeBaseId: number
  knowledgeBaseName: string
  originalFilename: string
  objectKey: string
  contentType: string
  fileSize: number
  status: string
  processingTaskId: string | null
  createdAt: string
  processingFailureReason?: string | null
  processingRetryCount?: number
}

/** 检索模式：vector 纯向量 / keyword 纯 BM25 关键词 / hybrid 双路融合（默认） */
export type RetrievalMode = 'vector' | 'keyword' | 'hybrid'

export interface KnowledgeRetrievalChunk {
  documentObjectKey: string
  content: string
  score: number
  /** 该片段被哪一路召回器命中，用于在界面上区分向量召回与关键词召回 */
  retriever?: RetrievalMode | null
}

export interface KnowledgeAnswer {
  answer: string
  sources: KnowledgeRetrievalChunk[]
}

export interface KnowledgeAgentStep {
  tool: string
  query: string
  resultCount: number
}

export interface KnowledgeAgentAnswer extends KnowledgeAnswer {
  steps: KnowledgeAgentStep[]
}

export interface ConversationSession {
  id: number
  knowledgeBaseId: number
  userId: number
  title: string
  createdAt: string
  updatedAt: string
}

export interface ConversationMessage {
  id: number
  sessionId: number
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt: string
  sources: KnowledgeRetrievalChunk[]
  agentSteps?: KnowledgeAgentStep[]
}

export interface ConversationReply {
  userMessage: ConversationMessage
  assistantMessage: ConversationMessage
}

export interface LoginResponse {
  accessToken: string
  user: { id: number; username: string; email: string; status: string; isAdmin?: boolean }
}

export interface Workspace {
  id: number
  name: string
  ownerUserId: number
}

export interface WorkspaceMember {
  workspaceId: number
  userId: number
  role: 'OWNER' | 'ADMIN' | 'MEMBER'
  joinedAt: string | null
}

export interface WorkspaceOverview {
  workspaceId: number
  knowledgeBaseCount: number
  documentCount: number
  indexedChunkCount: number | null
  conversationCount: number
  processingTaskCount: number
  recentKnowledgeBases: Array<{
    id: number
    name: string
    status: string
    updatedAt: string
    documentCount: number
  }>
  recentDocuments: Array<{
    id: number
    knowledgeBaseId: number
    knowledgeBaseName: string
    originalFilename: string
    contentType: string
    fileSize: number
    status: string
    createdAt: string
  }>
  recentConversations: Array<{
    id: number
    title: string
    knowledgeBaseId: number
    knowledgeBaseName: string
    updatedAt: string
  }>
}

function userHeaders(): HeadersInit {
  const token = localStorage.getItem('accessToken')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function parseResponse<T>(response: Response, fallback: string): Promise<ApiResponse<T>> {
  const payload = await response.json().catch(() => null) as ApiResponse<T> | null
  if (!response.ok) {
    if (response.status === 401) handleAuthenticationFailure()
    throw new Error(payload?.message ? `${fallback}：${payload.message}` : `${fallback}：${response.status}`)
  }
  return payload as ApiResponse<T>
}

function handleAuthenticationFailure() {
  if (!localStorage.getItem('accessToken')) return
  localStorage.removeItem('accessToken')
  localStorage.removeItem('currentUser')
  localStorage.removeItem('activeWorkspaceId')
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT))
}

export async function login(email: string, password: string, asAdmin = false): Promise<ApiResponse<LoginResponse>> {
  const response = await fetch('/api/v1/users/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(asAdmin ? { email, password, admin: true } : { email, password }),
  })
  return parseResponse<LoginResponse>(response, asAdmin ? '管理员登录失败' : '登录失败')
}

/**
 * 登出：通知后端把 token 从 Redis 删掉。
 *
 * 必须在**清除 localStorage 之前**调用 —— 请求头里的 token 就是从 localStorage 读的，
 * 先清了就没得传了。
 *
 * 失败不影响本地登出流程（本地状态无论如何都要清掉），所以这里只吞掉错误。
 */
export async function logout(): Promise<void> {
  try {
    await fetch('/api/v1/users/logout', { method: 'POST', headers: userHeaders() })
  } catch {
    // 网络异常时也要保证本地登出成功，静默处理
  }
}

export async function registerUser(username: string, email: string, password: string): Promise<ApiResponse<{ id: number; username: string; email: string; status: string }>> {
  const response = await fetch('/api/v1/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, email, password }),
  })
  return parseResponse(response, '注册失败')
}

export async function createWorkspace(userId: number, name: string): Promise<ApiResponse<Workspace>> {
  const response = await fetch('/api/v1/workspaces', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...userHeaders() },
    body: JSON.stringify({ name, ownerUserId: userId }),
  })
  return parseResponse<Workspace>(response, '创建工作空间失败')
}

export async function listWorkspaces(userId: number): Promise<ApiResponse<Workspace[]>> {
  const response = await fetch('/api/v1/workspaces', { headers: userHeaders() })
  return parseResponse<Workspace[]>(response, '获取工作空间失败')
}

export async function listWorkspaceMembers(userId: number, workspaceId: number): Promise<ApiResponse<WorkspaceMember[]>> {
  const response = await fetch(`/api/v1/workspaces/${workspaceId}/members`, { headers: userHeaders() })
  return parseResponse<WorkspaceMember[]>(response, '获取工作空间成员失败')
}

export async function addWorkspaceMember(
  userId: number,
  workspaceId: number,
  memberUserId: number,
  role: WorkspaceMember['role'],
): Promise<ApiResponse<WorkspaceMember>> {
  const response = await fetch(`/api/v1/workspaces/${workspaceId}/members`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...userHeaders() },
    body: JSON.stringify({ userId: memberUserId, role }),
  })
  return parseResponse<WorkspaceMember>(response, '添加工作空间成员失败')
}

export async function getWorkspaceOverview(userId: number, workspaceId: number): Promise<ApiResponse<WorkspaceOverview>> {
  const response = await fetch(`/api/v1/workspaces/${workspaceId}/overview`, { headers: userHeaders() })
  return parseResponse<WorkspaceOverview>(response, '获取工作空间概览失败')
}

export async function createKnowledgeBase(
  userId: number,
  workspaceId: number,
  name: string,
  description: string,
): Promise<ApiResponse<KnowledgeBase>> {
  const response = await fetch('/api/v1/knowledge-bases', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...userHeaders() },
    body: JSON.stringify({ workspaceId, name, description }),
  })
  return parseResponse<KnowledgeBase>(response, '创建知识库失败')
}

export async function listKnowledgeBases(userId: number, workspaceId: number): Promise<ApiResponse<KnowledgeBase[]>> {
  const response = await fetch(`/api/v1/knowledge-bases?workspaceId=${workspaceId}`, { headers: userHeaders() })
  return parseResponse<KnowledgeBase[]>(response, '获取知识库失败')
}

export async function uploadDocument(
  userId: number,
  knowledgeBaseId: number,
  file: File,
): Promise<ApiResponse<KnowledgeDocument>> {
  const formData = new FormData()
  formData.append('file', file)
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/documents/upload`, {
    method: 'POST',
    headers: userHeaders(),
    body: formData,
  })
  return parseResponse<KnowledgeDocument>(response, '上传文档失败')
}

export async function listDocuments(userId: number, knowledgeBaseId: number): Promise<ApiResponse<KnowledgeDocument[]>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/documents`, { headers: userHeaders() })
  return parseResponse<KnowledgeDocument[]>(response, '获取文档列表失败')
}

export async function listWorkspaceDocuments(
  userId: number,
  workspaceId: number,
  filters: { query?: string; status?: string; knowledgeBaseId?: number } = {},
): Promise<ApiResponse<WorkspaceDocument[]>> {
  const params = new URLSearchParams()
  if (filters.query?.trim()) params.set('query', filters.query.trim())
  if (filters.status && filters.status !== 'ALL') params.set('status', filters.status)
  if (filters.knowledgeBaseId) params.set('knowledgeBaseId', String(filters.knowledgeBaseId))
  const queryString = params.toString()
  const response = await fetch(`/api/v1/workspaces/${workspaceId}/documents${queryString ? `?${queryString}` : ''}`, { headers: userHeaders() })
  return parseResponse<WorkspaceDocument[]>(response, '获取 Workspace 文档失败')
}

export async function refreshDocumentProcessingStatus(
  userId: number,
  knowledgeBaseId: number,
  documentId: number,
): Promise<ApiResponse<KnowledgeDocument>> {
  const response = await fetch(
    `/api/v1/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/processing-status`,
    { headers: userHeaders() },
  )
  return parseResponse<KnowledgeDocument>(response, '查询解析状态失败')
}

export async function reparseDocument(
  userId: number,
  knowledgeBaseId: number,
  documentId: number,
): Promise<ApiResponse<KnowledgeDocument>> {
  const response = await fetch(
    `/api/v1/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/reparse`,
    { method: 'POST', headers: userHeaders() },
  )
  return parseResponse<KnowledgeDocument>(response, '重新解析文档失败')
}

export async function retryDocumentProcessing(
  userId: number,
  knowledgeBaseId: number,
  documentId: number,
): Promise<ApiResponse<KnowledgeDocument>> {
  const response = await fetch(
    `/api/v1/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/processing-retry`,
    { method: 'POST', headers: userHeaders() },
  )
  return parseResponse<KnowledgeDocument>(response, '重试解析任务失败')
}

export async function deleteDocument(
  userId: number,
  knowledgeBaseId: number,
  documentId: number,
): Promise<void> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/documents/${documentId}`, {
    method: 'DELETE',
    headers: userHeaders(),
  })
  if (!response.ok) {
    if (response.status === 401) handleAuthenticationFailure()
    const payload = await response.json().catch(() => null) as ApiResponse<unknown> | null
    throw new Error(payload?.message ? `删除文档失败：${payload.message}` : `删除文档失败：${response.status}`)
  }
}

export async function searchKnowledge(
  userId: number,
  knowledgeBaseId: number,
  query: string,
  mode?: RetrievalMode,
  limit?: number,
): Promise<ApiResponse<KnowledgeRetrievalChunk[]>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...userHeaders() },
    // mode / limit 留空则由服务端配置决定，避免前端硬编码默认值
    body: JSON.stringify({ query, ...(mode ? { mode } : {}), ...(limit ? { limit } : {}) }),
  })
  return parseResponse<KnowledgeRetrievalChunk[]>(response, '检索知识库失败')
}

export async function askKnowledge(userId: number, knowledgeBaseId: number, question: string): Promise<ApiResponse<KnowledgeAnswer>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/answer`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', ...userHeaders() }, body: JSON.stringify({ question }),
  })
  return parseResponse<KnowledgeAnswer>(response, '知识问答失败')
}

export async function createConversation(
  userId: number,
  knowledgeBaseId: number,
  title?: string,
): Promise<ApiResponse<ConversationSession>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/conversations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...userHeaders() },
    body: JSON.stringify({ title }),
  })
  return parseResponse<ConversationSession>(response, '创建会话失败')
}

/**
 * 会话列表（分页）。
 *
 * 注意返回的是 `PageResponse` 而不是数组：调用方要读 `.data.items`，
 * 并可以用 `.data.hasNext` 决定要不要显示「加载更多」。
 */
export async function listConversations(
  userId: number,
  knowledgeBaseId: number,
  query = '',
  page = 0,
  size = DEFAULT_PAGE_SIZE,
): Promise<ApiResponse<PageResponse<ConversationSession>>> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (query.trim()) params.set('query', query.trim())
  const response = await fetch(
    `/api/v1/knowledge-bases/${knowledgeBaseId}/conversations?${params.toString()}`,
    { headers: userHeaders() },
  )
  return parseResponse<PageResponse<ConversationSession>>(response, '获取会话列表失败')
}

/**
 * 会话消息（分页）。
 *
 * **页号从最新一条往回数**：page=0 拿到的是最近的一批（页内按时间正序），
 * page=1 是再往前的一批。所以「加载更早的消息」是把旧的一页**插到前面**，
 * 而不是追加到后面。
 */
export async function listConversationMessages(
  userId: number,
  knowledgeBaseId: number,
  sessionId: number,
  page = 0,
  size = DEFAULT_PAGE_SIZE,
): Promise<ApiResponse<PageResponse<ConversationMessage>>> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  const response = await fetch(
    `/api/v1/knowledge-bases/${knowledgeBaseId}/conversations/${sessionId}/messages?${params.toString()}`,
    { headers: userHeaders() },
  )
  return parseResponse<PageResponse<ConversationMessage>>(response, '获取会话消息失败')
}

export async function renameConversation(
  userId: number,
  knowledgeBaseId: number,
  sessionId: number,
  title: string,
): Promise<ApiResponse<ConversationSession>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/conversations/${sessionId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', ...userHeaders() },
    body: JSON.stringify({ title }),
  })
  return parseResponse<ConversationSession>(response, '重命名会话失败')
}

export async function deleteConversation(
  userId: number,
  knowledgeBaseId: number,
  sessionId: number,
): Promise<void> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/conversations/${sessionId}`, {
    method: 'DELETE',
    headers: userHeaders(),
  })
  if (!response.ok) {
    if (response.status === 401) handleAuthenticationFailure()
    const payload = await response.json().catch(() => null) as ApiResponse<unknown> | null
    throw new Error(payload?.message ? `删除会话失败：${payload.message}` : `删除会话失败：${response.status}`)
  }
}

export async function askConversationAgent(
  userId: number,
  knowledgeBaseId: number,
  sessionId: number,
  question: string,
): Promise<ApiResponse<ConversationReply>> {
  const response = await fetch(
    `/api/v1/knowledge-bases/${knowledgeBaseId}/conversations/${sessionId}/agent-messages`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...userHeaders() },
      body: JSON.stringify({ question }),
    },
  )
  return parseResponse<ConversationReply>(response, 'Agent 会话问答失败')
}

export type ConversationStreamEvent =
  | { event: 'user'; data: ConversationMessage }
  | { event: 'delta'; data: { content: string } }
  | { event: 'sources'; data: { sources: KnowledgeRetrievalChunk[] } }
  | { event: 'complete'; data: ConversationReply }
  | { event: 'error'; data: { message: string } }

export async function streamConversation(
  userId: number,
  knowledgeBaseId: number,
  sessionId: number,
  question: string,
  signal: AbortSignal,
  onEvent: (event: ConversationStreamEvent) => void,
): Promise<void> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/conversations/${sessionId}/messages/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...userHeaders() },
    body: JSON.stringify({ question }),
    signal,
  })
  if (!response.ok || !response.body) {
    if (response.status === 401) handleAuthenticationFailure()
    const payload = await response.json().catch(() => null) as ApiResponse<unknown> | null
    throw new Error(payload?.message ? `流式问答失败：${payload.message}` : `流式问答失败：${response.status}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const consume = (block: string) => {
    const eventName = block.match(/^event:\s*(.+)$/m)?.[1]?.trim()
    const dataLine = block.match(/^data:\s*(.+)$/m)?.[1]
    if (!eventName || !dataLine) return
    onEvent({ event: eventName, data: JSON.parse(dataLine) } as ConversationStreamEvent)
  }
  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value ?? new Uint8Array(), { stream: !done })
    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() ?? ''
    blocks.filter(Boolean).forEach(consume)
    if (done) break
  }
  if (buffer.trim()) consume(buffer)
}

// ---------------------------------------------------------------------------
// 后台管理（Admin）
// 所有接口都要求当前登录用户具备管理员身份，由后端 AdminService.requireAdmin 强制校验。
// ---------------------------------------------------------------------------

export interface AdminUser {
  id: number
  username: string
  email: string
  status: string
  isAdmin: boolean
  createdAt: string
  workspaceCount: number
  knowledgeBaseCount: number
  conversationCount: number
}

export interface AdminWorkspace {
  id: number
  name: string
  ownerUserId: number
  ownerUsername: string | null
  memberCount: number
  knowledgeBaseCount: number
  createdAt: string
}

export interface AdminKnowledgeBase {
  id: number
  name: string
  description: string | null
  status: string
  workspaceId: number
  workspaceName: string | null
  documentCount: number
  conversationCount: number
  createdAt: string
}

export interface AdminOverview {
  userCount: number
  activeUserCount: number
  suspendedUserCount: number
  adminCount: number
  workspaceCount: number
  knowledgeBaseCount: number
  documentCount: number
  conversationCount: number
}

function adminJsonHeaders(): HeadersInit {
  return { 'Content-Type': 'application/json', ...userHeaders() }
}

function withQuery(path: string, query?: string): string {
  const trimmed = query?.trim()
  return trimmed ? `${path}?query=${encodeURIComponent(trimmed)}` : path
}

export async function getAdminOverview(): Promise<ApiResponse<AdminOverview>> {
  const response = await fetch('/api/v1/admin/overview', { headers: userHeaders() })
  return parseResponse<AdminOverview>(response, '获取后台概览失败')
}

export async function listAdminUsers(query?: string): Promise<ApiResponse<AdminUser[]>> {
  const response = await fetch(withQuery('/api/v1/admin/users', query), { headers: userHeaders() })
  return parseResponse<AdminUser[]>(response, '获取用户列表失败')
}

export async function updateAdminUserStatus(userId: number, status: 'ACTIVE' | 'SUSPENDED'): Promise<ApiResponse<AdminUser>> {
  const response = await fetch(`/api/v1/admin/users/${userId}/status`, {
    method: 'PATCH',
    headers: adminJsonHeaders(),
    body: JSON.stringify({ status }),
  })
  return parseResponse<AdminUser>(response, '更新用户状态失败')
}

export async function updateAdminUserAdmin(userId: number, isAdmin: boolean): Promise<ApiResponse<AdminUser>> {
  const response = await fetch(`/api/v1/admin/users/${userId}/admin`, {
    method: 'PATCH',
    headers: adminJsonHeaders(),
    body: JSON.stringify({ isAdmin }),
  })
  return parseResponse<AdminUser>(response, '更新管理员权限失败')
}

export async function deleteAdminUser(userId: number): Promise<void> {
  const response = await fetch(`/api/v1/admin/users/${userId}`, {
    method: 'DELETE',
    headers: userHeaders(),
  })
  if (!response.ok) {
    if (response.status === 401) handleAuthenticationFailure()
    const payload = await response.json().catch(() => null) as ApiResponse<unknown> | null
    throw new Error(payload?.message ? `删除用户失败：${payload.message}` : `删除用户失败：${response.status}`)
  }
}

export async function listAdminWorkspaces(query?: string): Promise<ApiResponse<AdminWorkspace[]>> {
  const response = await fetch(withQuery('/api/v1/admin/workspaces', query), { headers: userHeaders() })
  return parseResponse<AdminWorkspace[]>(response, '获取工作空间列表失败')
}

export async function deleteAdminWorkspace(workspaceId: number): Promise<void> {
  const response = await fetch(`/api/v1/admin/workspaces/${workspaceId}`, {
    method: 'DELETE',
    headers: userHeaders(),
  })
  if (!response.ok) {
    if (response.status === 401) handleAuthenticationFailure()
    const payload = await response.json().catch(() => null) as ApiResponse<unknown> | null
    throw new Error(payload?.message ? `删除工作空间失败：${payload.message}` : `删除工作空间失败：${response.status}`)
  }
}

export async function listAdminKnowledgeBases(query?: string): Promise<ApiResponse<AdminKnowledgeBase[]>> {
  const response = await fetch(withQuery('/api/v1/admin/knowledge-bases', query), { headers: userHeaders() })
  return parseResponse<AdminKnowledgeBase[]>(response, '获取知识库列表失败')
}

export async function deleteAdminKnowledgeBase(knowledgeBaseId: number): Promise<void> {
  const response = await fetch(`/api/v1/admin/knowledge-bases/${knowledgeBaseId}`, {
    method: 'DELETE',
    headers: userHeaders(),
  })
  if (!response.ok) {
    if (response.status === 401) handleAuthenticationFailure()
    const payload = await response.json().catch(() => null) as ApiResponse<unknown> | null
    throw new Error(payload?.message ? `删除知识库失败：${payload.message}` : `删除知识库失败：${response.status}`)
  }
}
