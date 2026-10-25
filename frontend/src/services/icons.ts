/**
 * 图标统一用 feather 风格（stroke，24x24），不用 emoji。
 * 以字符串返回、template 里 v-html 渲染 —— 内容全部来自本文件的常量，无注入风险。
 */
export function featherIcon(paths: string) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${paths}</svg>`
}

export const SPARK_ICON = featherIcon('<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>')

/** 主题切换：月亮 = 当前浅色可切深色；太阳 = 当前深色可切浅色。 */
export const MOON_ICON = featherIcon('<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>')
export const SUN_ICON = featherIcon('<circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>')
