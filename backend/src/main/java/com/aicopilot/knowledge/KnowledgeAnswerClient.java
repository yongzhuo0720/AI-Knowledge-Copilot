package com.aicopilot.knowledge;

public interface KnowledgeAnswerClient {
    KnowledgeAnswer answer(Long knowledgeBaseId, String question);
}
