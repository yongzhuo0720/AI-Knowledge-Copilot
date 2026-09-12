package com.aicopilot.conversation;

import com.aicopilot.knowledge.KnowledgeRetrievalChunk;
import com.aicopilot.knowledge.KnowledgeAgentStep;

import java.time.Instant;
import java.util.List;

public record ConversationMessage(
        Long id,
        Long sessionId,
        String role,
        String content,
        Instant createdAt,
        List<KnowledgeRetrievalChunk> sources,
        List<KnowledgeAgentStep> agentSteps
) {

    public ConversationMessage(
            Long id,
            Long sessionId,
            String role,
            String content,
            Instant createdAt,
            List<KnowledgeRetrievalChunk> sources
    ) {
        this(id, sessionId, role, content, createdAt, sources, List.of());
    }
}
