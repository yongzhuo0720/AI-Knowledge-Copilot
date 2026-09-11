package com.aicopilot.knowledge;

import com.aicopilot.conversation.ConversationMessage;

import java.util.List;

public interface KnowledgeAnswerClient {
    KnowledgeAnswer answer(Long knowledgeBaseId, String question, List<ConversationMessage> history);

    default KnowledgeAnswer answer(Long knowledgeBaseId, String question) {
        return answer(knowledgeBaseId, question, List.of());
    }
}
