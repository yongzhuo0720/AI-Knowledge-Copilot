package com.aicopilot.knowledge;

public interface KnowledgeIndexClient {

    void deleteKnowledgeBaseIndex(Long knowledgeBaseId);

    void deleteDocumentIndex(Long knowledgeBaseId, String objectKey);
}
