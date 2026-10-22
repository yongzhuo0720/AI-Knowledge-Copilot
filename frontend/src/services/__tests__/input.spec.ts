import { describe, expect, it } from 'vitest'

import { isSendHotkey } from '../input'

function enterKey(init: Partial<KeyboardEventInit> = {}, keyCode = 0): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { key: 'Enter', ...init })
  // keyCode 不在 KeyboardEventInit 里（规范已废弃），但输入法的回车仍靠它识别。
  Object.defineProperty(event, 'keyCode', { value: keyCode })
  return event
}

describe('isSendHotkey', () => {
  it('普通回车触发发送', () => {
    expect(isSendHotkey(enterKey())).toBe(true)
  })

  it('Shift+Enter 留给换行，不触发发送', () => {
    expect(isSendHotkey(enterKey({ shiftKey: true }))).toBe(false)
  })

  it('输入法选词中的回车不触发发送', () => {
    expect(isSendHotkey(enterKey({ isComposing: true }))).toBe(false)
  })

  it('旧实现里输入法回车以 keyCode 229 出现，同样不触发发送', () => {
    expect(isSendHotkey(enterKey({}, 229))).toBe(false)
  })

  it('非回车按键不触发发送', () => {
    expect(isSendHotkey(new KeyboardEvent('keydown', { key: 'a' }))).toBe(false)
  })
})
