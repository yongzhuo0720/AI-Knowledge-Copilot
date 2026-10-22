package com.aicopilot.admin;

import java.time.Instant;

public record AdminKnowledgeBaseResponse(
        Long id,
        String name,
        String description,
        String status,
        Long workspaceId,
        String workspaceName,
        long documentCount,
        long conversationCount,
        Instant createdAt
) {
}
