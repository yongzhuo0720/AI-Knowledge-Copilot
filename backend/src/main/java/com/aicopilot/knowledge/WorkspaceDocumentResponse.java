package com.aicopilot.knowledge;

import java.time.Instant;

public record WorkspaceDocumentResponse(
        Long id,
        Long knowledgeBaseId,
        String knowledgeBaseName,
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
}
