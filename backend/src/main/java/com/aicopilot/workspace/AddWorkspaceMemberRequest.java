package com.aicopilot.workspace;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record AddWorkspaceMemberRequest(
        @NotNull(message = "userId must not be null")
        Long userId,

        @Pattern(regexp = "OWNER|ADMIN|MEMBER", message = "role must be OWNER, ADMIN or MEMBER")
        String role
) {

    public String normalizedRole() {
        return role == null || role.isBlank() ? "MEMBER" : role.trim().toUpperCase();
    }
}
