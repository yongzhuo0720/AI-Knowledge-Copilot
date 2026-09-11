package com.aicopilot.knowledge;

public record KnowledgeRetrievalChunk(
        String documentObjectKey,
        String content,
        double score
) {
}
