export function formatSize(value: number) {
  if (value < 1024 * 1024) return `${Math.max(1, Math.ceil(value / 1024))} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

/**
 * 文件类型徽标的短标签：取 MIME 子类型前 4 位大写（application/pdf → PDF）。
 * 截到 4 位是为了「spreadsheet」这类长子类型不会把窄列撑开。
 */
export function fileType(contentType: string): string {
  return contentType.split('/').pop()?.slice(0, 4).toUpperCase() || 'FILE'
}

/**
 * 对象存储 key 里的文件名（去 MinIO 前缀）。取不到时退回原 key，
 * 界面上宁可显示完整路径也不能显示空字符串。
 */
export function fileName(objectKey: string): string {
  return objectKey.split('/').pop() || objectKey
}
