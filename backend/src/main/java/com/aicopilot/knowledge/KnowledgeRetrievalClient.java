package com.aicopilot.knowledge;

import java.util.List;

public interface KnowledgeRetrievalClient {

    List<KnowledgeRetrievalChunk> search(Long knowledgeBaseId, String query, int limit);
}
