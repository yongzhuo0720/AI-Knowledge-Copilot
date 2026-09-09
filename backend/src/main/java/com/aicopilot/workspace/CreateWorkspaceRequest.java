package com.aicopilot.workspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 128, message = "name must be at most 128 characters")
        String name,

        @NotNull(message = "ownerUserId must not be null")
        Long ownerUserId
) {
}
