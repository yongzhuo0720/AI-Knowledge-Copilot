import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  applyTheme,
  currentTheme,
  initTheme,
  onThemeChange,
  resolveTheme,
  toggleTheme,
} from '../theme'

/** jsdom 没有 matchMedia，用可编程的替身：listener 列表允许测试模拟系统切换。 */
function stubMatchMedia(matches: boolean) {
  const listeners: Array<(event: { matches: boolean }) => void> = []
  const query = {
    matches,
    addEventListener: (_: string, listener: (event: { matches: boolean }) => void) => listeners.push(listener),
    removeEventListener: () => {},
  }
  vi.stubGlobal('matchMedia', vi.fn(() => query))
  return {
    /** 模拟系统偏好变化（返回值同时更新 query.matches）。 */
    flip() {
      query.matches = !query.matches
      for (const listener of listeners) listener({ matches: query.matches })
    },
  }
}

describe('theme service', () => {
  beforeEach(() => {
    localStorage.clear()
    delete document.documentElement.dataset.theme
    vi.unstubAllGlobals()
  })

  it('follows the system preference when no explicit choice was stored', () => {
    stubMatchMedia(true)

    expect(resolveTheme()).toBe('dark')
    initTheme()

    expect(currentTheme()).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
  })

  it('an explicit choice wins over the system preference', () => {
    stubMatchMedia(true)
    localStorage.setItem('themePreference', 'light')

    initTheme()

    expect(currentTheme()).toBe('light')
  })

  it('toggle flips the applied theme and persists the choice', () => {
    stubMatchMedia(false)
    initTheme()

    toggleTheme()

    expect(currentTheme()).toBe('dark')
    expect(localStorage.getItem('themePreference')).toBe('dark')
  })

  it('a user without an explicit choice keeps following system changes', () => {
    const media = stubMatchMedia(false)
    initTheme()
    expect(currentTheme()).toBe('light')

    media.flip()

    expect(currentTheme()).toBe('dark')
    expect(localStorage.getItem('themePreference')).toBeNull()
  })

  it('an explicit choice no longer follows system changes', () => {
    const media = stubMatchMedia(false)
    initTheme()
    toggleTheme() // 显式选择了 dark

    media.flip() // 系统切回 dark 再切走也不影响

    expect(currentTheme()).toBe('dark')
  })

  it('notifies subscribers when the theme changes', () => {
    stubMatchMedia(false)
    const seen: string[] = []
    onThemeChange((theme) => seen.push(theme))

    applyTheme('dark')
    toggleTheme()

    expect(seen).toEqual(['dark', 'light'])
  })
})
