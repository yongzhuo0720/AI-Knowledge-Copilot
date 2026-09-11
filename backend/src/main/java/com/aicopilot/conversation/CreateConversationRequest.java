package com.aicopilot.conversation;

import jakarta.validation.constraints.Size;

public record CreateConversationRequest(
        @Size(max = 200, message = "title must be at most 200 characters")
        String title
) {
}
