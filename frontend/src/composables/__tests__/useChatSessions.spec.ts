import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

import * as api from '@/services/api'
import type { ConversationSession } from '@/services/api'
import { useChatSessions } from '../useChatSessions'

vi.mock('@/services/api', () => ({ listConversations: vi.fn() }))

function session(id: number): ConversationSession {
  return { id, knowledgeBaseId: 1, userId: 1, title: `会话${id}`, createdAt: '2026-10-22', updatedAt: '2026-10-22' }
}
function page(items: ConversationSession[], hasNext = false) {
  return { code: 'OK', message: '', data: { items, page: 0, size: 20, totalElements: items.length, totalPages: 1, hasNext } }
}

type Composable = ReturnType<typeof useChatSessions>

function setup(overrides: Partial<Parameters<typeof useChatSessions>[0]> = {}) {
  const knowledgeBaseId = ref('1')
  const options = {
    knowledgeBaseId,
    pageSize: 20,
    onSessionSelected: vi.fn(),
    ...overrides,
  }
  return { knowledgeBaseId, options, ...useChatSessions(options) }
}

beforeEach(() => {
  vi.mocked(api.listConversations).mockReset()
})

describe('useChatSessions', () => {
  it('知识库为空时 loadSessions 清空列表而不发请求', async () => {
    const c = setup({ knowledgeBaseId: ref('') })
    c.sessions.value = [session(1)]

    await c.loadSessions()

    expect(api.listConversations).not.toHaveBeenCalled()
    expect(c.sessions.value).toEqual([])
    expect(c.sessionId.value).toBeNull()
  })

  it('优先恢复之前选中的会话，而不是列表第一条', async () => {
    vi.mocked(api.listConversations).mockResolvedValue(page([session(2), session(1)]))
    const c = setup()
    c.sessionId.value = 1

    await c.loadSessions()

    expect(c.sessionId.value).toBe(1)
    expect(c.options.onSessionSelected).toHaveBeenCalledWith(expect.objectContaining({ id: 1 }))
  })

  it('列表为空且允许创建时交给 createSession 兜底', async () => {
    vi.mocked(api.listConversations).mockResolvedValue(page([]))
    const createSession = vi.fn(async () => {})
    const c = setup({ createSession })

    await c.loadSessions()

    expect(createSession).toHaveBeenCalledTimes(1)
    expect(c.options.onSessionSelected).not.toHaveBeenCalled()
  })

  it('列表为空且不创建时清空选中并通知视图', async () => {
    vi.mocked(api.listConversations).mockResolvedValue(page([]))
    const onNoSession = vi.fn(() => {})
    const c = setup({ onNoSession })
    c.sessionId.value = 5

    await c.loadSessions(false)

    expect(c.sessionId.value).toBeNull()
    expect(onNoSession).toHaveBeenCalledTimes(1)
  })

  it('先发的请求后返回时被丢弃，不覆盖新列表', async () => {
    let resolveStale!: (value: ReturnType<typeof page>) => void
    vi.mocked(api.listConversations)
      .mockImplementationOnce(() => new Promise((resolve) => { resolveStale = resolve }))
      .mockResolvedValueOnce(page([session(9)]))
    const c = setup()

    const first = c.loadSessions()
    c.knowledgeBaseId.value = '2'
    const second = c.loadSessions()
    resolveStale(page([session(7)]))
    await Promise.all([first, second])

    expect(c.sessions.value.map((item) => item.id)).toEqual([9])
  })

  it('加载更多按 id 去重：跨页重复的会话只保留一次', async () => {
    vi.mocked(api.listConversations)
      .mockResolvedValueOnce(page([session(1)], true))
      .mockResolvedValueOnce(page([session(1), session(2)], false))
    const c = setup()

    await c.loadSessions(false)
    await c.loadMoreSessions()

    expect(c.sessions.value.map((item) => item.id)).toEqual([1, 2])
    expect(c.hasMoreSessions.value).toBe(false)
  })

  it('刷新第一页时保留第一页之外的旧会话，不把历史收缩回去', async () => {
    const c = setup()
    c.sessions.value = [session(1), session(2)]
    c.sessionPage.value = 1
    c.hasMoreSessions.value = true
    vi.mocked(api.listConversations).mockResolvedValueOnce(page([session(1)], true))

    await c.refreshFirstPage()

    expect(c.sessions.value.map((item) => item.id)).toEqual([1, 2])
    expect(c.hasMoreSessions.value).toBe(true)
  })

  it('刷新失败时静默：不抛错也不清空已显示的列表', async () => {
    const c: Composable = setup()
    c.sessions.value = [session(1)]
    vi.mocked(api.listConversations).mockRejectedValueOnce(new Error('boom'))

    await expect(c.refreshFirstPage()).resolves.toBeUndefined()
    expect(c.sessions.value.map((item) => item.id)).toEqual([1])
  })

  it('selectSession 记下选中并触发视图回调', async () => {
    const c = setup()

    await c.selectSession(session(5))

    expect(c.sessionId.value).toBe(5)
    expect(c.options.onSessionSelected).toHaveBeenCalledTimes(1)
  })
})
