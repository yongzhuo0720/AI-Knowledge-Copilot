package com.aicopilot.knowledge;

import java.util.List;

public interface KnowledgeRepository {

    KnowledgeBase saveKnowledgeBase(KnowledgeBase knowledgeBase);

    java.util.Optional<KnowledgeBase> findKnowledgeBase(Long knowledgeBaseId);

    KnowledgeDocument saveDocument(KnowledgeDocument document);

    List<KnowledgeDocument> findDocuments(Long knowledgeBaseId);

    java.util.Optional<KnowledgeDocument> findDocument(Long knowledgeBaseId, Long documentId);

    void updateProcessingStatus(Long documentId, String processingTaskId, String status);
}
