package com.aicopilot.knowledge;

import java.util.List;

public interface KnowledgeRepository {

    KnowledgeBase saveKnowledgeBase(KnowledgeBase knowledgeBase);

    KnowledgeDocument saveDocument(KnowledgeDocument document);

    List<KnowledgeDocument> findDocuments(Long knowledgeBaseId);
}
