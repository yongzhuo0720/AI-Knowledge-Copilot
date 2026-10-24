package com.aicopilot.knowledge;

/**
 * 文档预览的 AI 能力接口。一个能力 = 一个函数式接口 + 一个 lambda {@code @Bean}
 * （见 {@code AiServiceConfiguration}），真正读取原文与结构化抽取在 Python 侧完成。
 */
public interface DocumentPreviewClient {
    DocumentPreview preview(Long knowledgeBaseId, String objectKey);
}
