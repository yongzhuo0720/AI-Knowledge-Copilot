/** 与后端 KnowledgeService.SUPPORTED_EXTENSIONS、ai-service 解析器保持一致。 */
export const SUPPORTED_DOCUMENT_EXTENSIONS = [
  '.txt', '.md', '.csv', '.tsv',
  '.pdf', '.docx',
  '.xlsx', '.xlsm', '.pptx',
  '.html', '.htm', '.xhtml',
] as const

export const SUPPORTED_DOCUMENT_ACCEPT = SUPPORTED_DOCUMENT_EXTENSIONS.join(',')

export const SUPPORTED_DOCUMENT_LABEL = 'TXT、MD、CSV、TSV、PDF、DOCX、XLSX、XLSM、PPTX、HTML'

export function isSupportedDocument(filename: string): boolean {
  const lower = filename.toLowerCase()
  return SUPPORTED_DOCUMENT_EXTENSIONS.some((extension) => lower.endsWith(extension))
}
