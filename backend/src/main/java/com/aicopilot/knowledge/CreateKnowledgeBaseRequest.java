package com.aicopilot.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateKnowledgeBaseRequest(
        @NotNull(message = "workspaceId must not be null")
        Long workspaceId,

        @NotBlank(message = "name must not be blank")
        @Size(max = 128, message = "name must be at most 128 characters")
        String name,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description
) {
}
