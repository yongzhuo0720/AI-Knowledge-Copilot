package com.aicopilot.knowledge;

import java.time.Instant;

public record KnowledgeDocumentResponse(
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

    public KnowledgeDocumentResponse(
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

    public static KnowledgeDocumentResponse from(KnowledgeDocument document) {
        return new KnowledgeDocumentResponse(
                document.id(),
                document.knowledgeBaseId(),
                document.originalFilename(),
                document.objectKey(),
                document.contentType(),
                document.fileSize(),
                document.status(),
                document.processingTaskId(),
                document.createdAt(),
                document.processingFailureReason(),
                document.processingRetryCount()
        );
    }

    public static KnowledgeDocumentResponse from(
            KnowledgeDocument document,
            DocumentProcessingResponse task
    ) {
        return new KnowledgeDocumentResponse(
                document.id(),
                document.knowledgeBaseId(),
                document.originalFilename(),
                document.objectKey(),
                document.contentType(),
                document.fileSize(),
                document.status(),
                document.processingTaskId(),
                document.createdAt(),
                task.failureReason(),
                task.retryCount()
        );
    }
}
