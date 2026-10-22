package com.aicopilot.knowledge;

import java.util.List;

public interface KnowledgeRetrievalClient {

    /**
     * @param mode vector / keyword / hybrid；传 null 表示用 ai-service 的默认配置
     */
    List<KnowledgeRetrievalChunk> search(Long knowledgeBaseId, String query, int limit, String mode);
}
