export interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

export async function getHealth(): Promise<ApiResponse<{ status: string }>> {
  const response = await fetch('/api/v1/health')
  if (!response.ok) {
    throw new Error(`请求失败：${response.status}`)
  }
  return response.json() as Promise<ApiResponse<{ status: string }>>
}
