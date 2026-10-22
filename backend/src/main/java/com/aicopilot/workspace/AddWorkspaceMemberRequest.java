package com.aicopilot.workspace;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record AddWorkspaceMemberRequest(
        @NotNull(message = "userId must not be null")
        Long userId,

        // OWNER 只能由「创建工作空间」自动产生，不通过成员接口分配。
        @Pattern(regexp = "ADMIN|MEMBER", message = "role must be ADMIN or MEMBER")
        String role
) {

    public String normalizedRole() {
        return role == null || role.isBlank() ? "MEMBER" : role.trim().toUpperCase();
    }
}
