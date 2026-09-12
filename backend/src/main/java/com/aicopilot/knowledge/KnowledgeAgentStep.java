package com.aicopilot.knowledge;

public record KnowledgeAgentStep(
        String tool,
        String query,
        int resultCount
) {
}
