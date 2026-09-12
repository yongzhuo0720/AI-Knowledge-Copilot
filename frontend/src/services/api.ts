export interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

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

export interface KnowledgeRetrievalChunk {
  documentObjectKey: string
  content: string
  score: number
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
}

export interface ConversationReply {
  userMessage: ConversationMessage
  assistantMessage: ConversationMessage
}

export interface LoginResponse {
  accessToken: string
  user: { id: number; username: string; email: string; status: string }
}

export interface Workspace {
  id: number
  name: string
  ownerUserId: number
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

function userHeaders(userId: number): HeadersInit {
  void userId
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
  window.dispatchEvent(new Event('aicopilot-auth-changed'))
}

export async function login(email: string, password: string): Promise<ApiResponse<LoginResponse>> {
  const response = await fetch('/api/v1/users/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  return parseResponse<LoginResponse>(response, '登录失败')
}

export async function registerUser(username: string, email: string, password: string): Promise<ApiResponse<{ id: number; username: string; email: string; status: string }>> {
  const response = await fetch('/api/v1/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, email, password }),
  })
  return parseResponse(response, '注册失败')
}

export async function getHealth(): Promise<ApiResponse<{ status: string }>> {
  const response = await fetch('/api/v1/health')
  return parseResponse<{ status: string }>(response, '请求失败')
}

export async function createWorkspace(userId: number, name: string): Promise<ApiResponse<Workspace>> {
  const response = await fetch('/api/v1/workspaces', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...userHeaders(userId) },
    body: JSON.stringify({ name, ownerUserId: userId }),
  })
  return parseResponse<Workspace>(response, '创建工作空间失败')
}

export async function listWorkspaces(userId: number): Promise<ApiResponse<Workspace[]>> {
  const response = await fetch('/api/v1/workspaces', { headers: userHeaders(userId) })
  return parseResponse<Workspace[]>(response, '获取工作空间失败')
}

export async function getWorkspaceOverview(userId: number, workspaceId: number): Promise<ApiResponse<WorkspaceOverview>> {
  const response = await fetch(`/api/v1/workspaces/${workspaceId}/overview`, { headers: userHeaders(userId) })
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
    headers: { 'Content-Type': 'application/json', ...userHeaders(userId) },
    body: JSON.stringify({ workspaceId, name, description }),
  })
  return parseResponse<KnowledgeBase>(response, '创建知识库失败')
}

export async function listKnowledgeBases(userId: number, workspaceId: number): Promise<ApiResponse<KnowledgeBase[]>> {
  const response = await fetch(`/api/v1/knowledge-bases?workspaceId=${workspaceId}`, { headers: userHeaders(userId) })
  return parseResponse<KnowledgeBase[]>(response, '获取知识库失败')
}

export async function registerDocument(
  userId: number,
  knowledgeBaseId: number,
  document: Omit<KnowledgeDocument, 'id' | 'knowledgeBaseId' | 'status' | 'createdAt'>,
): Promise<ApiResponse<KnowledgeDocument>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/documents`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...userHeaders(userId) },
    body: JSON.stringify(document),
  })
  return parseResponse<KnowledgeDocument>(response, '登记文档失败')
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
    headers: userHeaders(userId),
    body: formData,
  })
  return parseResponse<KnowledgeDocument>(response, '上传文档失败')
}

export async function listDocuments(userId: number, knowledgeBaseId: number): Promise<ApiResponse<KnowledgeDocument[]>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/documents`, { headers: userHeaders(userId) })
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
  const response = await fetch(`/api/v1/workspaces/${workspaceId}/documents${queryString ? `?${queryString}` : ''}`, { headers: userHeaders(userId) })
  return parseResponse<WorkspaceDocument[]>(response, '获取 Workspace 文档失败')
}

export async function refreshDocumentProcessingStatus(
  userId: number,
  knowledgeBaseId: number,
  documentId: number,
): Promise<ApiResponse<KnowledgeDocument>> {
  const response = await fetch(
    `/api/v1/knowledge-bases/${knowledgeBaseId}/documents/${documentId}/processing-status`,
    { headers: userHeaders(userId) },
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
    { method: 'POST', headers: userHeaders(userId) },
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
    { method: 'POST', headers: userHeaders(userId) },
  )
  return parseResponse<KnowledgeDocument>(response, '重试解析任务失败')
}

export async function searchKnowledge(
  userId: number,
  knowledgeBaseId: number,
  query: string,
): Promise<ApiResponse<KnowledgeRetrievalChunk[]>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...userHeaders(userId) },
    body: JSON.stringify({ query }),
  })
  return parseResponse<KnowledgeRetrievalChunk[]>(response, '检索知识库失败')
}

export async function askKnowledge(userId: number, knowledgeBaseId: number, question: string): Promise<ApiResponse<KnowledgeAnswer>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/answer`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', ...userHeaders(userId) }, body: JSON.stringify({ question }),
  })
  return parseResponse<KnowledgeAnswer>(response, '知识问答失败')
}

export async function runKnowledgeAgent(
  userId: number,
  knowledgeBaseId: number,
  question: string,
): Promise<ApiResponse<KnowledgeAgentAnswer>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/agents/knowledge`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...userHeaders(userId) },
    body: JSON.stringify({ question }),
  })
  return parseResponse<KnowledgeAgentAnswer>(response, 'Agent 执行失败')
}

export async function createConversation(
  userId: number,
  knowledgeBaseId: number,
  title?: string,
): Promise<ApiResponse<ConversationSession>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/conversations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...userHeaders(userId) },
    body: JSON.stringify({ title }),
  })
  return parseResponse<ConversationSession>(response, '创建会话失败')
}

export async function listConversations(
  userId: number,
  knowledgeBaseId: number,
): Promise<ApiResponse<ConversationSession[]>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/conversations`, {
    headers: userHeaders(userId),
  })
  return parseResponse<ConversationSession[]>(response, '获取会话列表失败')
}

export async function listConversationMessages(
  userId: number,
  knowledgeBaseId: number,
  sessionId: number,
): Promise<ApiResponse<ConversationMessage[]>> {
  const response = await fetch(
    `/api/v1/knowledge-bases/${knowledgeBaseId}/conversations/${sessionId}/messages`,
    { headers: userHeaders(userId) },
  )
  return parseResponse<ConversationMessage[]>(response, '获取会话消息失败')
}

export async function renameConversation(
  userId: number,
  knowledgeBaseId: number,
  sessionId: number,
  title: string,
): Promise<ApiResponse<ConversationSession>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/conversations/${sessionId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', ...userHeaders(userId) },
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
    headers: userHeaders(userId),
  })
  if (!response.ok) {
    if (response.status === 401) handleAuthenticationFailure()
    const payload = await response.json().catch(() => null) as ApiResponse<unknown> | null
    throw new Error(payload?.message ? `删除会话失败：${payload.message}` : `删除会话失败：${response.status}`)
  }
}

export async function askConversation(
  userId: number,
  knowledgeBaseId: number,
  sessionId: number,
  question: string,
): Promise<ApiResponse<ConversationReply>> {
  const response = await fetch(
    `/api/v1/knowledge-bases/${knowledgeBaseId}/conversations/${sessionId}/messages`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...userHeaders(userId) },
      body: JSON.stringify({ question }),
    },
  )
  return parseResponse<ConversationReply>(response, '会话问答失败')
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
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...userHeaders(userId) },
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
