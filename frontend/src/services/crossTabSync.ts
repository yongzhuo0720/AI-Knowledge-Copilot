/**
 * 多标签页状态同步。
 *
 * ## 为什么需要它
 *
 * `localStorage` 是**跨标签页共享**的，但浏览器派发 `storage` 事件时有一条反直觉的规则：
 * **它只派发到「其它」标签页，当前标签页收不到自己写的改动。**
 *
 * 而本项目各组件监听的 `aicopilot-auth-changed` / `aicopilot-workspace-changed`，是它们
 * 自己 `dispatchEvent` 出来的自定义事件 —— 自定义事件**只在本标签页里传播**。
 *
 * 两件事凑在一起就出现一个洞：
 *
 * ```
 * 标签 A：点「退出登录」 → 清掉 localStorage → 派发 auth-changed → A 自己正常跳走 ✅
 * 标签 B：什么都没发生（storage 事件没人听，自定义事件它又收不到）
 *         → accessToken / currentUser 的 ref 还留着 → 界面仍显示已登录 ❌
 * ```
 *
 * 这个模块就是两者之间的**桥**：监听跨标签页的 `storage` 事件，把它翻译成本项目已有的
 * 自定义事件。于是所有既有监听者（App.vue、HomeView、ChatView、DocumentsView、
 * KnowledgeView）一行都不用改，就能收到跨标签页的通知。
 *
 * ## 为什么放在 main.ts 装一次
 *
 * 它是全局基础设施，不属于任何组件；装在组件里就会出现「哪个组件先挂载才生效」这种
 * 顺序依赖，而且在组件卸载时被误摘掉。装一次、随应用生命周期存活。
 */

/** 与登录态相关的 key。任一变化都意味着「谁登录了」变了。 */
export const AUTH_STORAGE_KEYS = ['accessToken', 'currentUser'] as const

/** 当前工作空间。它变了只影响页面内容，不影响登录态。 */
export const WORKSPACE_STORAGE_KEY = 'activeWorkspaceId'

export const AUTH_CHANGED_EVENT = 'aicopilot-auth-changed'
export const WORKSPACE_CHANGED_EVENT = 'aicopilot-workspace-changed'
export const WORKSPACES_CHANGED_EVENT = 'aicopilot-workspaces-changed'

/**
 * 把跨标签页的 `storage` 事件转成本项目的自定义事件。
 *
 * @returns 卸载函数（测试与热更新时用得上）。
 */
export function installCrossTabSync(target: Window = window): () => void {
  const onStorage = (event: StorageEvent) => {
    // `storageArea` 为 null 的 StorageEvent（手工构造、或历史浏览器）当作我们的处理对象；
    // 明确指向 sessionStorage 的则与登录态无关，直接忽略。
    if (event.storageArea && event.storageArea !== target.localStorage) return

    // key === null 表示 storage.clear()：登录态和工作空间一起没了，
    // 按「认证状态变化」处理 —— 那条路径本来就会清空工作空间并跳走。
    if (event.key === null || (AUTH_STORAGE_KEYS as readonly string[]).includes(event.key)) {
      target.dispatchEvent(new Event(AUTH_CHANGED_EVENT))
      return
    }

    if (event.key === WORKSPACE_STORAGE_KEY) {
      target.dispatchEvent(new CustomEvent(WORKSPACE_CHANGED_EVENT, {
        detail: { workspaceId: event.newValue ?? '' },
      }))
    }
  }

  target.addEventListener('storage', onStorage)
  return () => target.removeEventListener('storage', onStorage)
}
