/**
 * 「回车发送」的统一判定。
 *
 * Chat / Agent / Retrieval 三个输入框曾各自复制同一段 keydown 判断，
 * 修一处漏一处只是时间问题，所以收敛到这里。
 */

/**
 * 这一次按键是否应当触发「发送」。
 *
 * 只接受不带 Shift 的 Enter（Shift+Enter 留给换行）；同时必须排除输入法：
 * `isComposing` 为 true 说明这次回车是在选词，`keyCode === 229` 是同一事件
 * 在旧实现里的别名 —— 不挡住它，每输入一个中文词就可能把半句话发出去。
 */
export function isSendHotkey(event: KeyboardEvent): boolean {
  if (event.key !== 'Enter' || event.shiftKey) return false
  if (event.isComposing || event.keyCode === 229) return false
  return true
}
