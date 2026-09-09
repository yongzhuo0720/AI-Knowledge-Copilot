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
        Instant createdAt
) {
}
