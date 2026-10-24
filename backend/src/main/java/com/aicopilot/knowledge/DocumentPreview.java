package com.aicopilot.knowledge;

import java.util.List;

/**
 * 单篇文档的结构化预览：按标题链分节返回原文内容。
 * 数据来自 ai-service 重新下载原文后的结构化抽取（与 Agent 的 document_outline 同源）。
 */
public record DocumentPreview(
        String name,
        int sectionCount,
        int blockCount,
        boolean truncated,
        List<DocumentPreviewSection> sections
) {
}
