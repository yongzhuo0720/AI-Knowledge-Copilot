/**
 * 主题（浅色 / 暗色）解析与应用。
 *
 * 生效机制：把解析结果写到 `<html data-theme="light|dark">` 上，样式全在
 * styles.css 的 `:root[data-theme='dark']` 覆盖块里翻转——本模块只负责「现在是哪个」。
 *
 * 默认跟随系统；用户点过切换后记住显式选择（localStorage），系统再变也不跟。
 * 首帧之前由 index.html 的内联脚本做同样一次解析，避免刷新时闪一下浅色；
 * 这里在应用挂载后补系统偏好监听（仅对「没做过显式选择」的用户生效）。
 */

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'themePreference'
const listeners = new Set<(theme: Theme) => void>()

function mediaQuery(): MediaQueryList | null {
  return typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null
}

function systemTheme(): Theme {
  return mediaQuery()?.matches ? 'dark' : 'light'
}

function storedTheme(): Theme | null {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return stored === 'light' || stored === 'dark' ? stored : null
  } catch {
    return null
  }
}

/** 解析当前应生效的主题：显式选择优先，否则跟系统。 */
export function resolveTheme(): Theme {
  return storedTheme() ?? systemTheme()
}

/** 把主题写到文档根元素上并通知订阅者。幂等。 */
export function applyTheme(theme: Theme): void {
  if (typeof document === 'undefined') return
  document.documentElement.dataset.theme = theme
  for (const listener of listeners) listener(theme)
}

/** 当前实际生效的主题（读根元素，读不到时按解析规则兜底）。 */
export function currentTheme(): Theme {
  if (typeof document !== 'undefined') {
    const applied = document.documentElement.dataset.theme
    if (applied === 'light' || applied === 'dark') return applied
  }
  return resolveTheme()
}

/** 切换到当前主题的另一边，并作为显式选择记住。 */
export function toggleTheme(): Theme {
  const next: Theme = currentTheme() === 'dark' ? 'light' : 'dark'
  try {
    localStorage.setItem(STORAGE_KEY, next)
  } catch {
    // localStorage 被禁用时主题仍能切换，只是刷新后回到跟随系统
  }
  applyTheme(next)
  return next
}

/**
 * 应用启动时调用：应用解析结果，并为「跟随系统」的用户注册偏好监听——
 * 系统切到深色时页面实时跟上。做过显式选择的用户不受系统变化影响。
 * 幂等，重复调用不会叠加监听器。
 */
export function initTheme(): Theme {
  applyTheme(resolveTheme())
  const media = mediaQuery()
  if (media) {
    media.addEventListener('change', () => {
      if (storedTheme() === null) applyTheme(systemTheme())
    })
  }
  return currentTheme()
}

/** 订阅主题变化（App.vue 的切换图标靠它保持响应）。返回取消订阅函数。 */
export function onThemeChange(listener: (theme: Theme) => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}
