package com.aicopilot.conversation;

import com.aicopilot.knowledge.KnowledgeRetrievalChunk;

import java.time.Instant;
import java.util.List;

public record ConversationMessage(
        Long id,
        Long sessionId,
        String role,
        String content,
        Instant createdAt,
        List<KnowledgeRetrievalChunk> sources
) {
}
