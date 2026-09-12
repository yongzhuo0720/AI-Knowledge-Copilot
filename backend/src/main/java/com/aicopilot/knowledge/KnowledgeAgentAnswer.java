package com.aicopilot.knowledge;

import java.util.List;

public record KnowledgeAgentAnswer(
        String answer,
        List<KnowledgeRetrievalChunk> sources,
        List<KnowledgeAgentStep> steps
) {
}
