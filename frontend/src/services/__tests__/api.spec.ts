import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  DEFAULT_PAGE_SIZE,
  MAX_PAGE_SIZE,
  listConversationMessages,
  listConversations,
  type PageResponse,
  type ConversationSession,
} from '../api'

/**
 * 这两个接口是 C 组「补分页」改动里**唯一被破坏性修改的契约**：
 * 返回值从「数组」变成了「分页外壳」。所以这里要钉住两件事：
 *
 * 1. 请求 URL 里的分页参数拼对了没有（拼错的话后端会永远返回第 1 页，
 *    而界面上看起来「就是没有更多数据」，非常难查）；
 * 2. 返回结构确实是 `{ items, hasNext, ... }`，而不是碰巧兼容。
 *
 * 用 stub 掉 global.fetch 的方式测，不引入 MSW 之类的额外依赖 ——
 * 这里要验证的是「我们发出去什么、怎么解读回来」，不是真实网络行为。
 */
function stubFetch(payload: unknown, ok = true) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok,
    status: ok ? 200 : 500,
    json: async () => payload,
  } as unknown as Response)
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function requestedUrl(fetchMock: ReturnType<typeof stubFetch>): string {
  return String(fetchMock.mock.calls[0][0])
}

const emptyPage: PageResponse<ConversationSession> = {
  items: [],
  page: 0,
  size: DEFAULT_PAGE_SIZE,
  totalElements: 0,
  totalPages: 0,
  hasNext: false,
}

describe('listConversations 的分页参数', () => {
  beforeEach(() => localStorage.setItem('accessToken', 'test-token'))
  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('默认请求第 0 页', async () => {
    const fetchMock = stubFetch({ code: 'OK', message: '', data: emptyPage })

    await listConversations(1, 20)

    const url = requestedUrl(fetchMock)
    expect(url).toContain('/api/v1/knowledge-bases/20/conversations?')
    expect(url).toContain('page=0')
    expect(url).toContain(`size=${DEFAULT_PAGE_SIZE}`)
  })

  it('把页号和每页条数原样带上', async () => {
    const fetchMock = stubFetch({ code: 'OK', message: '', data: emptyPage })

    await listConversations(1, 20, '', 3, 50)

    const url = requestedUrl(fetchMock)
    expect(url).toContain('page=3')
    expect(url).toContain('size=50')
  })

  it('搜索词要正确编码，且空串不占位', async () => {
    const withQuery = stubFetch({ code: 'OK', message: '', data: emptyPage })
    await listConversations(1, 20, '发布 流程/规范')

    // 这里**不**断言字面上的 `%20`：URLSearchParams 走的是 query string 的标准编码
    // （application/x-www-form-urlencoded），空格编成 `+`、斜杠编成 `%2F`。
    // 后端 @RequestParam 会把这些还原成原文，所以正确的断言是「解回来还是原来那句话」——
    // 钉住字面编码反而会在换用别的编码实现时误报。
    const raw = requestedUrl(withQuery)
    const params = new URLSearchParams(raw.split('?')[1])
    expect(params.get('query')).toBe('发布 流程/规范')
    // 顺便确认斜杠确实被编码了，没有裸着出现在 URL 里（裸斜杠会被当成路径分隔符）
    expect(raw).not.toContain('query=发布 流程/规范')

    const withoutQuery = stubFetch({ code: 'OK', message: '', data: emptyPage })
    await listConversations(1, 20, '   ')
    // 只有空白字符等于没搜，不该发一个 `query=` 的空参数出去
    expect(requestedUrl(withoutQuery)).not.toContain('query=')
  })

  it('带上 Bearer token', async () => {
    const fetchMock = stubFetch({ code: 'OK', message: '', data: emptyPage })

    await listConversations(1, 20)

    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer test-token')
  })

  it('返回的是分页外壳，调用方要读 items', async () => {
    const page: PageResponse<ConversationSession> = {
      ...emptyPage,
      items: [{ id: 7, knowledgeBaseId: 20, userId: 1, title: '发布流程', createdAt: '', updatedAt: '' }],
      totalElements: 137,
      totalPages: 7,
      hasNext: true,
    }
    stubFetch({ code: 'OK', message: '', data: page })

    const response = await listConversations(1, 20)

    expect(response.data.items).toHaveLength(1)
    expect(response.data.items[0].title).toBe('发布流程')
    expect(response.data.totalElements).toBe(137)
    expect(response.data.hasNext).toBe(true)
  })
})

describe('listConversationMessages 的分页参数', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('页号越大越往历史深处走', async () => {
    const fetchMock = stubFetch({ code: 'OK', message: '', data: emptyPage })

    await listConversationMessages(1, 20, 7, 2, 30)

    expect(requestedUrl(fetchMock)).toContain('/conversations/7/messages?')
    expect(requestedUrl(fetchMock)).toContain('page=2')
    expect(requestedUrl(fetchMock)).toContain('size=30')
  })

  it('size 上限由后端兜底，前端只负责按常量对齐', () => {
    // 这两个值必须和后端 PageQuery 保持一致，否则前端会以为自己拿满了 100 条
    expect(MAX_PAGE_SIZE).toBe(100)
    expect(DEFAULT_PAGE_SIZE).toBe(20)
  })

  it('服务端报错时抛出带后端消息的错误', async () => {
    stubFetch({ code: 'RATE_LIMIT_EXCEEDED', message: 'too many requests', data: null }, false)

    await expect(listConversationMessages(1, 20, 7)).rejects.toThrow('too many requests')
  })
})
