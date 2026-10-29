import { afterEach, describe, expect, it, vi } from 'vitest'

import { streamConversation, type ConversationStreamEvent } from '../api'

/**
 * 流式回答的解析契约。后端（Spring）与 ai-service 的「成功」都以收到
 * complete 事件为准，所以这里钉住三件事：
 * 1. CRLF 帧必须能拆开（浏览器/代理可能改写换行）；
 * 2. 多行 data 按 SSE 规范用 \n 拼接后解析；
 * 3. 没收到 complete 就结束 = 失败，必须 reject，不能静默 resolve。
 */
function stubStream(chunks: string[]) {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)))
      controller.close()
    },
  })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body } as unknown as Response))
}

function collect(): { events: ConversationStreamEvent[] } {
  const events: ConversationStreamEvent[] = []
  return { events }
}

const delta = 'event: delta\ndata: {"content":"你好"}\n\n'
const complete = 'event: complete\ndata: {"userMessage":{"id":1},"assistantMessage":{"id":2}}\n\n'

describe('streamConversation 的 SSE 解析', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('LF 帧：delta 与 complete 依次派发', async () => {
    stubStream([delta + complete])
    const { events } = collect()

    await streamConversation(1, 2, '问题', new AbortController().signal, (event) => events.push(event))

    expect(events.map((event) => event.event)).toEqual(['delta', 'complete'])
  })

  it('CRLF 帧：complete 不能丢', async () => {
    stubStream([delta.replace(/\n/g, '\r\n') + complete.replace(/\n/g, '\r\n')])
    const { events } = collect()

    await streamConversation(1, 2, '问题', new AbortController().signal, (event) => events.push(event))

    expect(events.map((event) => event.event)).toEqual(['delta', 'complete'])
  })

  it('多行 data：拼接后再解析', async () => {
    stubStream(['event: delta\ndata: {"content":\ndata: "换行"}\n\n' + complete])
    const { events } = collect()

    await streamConversation(1, 2, '问题', new AbortController().signal, (event) => events.push(event))

    expect(events[0]).toEqual({ event: 'delta', data: { content: '换行' } })
  })

  it('帧跨越网络块边界时不丢事件', async () => {
    const frame = delta + complete
    stubStream([frame.slice(0, 7), frame.slice(7, 30), frame.slice(30)])
    const { events } = collect()

    await streamConversation(1, 2, '问题', new AbortController().signal, (event) => events.push(event))

    expect(events.map((event) => event.event)).toEqual(['delta', 'complete'])
  })

  it('EOF 缺少 complete 时 reject', async () => {
    stubStream([delta])
    const { events } = collect()

    await expect(
      streamConversation(1, 2, '问题', new AbortController().signal, (event) => events.push(event)),
    ).rejects.toThrow('未正常结束')
  })

  it('收到 error 帧后 EOF 时抛出后端给的原因', async () => {
    stubStream(['event: error\ndata: {"message":"模型输出流未正常结束"}\n\n'])

    await expect(
      streamConversation(1, 2, '问题', new AbortController().signal, () => {}),
    ).rejects.toThrow('模型输出流未正常结束')
  })
})
