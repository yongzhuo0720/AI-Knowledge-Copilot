import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import {
  AUTH_CHANGED_EVENT,
  WORKSPACE_CHANGED_EVENT,
  installCrossTabSync,
} from '../crossTabSync'

/**
 * 浏览器只把 `storage` 事件派发给**其它**标签页，所以这里必须手工构造并派发 ——
 * 这也正好还原了「另一个标签页改了 localStorage，我这边收到通知」的真实场景。
 */
function otherTabChangedStorage(key: string | null, newValue: string | null, storageArea: Storage | null = window.localStorage) {
  window.dispatchEvent(new StorageEvent('storage', {
    key,
    oldValue: null,
    newValue,
    storageArea,
  }))
}

let uninstall: (() => void) | null = null
let authEvents: number
let workspaceDetails: Array<{ workspaceId: string }>

function onAuth() {
  authEvents += 1
}

function onWorkspace(event: Event) {
  workspaceDetails.push((event as CustomEvent<{ workspaceId: string }>).detail)
}

beforeEach(() => {
  authEvents = 0
  workspaceDetails = []
  window.addEventListener(AUTH_CHANGED_EVENT, onAuth)
  window.addEventListener(WORKSPACE_CHANGED_EVENT, onWorkspace)
  uninstall = installCrossTabSync()
})

afterEach(() => {
  window.removeEventListener(AUTH_CHANGED_EVENT, onAuth)
  window.removeEventListener(WORKSPACE_CHANGED_EVENT, onWorkspace)
  uninstall?.()
  uninstall = null
  localStorage.clear()
})

describe('installCrossTabSync', () => {
  it('另一个标签页改了 accessToken 时，翻译成本项目的 auth-changed', () => {
    otherTabChangedStorage('accessToken', null)

    expect(authEvents).toBe(1)
    expect(workspaceDetails).toEqual([])
  })

  it('currentUser 变化同样算登录态变化', () => {
    otherTabChangedStorage('currentUser', JSON.stringify({ id: 2 }))

    expect(authEvents).toBe(1)
  })

  it('storage.clear()（key 为 null）也算登录态变化', () => {
    // clear() 会把 token 和工作空间一起抹掉，走 auth-changed 那条路径正好
    // 既能收起外壳、也能清空工作空间
    otherTabChangedStorage(null, null)

    expect(authEvents).toBe(1)
  })

  it('工作空间变化派发 workspace-changed，并把新值放进 detail', () => {
    otherTabChangedStorage('activeWorkspaceId', '26')

    expect(workspaceDetails).toEqual([{ workspaceId: '26' }])
    expect(authEvents).toBe(0)
  })

  it('工作空间被清空时 detail 是空串，而不是 null', () => {
    otherTabChangedStorage('activeWorkspaceId', null)

    expect(workspaceDetails).toEqual([{ workspaceId: '' }])
  })

  it('与本应用无关的 key 不触发任何事件', () => {
    otherTabChangedStorage('some-other-app-key', 'whatever')

    expect(authEvents).toBe(0)
    expect(workspaceDetails).toEqual([])
  })

  it('忽略 sessionStorage 的事件', () => {
    otherTabChangedStorage('accessToken', null, window.sessionStorage)

    expect(authEvents).toBe(0)
  })

  it('卸载后不再响应', () => {
    uninstall?.()
    uninstall = null

    otherTabChangedStorage('accessToken', null)

    expect(authEvents).toBe(0)
  })
})
