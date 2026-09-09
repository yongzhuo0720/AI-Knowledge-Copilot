package com.aicopilot.workspace;

import java.time.Instant;

public record WorkspaceMemberResponse(
        Long workspaceId,
        Long userId,
        String role,
        Instant joinedAt
) {

    public static WorkspaceMemberResponse from(WorkspaceMember member) {
        return new WorkspaceMemberResponse(
                member.workspaceId(),
                member.userId(),
                member.role(),
                member.joinedAt()
        );
    }
}
