package com.aicopilot.knowledge;

public record KnowledgeBase(
        Long id,
        Long workspaceId,
        String name,
        String description,
        String status
) {
}
