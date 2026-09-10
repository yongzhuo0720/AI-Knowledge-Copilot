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
  createdAt: string
}

export async function getHealth(): Promise<ApiResponse<{ status: string }>> {
  const response = await fetch('/api/v1/health')
  if (!response.ok) {
    throw new Error(`请求失败：${response.status}`)
  }
  return response.json() as Promise<ApiResponse<{ status: string }>>
}

export async function createKnowledgeBase(
  workspaceId: number,
  name: string,
  description: string,
): Promise<ApiResponse<KnowledgeBase>> {
  const response = await fetch('/api/v1/knowledge-bases', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ workspaceId, name, description }),
  })
  if (!response.ok) throw new Error(`创建知识库失败：${response.status}`)
  return response.json() as Promise<ApiResponse<KnowledgeBase>>
}

export async function registerDocument(
  knowledgeBaseId: number,
  document: Omit<KnowledgeDocument, 'id' | 'knowledgeBaseId' | 'status' | 'createdAt'>,
): Promise<ApiResponse<KnowledgeDocument>> {
  const response = await fetch(`/api/v1/knowledge-bases/${knowledgeBaseId}/documents`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(document),
  })
  if (!response.ok) throw new Error(`登记文档失败：${response.status}`)
  return response.json() as Promise<ApiResponse<KnowledgeDocument>>
}
