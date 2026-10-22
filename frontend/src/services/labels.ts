/** 任务/资源状态显示为中文（数据本身仍是英文枚举）。 */
const STATUS_LABELS: Record<string, string> = {
  COMPLETED: '已完成', PROCESSING: '处理中', ACCEPTED: '已受理', RETRYING: '重试中', FAILED: '失败', ACTIVE: '正常',
}

export const statusLabel = (value: string) => STATUS_LABELS[value] ?? value

/** 检索片段被哪一路召回器命中。缺省按向量召回展示。 */
const RETRIEVER_LABELS: Record<string, string> = { vector: '向量', keyword: '关键词', hybrid: '双路命中' }

export const retrieverLabel = (value?: string | null) => RETRIEVER_LABELS[value ?? 'vector'] ?? '向量'

/** 角色显示为中文（数据本身仍是英文枚举）。 */
const ROLE_LABELS: Record<string, string> = { OWNER: '所有者', ADMIN: '管理员', MEMBER: '成员' }

export const roleLabel = (value: string) => ROLE_LABELS[value] ?? value
