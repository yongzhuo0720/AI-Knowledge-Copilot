package com.aicopilot.knowledge;

import java.util.List;

public record KnowledgeAnswer(String answer, List<KnowledgeRetrievalChunk> sources) {
}
