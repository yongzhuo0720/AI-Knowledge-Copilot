import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import AgentView from '../AgentView.vue'
import ChatView from '../ChatView.vue'
import DocumentsView from '../DocumentsView.vue'
import KnowledgeView from '../KnowledgeView.vue'
import RetrievalView from '../RetrievalView.vue'
import SettingsView from '../SettingsView.vue'
import * as api from '@/services/api'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/services/api', async (importOriginal) => ({
  ...await importOriginal<typeof api>(),
  listWorkspaces: vi.fn(),
  listKnowledgeBases: vi.fn(),
  listConversations: vi.fn(),
  listConversationMessages: vi.fn(),
  listWorkspaceMembers: vi.fn(),
  listDocuments: vi.fn(),
  listWorkspaceDocuments: vi.fn(),
  searchKnowledge: vi.fn(),
  askKnowledge: vi.fn(),
  logout: vi.fn(),
  streamConversation: vi.fn(),
}))

const workspaces = [
  { id: 1, name: '研发', ownerUserId: 1 },
  { id: 2, name: '产品', ownerUserId: 1 },
]
const sessions = [
  { id: 11, knowledgeBaseId: 10, userId: 1, title: '会话一', createdAt: '2026-09-19', updatedAt: '2026-09-19' },
  { id: 12, knowledgeBaseId: 10, userId: 1, title: '会话二', createdAt: '2026-09-19', updatedAt: '2026-09-19' },
]
function response<T>(data: T) { return { code: 'OK', message: '', data } }
function page<T>(items: T[]) {
  return response({ items, page: 0, size: 20, totalElements: items.length, totalPages: 1, hasNext: false })
}

let wrapper: VueWrapper | undefined

beforeEach(() => {
  localStorage.setItem('accessToken', 'test-token')
  localStorage.setItem('currentUser', JSON.stringify({ id: 1, username: '测试', email: 'test@example.com', status: 'ACTIVE' }))
  localStorage.setItem('activeWorkspaceId', '1')
  vi.mocked(api.listWorkspaces).mockResolvedValue(response(workspaces))
  vi.mocked(api.listKnowledgeBases).mockImplementation(async (_userId, workspaceId) => response([
    { id: workspaceId * 10, workspaceId, name: `${workspaceId}号知识库`, description: '', status: 'ACTIVE' },
  ]))
  vi.mocked(api.listConversations).mockResolvedValue(page(sessions))
  vi.mocked(api.listConversationMessages).mockResolvedValue(page([]))
  vi.mocked(api.listWorkspaceMembers).mockResolvedValue(response([]))
  vi.mocked(api.listDocuments).mockResolvedValue(response([]))
  vi.mocked(api.listWorkspaceDocuments).mockResolvedValue(response([]))
  vi.mocked(api.logout).mockResolvedValue()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  localStorage.clear()
})

describe('工作空间切换', () => {
  it.each([
    ['Agent', AgentView],
    ['Retrieval', RetrievalView],
    ['Settings', SettingsView],
  ] as const)('%s 同步侧栏切换的工作空间', async (_name, component) => {
    wrapper = mount(component)
    await flushPromises()

    localStorage.setItem('activeWorkspaceId', '2')
    window.dispatchEvent(new CustomEvent('aicopilot-workspace-changed', { detail: { workspaceId: '2' } }))
    await flushPromises()

    expect(wrapper.find('select').element.value).toBe('2')
    if (component === SettingsView) expect(api.listWorkspaceMembers).toHaveBeenLastCalledWith(1, 2)
    else expect(api.listKnowledgeBases).toHaveBeenLastCalledWith(1, 2)
  })

  it.each([
    ['Agent', AgentView],
    ['Retrieval', RetrievalView],
    ['Settings', SettingsView],
    ['Chat', ChatView],
  ] as const)('%s 的局部选择通知全局侧栏', async (_name, component) => {
    wrapper = mount(component)
    await flushPromises()
    const listener = vi.fn()
    window.addEventListener('aicopilot-workspace-changed', listener)
    try {
      await wrapper.find('select').setValue('2')
      await flushPromises()
      expect(localStorage.getItem('activeWorkspaceId')).toBe('2')
      expect(listener).toHaveBeenCalledTimes(1)
    } finally {
      window.removeEventListener('aicopilot-workspace-changed', listener)
    }
  })
})

it('检索调试保留所选模式的结果，并把 Top-K 发给后端', async () => {
  vi.mocked(api.searchKnowledge).mockResolvedValue(response([
    { documentObjectKey: 'keyword.txt', content: '关键词匹配片段', score: 5, retriever: 'keyword' },
  ]))
  vi.mocked(api.askKnowledge).mockResolvedValue(response({
    answer: '服务端默认模式回答',
    sources: [{ documentObjectKey: 'hybrid.txt', content: '默认回答来源', score: 0.5, retriever: 'hybrid' }],
  }))
  wrapper = mount(RetrievalView)
  await flushPromises()
  await wrapper.find('input').setValue('发布规则')
  await wrapper.findAll('select')[2]!.setValue('10')
  await wrapper.findAll('select')[3]!.setValue('keyword')
  await wrapper.find('button.retrieval-run').trigger('click')
  await flushPromises()

  expect(api.searchKnowledge).toHaveBeenLastCalledWith(1, 10, '发布规则', 'keyword', 10)
  expect(wrapper.find('.retrieval-chunk-list').text()).toContain('关键词匹配片段')
  expect(wrapper.find('.retrieval-chunk-list').text()).not.toContain('默认回答来源')
})

it('文档页上传入口接受表格、演示文稿与网页格式', async () => {
  wrapper = mount(DocumentsView)
  await flushPromises()

  const accept = wrapper.find('input[type="file"]').attributes('accept') ?? ''
  expect(accept).toContain('.xlsx')
  expect(accept).toContain('.pptx')
  expect(accept).toContain('.html')
  expect(wrapper.text()).toContain('XLSX')
})

it('成员角色选择不再提供所有者', async () => {
  wrapper = mount(SettingsView)
  await flushPromises()

  const roleSelect = wrapper.findAll('select').find((select) => select.text().includes('普通成员'))
  expect(roleSelect).toBeTruthy()
  expect(roleSelect!.text()).not.toContain('所有者')
})

it('知识库页退出登录先撤销后端 Token', async () => {
  wrapper = mount(KnowledgeView)
  await flushPromises()
  await wrapper.find('.content-utility button').trigger('click')
  await flushPromises()

  expect(api.logout).toHaveBeenCalledTimes(1)
  expect(localStorage.getItem('accessToken')).toBeNull()
})

it('切换会话后忽略上一条生成流的迟到消息', async () => {
  let dispatch: ((event: api.ConversationStreamEvent) => void) | undefined
  let finish: (() => void) | undefined
  vi.mocked(api.streamConversation).mockImplementation(async (_user, _base, _session, _question, _signal, onEvent) => {
    dispatch = onEvent
    await new Promise<void>((resolve) => { finish = resolve })
  })
  wrapper = mount(ChatView)
  await flushPromises()
  await wrapper.find('textarea').setValue('旧会话问题')
  await wrapper.find('form.chat-composer').trigger('submit')
  await flushPromises()
  await wrapper.findAll('.chat-session-item')[1]!.trigger('click')
  await flushPromises()
  dispatch?.({ event: 'delta', data: { content: '旧会话迟到内容' } })
  await flushPromises()

  expect(wrapper.find('.chat-messages').text()).not.toContain('旧会话迟到内容')
  expect(vi.mocked(api.streamConversation).mock.calls[0]![4].aborted).toBe(true)
  finish?.()
  await flushPromises()
})
