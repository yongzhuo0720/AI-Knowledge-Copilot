package com.aicopilot.knowledge;

public record KnowledgeBaseResponse(
        Long id,
        Long workspaceId,
        String name,
        String description,
        String status
) {

    public static KnowledgeBaseResponse from(KnowledgeBase knowledgeBase) {
        return new KnowledgeBaseResponse(
                knowledgeBase.id(),
                knowledgeBase.workspaceId(),
                knowledgeBase.name(),
                knowledgeBase.description(),
                knowledgeBase.status()
        );
    }
}
