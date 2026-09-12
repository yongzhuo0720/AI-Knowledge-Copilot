package com.aicopilot.knowledge;

import com.aicopilot.conversation.ConversationMessage;

import java.util.List;
import java.util.function.Consumer;

public interface KnowledgeAnswerClient {
    KnowledgeAnswer answer(Long knowledgeBaseId, String question, List<ConversationMessage> history);

    default KnowledgeAnswer stream(Long knowledgeBaseId, String question, List<ConversationMessage> history,
                                   Consumer<String> onDelta) {
        KnowledgeAnswer answer = answer(knowledgeBaseId, question, history);
        onDelta.accept(answer.answer());
        return answer;
    }

    default KnowledgeAnswer answer(Long knowledgeBaseId, String question) {
        return answer(knowledgeBaseId, question, List.of());
    }
}
