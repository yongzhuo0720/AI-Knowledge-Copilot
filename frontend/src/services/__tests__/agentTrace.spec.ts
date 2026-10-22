import { describe, expect, it } from 'vitest'

import { resultUnit, toAgentAnswer } from '../agentTrace'
import type { ConversationMessage } from '../api'

function message(overrides: Partial<ConversationMessage> = {}): ConversationMessage {
  return {
    id: 1,
    sessionId: 1,
    role: 'ASSISTANT',
    content: '回答',
    createdAt: '2026-10-22',
    sources: [],
    ...overrides,
  }
}

describe('resultUnit', () => {
  it.each([
    ['knowledge_search', '片'],
    ['list_documents', '篇'],
    ['document_outline', '小节'],
  ])('%s 的量词是「%s」，与工具说明书一致', (tool, unit) => {
    expect(resultUnit(tool)).toBe(unit)
  })

  it('未知工具退回中性量词「条」，而不是整块轨迹面板渲染失败', () => {
    expect(resultUnit('future_tool')).toBe('条')
  })
})

describe('toAgentAnswer', () => {
  it('从 ASSISTANT 消息还原结果面板结构', () => {
    const steps = [{ tool: 'knowledge_search', query: '发布流程', resultCount: 5 }]
    const answer = toAgentAnswer(message({ agentSteps: steps }))
    expect(answer).toEqual({ answer: '回答', sources: [], steps })
  })

  it('没有 agentSteps 的旧消息退回空轨迹，而不是 undefined', () => {
    expect(toAgentAnswer(message())?.steps).toEqual([])
  })

  it('USER 消息不是 Agent 结果，返回 null 让视图显示空面板', () => {
    expect(toAgentAnswer(message({ role: 'USER', content: '问题' }))).toBeNull()
  })
})
