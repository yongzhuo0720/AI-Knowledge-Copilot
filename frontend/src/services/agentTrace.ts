/**
 * 执行轨迹（Agent Execution Trace）的展示约定。
 *
 * 抽成独立模块而不是留在 `AgentView.vue` 里，是因为它是**后端工具的语义**、
 * 不是视图的私有细节：新增一个工具就必须在这里补一行，而补错了会让界面上
 * 出现「10 chunks」这种自相矛盾的读数（`list_documents` 返回的明明是「篇」）。
 * 放在这里才有测试能钉住它。
 */

import type { ConversationMessage, KnowledgeAgentAnswer } from '@/services/api'

/**
 * 每个工具 `result_count` 的量词。
 *
 * 用「片」而不是「块」：后端 `agent_tools.describe_call` 的说明书里就写着
 * 「检索『发布流程』，取前 5 片」，两处保持一致，用户看到的文案才不打架。
 */
export const RESULT_UNITS: Record<string, string> = {
  knowledge_search: '片',
  list_documents: '篇',
  document_outline: '小节',
  // 降级步骤的 result_count 是「检索直答」拿到的来源数，同样以「片」计
  fallback: '片',
}

/**
 * 取某个工具对应的量词。
 *
 * 未知工具退回「条」而不是抛错：后端将来加了新工具、前端还没跟上时，
 * 界面应该退化成一个中性的说法，而不是整块轨迹面板渲染失败。
 */
export function resultUnit(tool: string): string {
  return RESULT_UNITS[tool] ?? '条'
}

/**
 * 把一条 ASSISTANT 消息还原成 Agent 结果面板的展示结构。
 *
 * 复看历史会话和刚执行完一次任务做的是同一件事，曾经是两份一模一样的
 * 三元表达式；收在这里保证两处永远一致。传进来的不是 ASSISTANT 消息时
 * 返回 null，调用方据此显示空面板而不是报错。
 */
export function toAgentAnswer(message: ConversationMessage): KnowledgeAgentAnswer | null {
  if (message.role !== 'ASSISTANT') return null
  return {
    answer: message.content,
    sources: message.sources,
    steps: message.agentSteps ?? [],
  }
}
