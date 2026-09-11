package com.aicopilot.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeQuestionRequest(@NotBlank @Size(max = 1000) String question) {
}
