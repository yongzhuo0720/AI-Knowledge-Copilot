package com.aicopilot.knowledge;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentProcessingRequest(
        Long knowledgeBaseId,
        String objectKey,
        String filename,
        String contentType,
        boolean replaceExisting
) {

    public DocumentProcessingRequest(Long knowledgeBaseId, String objectKey, String filename, String contentType) {
        this(knowledgeBaseId, objectKey, filename, contentType, false);
    }
}
