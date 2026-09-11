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

export interface KnowledgeRetrievalChunk {
  documentObjectKey: string
  content: string
  score: number
}

export interface KnowledgeAnswer {
  answer: string
  sources: KnowledgeRetrievalChunk[]
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

function userHeaders(userId: number): HeadersInit {
  return { 'X-User-Id': String(userId) }
}

export async function getHealth(): Promise<ApiResponse<{ status: string }>> {
  const response = await fetch('/api/v1/health')
  if (!response.ok) {
    throw new Error(`请求失败：${response.status}`)
  }
  return response.json() as Promise<ApiResponse<{ status: string }>>
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
  if (!response.ok) throw new Error(`创建知识库失败：${response.status}`)
  return response.json() as Promise<ApiResponse<KnowledgeBase>>
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
  if (!response.ok) throw new Error(`登记文档失败：${response.status}`)
  return response.json() as Promise<ApiResponse<KnowledgeDocument>>
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
  if (!response.ok) throw new Error(`上传文档失败：${response.status}`)
  return response.json() as Promise<ApiResponse<KnowledgeDocument>>
}

export async function listDocuments(userId: number, knowledgeBaseId: number): Promise<ApiResponse<KnowledgeDocument[]>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/documents`, { headers: userHeaders(userId) })
  if (!response.ok) throw new Error(`获取文档列表失败：${response.status}`)
  return response.json() as Promise<ApiResponse<KnowledgeDocument[]>>
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
  if (!response.ok) throw new Error(`查询解析状态失败：${response.status}`)
  return response.json() as Promise<ApiResponse<KnowledgeDocument>>
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
  if (!response.ok) throw new Error(`重新解析文档失败：${response.status}`)
  return response.json() as Promise<ApiResponse<KnowledgeDocument>>
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
  if (!response.ok) throw new Error(`重试解析任务失败：${response.status}`)
  return response.json() as Promise<ApiResponse<KnowledgeDocument>>
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
  if (!response.ok) throw new Error(`检索知识库失败：${response.status}`)
  return response.json() as Promise<ApiResponse<KnowledgeRetrievalChunk[]>>
}

export async function askKnowledge(userId: number, knowledgeBaseId: number, question: string): Promise<ApiResponse<KnowledgeAnswer>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/answer`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', ...userHeaders(userId) }, body: JSON.stringify({ question }),
  })
  if (!response.ok) throw new Error(`知识问答失败：${response.status}`)
  return response.json() as Promise<ApiResponse<KnowledgeAnswer>>
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
  if (!response.ok) throw new Error(`创建会话失败：${response.status}`)
  return response.json() as Promise<ApiResponse<ConversationSession>>
}

export async function listConversations(
  userId: number,
  knowledgeBaseId: number,
): Promise<ApiResponse<ConversationSession[]>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/conversations`, {
    headers: userHeaders(userId),
  })
  if (!response.ok) throw new Error(`获取会话列表失败：${response.status}`)
  return response.json() as Promise<ApiResponse<ConversationSession[]>>
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
  if (!response.ok) throw new Error(`获取会话消息失败：${response.status}`)
  return response.json() as Promise<ApiResponse<ConversationMessage[]>>
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
  if (!response.ok) throw new Error(`会话问答失败：${response.status}`)
  return response.json() as Promise<ApiResponse<ConversationReply>>
}
