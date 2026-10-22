package com.aicopilot.admin;

import java.time.Instant;

public record AdminWorkspaceResponse(
        Long id,
        String name,
        Long ownerUserId,
        String ownerUsername,
        long memberCount,
        long knowledgeBaseCount,
        Instant createdAt
) {
}
