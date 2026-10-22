import { describe, expect, it } from 'vitest'

import {
  isSupportedDocument,
  SUPPORTED_DOCUMENT_ACCEPT,
  SUPPORTED_DOCUMENT_EXTENSIONS,
} from '../documentFormats'

describe('文档格式白名单', () => {
  it('accept 属性覆盖所有后端支持的扩展名', () => {
    for (const extension of SUPPORTED_DOCUMENT_EXTENSIONS) {
      expect(SUPPORTED_DOCUMENT_ACCEPT.split(',')).toContain(extension)
    }
  })

  it.each(['report.xlsx', 'legacy.xlsm', 'slides.pptx', 'page.html', 'page.htm', 'page.xhtml', 'notes.md', 'GUIDE.PDF'])(
    '接受 %s',
    (filename) => {
      expect(isSupportedDocument(filename)).toBe(true)
    },
  )

  it.each(['malware.exe', 'archive.zip', 'no-extension', 'report.xlsx.exe'])(
    '拒绝 %s',
    (filename) => {
      expect(isSupportedDocument(filename)).toBe(false)
    },
  )
})
