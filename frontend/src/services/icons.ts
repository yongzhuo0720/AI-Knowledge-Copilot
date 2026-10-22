/**
 * 图标统一用 feather 风格（stroke，24x24），不用 emoji。
 * 以字符串返回、template 里 v-html 渲染 —— 内容全部来自本文件的常量，无注入风险。
 */
export function featherIcon(paths: string) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${paths}</svg>`
}

export const SPARK_ICON = featherIcon('<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>')
