import { describe, expect, it } from 'vitest'

import { stageLabel, statusLabel } from '../labels'

describe('stageLabel', () => {
  it('translates known pipeline stages', () => {
    expect(stageLabel('claimed')).toBe('已受理')
    expect(stageLabel('downloaded')).toBe('已下载原文')
    expect(stageLabel('chunked')).toBe('已完成切片')
    expect(stageLabel('embedding')).toBe('正在生成向量')
  })

  it('returns null for missing stages so callers can fall back to status text', () => {
    // 终态/重启恢复中的任务没有 stage，界面必须退回 statusLabel 而不是显示 "null"
    expect(stageLabel(null)).toBeNull()
    expect(stageLabel(undefined)).toBeNull()
    expect(stageLabel('')).toBeNull()
  })

  it('passes unknown stage keys through instead of hiding them', () => {
    // ai-service 新增阶段而前端没跟上时，显示原始键比显示成「无阶段」更容易发现失配
    expect(stageLabel('indexing_v2')).toBe('indexing_v2')
  })
})

describe('statusLabel', () => {
  it('falls back to the raw enum for unknown statuses', () => {
    expect(statusLabel('ACTIVE')).toBe('正常')
    expect(statusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })
})
