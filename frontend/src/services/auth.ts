import { AUTH_CHANGED_EVENT } from '@/services/crossTabSync'

/** localStorage 里 `currentUser` 的形状。`email` 等字段按登录响应写入，读取方按需取用。 */
export type StoredUser = {
  id: number
  username: string
  email?: string
  status?: string
  isAdmin?: boolean
}

export function readStoredUser(): StoredUser | null {
  const stored = localStorage.getItem('currentUser')
  if (!stored) return null
  try { return JSON.parse(stored) as StoredUser } catch { return null }
}

/**
 * 清空本地登录态并广播 AUTH_CHANGED_EVENT。
 *
 * 登出与 401 失效最终都走这里；「是否先调后端 logout 作废 token」由调用方决定 ——
 * 顺序必须是先 logout 后 clear：请求头里的 token 就是从 localStorage 读的，先清就传不出去了。
 */
export function clearAuthState(): void {
  localStorage.removeItem('accessToken')
  localStorage.removeItem('currentUser')
  localStorage.removeItem('activeWorkspaceId')
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT))
}
