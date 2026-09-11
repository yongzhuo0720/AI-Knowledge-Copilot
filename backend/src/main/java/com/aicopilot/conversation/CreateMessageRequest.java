package com.aicopilot.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMessageRequest(
        @NotBlank(message = "question must not be blank")
        @Size(max = 1000, message = "question must be at most 1000 characters")
        String question
) {
}
