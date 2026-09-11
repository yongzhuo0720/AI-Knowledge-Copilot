package com.aicopilot.dashboard;

import java.time.Instant;
import java.util.List;

public record WorkspaceOverview(
        Long workspaceId,
        long knowledgeBaseCount,
        long documentCount,
        Long indexedChunkCount,
        long conversationCount,
        long processingTaskCount,
        List<RecentKnowledgeBase> recentKnowledgeBases,
        List<RecentDocument> recentDocuments,
        List<RecentConversation> recentConversations
) {

    public record RecentKnowledgeBase(Long id, String name, String status, Instant updatedAt, long documentCount) {
    }

    public record RecentDocument(Long id, Long knowledgeBaseId, String knowledgeBaseName, String originalFilename,
                                 String contentType, long fileSize, String status, Instant createdAt) {
    }

    public record RecentConversation(Long id, String title, Long knowledgeBaseId, String knowledgeBaseName,
                                     Instant updatedAt) {
    }
}
