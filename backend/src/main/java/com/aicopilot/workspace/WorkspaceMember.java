package com.aicopilot.workspace;

import java.time.Instant;

public record WorkspaceMember(
        Long workspaceId,
        Long userId,
        String role,
        Instant joinedAt
) {
}
