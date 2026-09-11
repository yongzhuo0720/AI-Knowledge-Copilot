package com.aicopilot.conversation;

import java.time.Instant;

public record ConversationSession(
        Long id,
        Long knowledgeBaseId,
        Long userId,
        String title,
        Instant createdAt,
        Instant updatedAt
) {
}
