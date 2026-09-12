package com.aicopilot.knowledge;

import com.aicopilot.conversation.ConversationMessage;

import java.util.List;

public interface KnowledgeAgentClient {

    KnowledgeAgentAnswer run(Long knowledgeBaseId, String question, List<ConversationMessage> history);
}
