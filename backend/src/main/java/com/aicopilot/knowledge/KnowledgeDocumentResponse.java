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
        Instant createdAt
) {

    public static KnowledgeDocumentResponse from(KnowledgeDocument document) {
        return new KnowledgeDocumentResponse(
                document.id(),
                document.knowledgeBaseId(),
                document.originalFilename(),
                document.objectKey(),
                document.contentType(),
                document.fileSize(),
                document.status(),
                document.createdAt()
        );
    }
}
