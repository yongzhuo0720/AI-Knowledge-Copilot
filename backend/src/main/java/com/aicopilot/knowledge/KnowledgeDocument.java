package com.aicopilot.knowledge;

import java.time.Instant;

public record KnowledgeDocument(
        Long id,
        Long knowledgeBaseId,
        String originalFilename,
        String objectKey,
        String contentType,
        long fileSize,
        String status,
        String processingTaskId,
        Instant createdAt,
        String processingFailureReason,
        int processingRetryCount
) {

    public KnowledgeDocument(
            Long id,
            Long knowledgeBaseId,
            String originalFilename,
            String objectKey,
            String contentType,
            long fileSize,
            String status,
            String processingTaskId,
            Instant createdAt
    ) {
        this(id, knowledgeBaseId, originalFilename, objectKey, contentType, fileSize, status,
                processingTaskId, createdAt, null, 0);
    }
}
