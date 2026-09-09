package com.aicopilot.workspace;

public record WorkspaceResponse(
        Long id,
        String name,
        Long ownerUserId
) {

    public static WorkspaceResponse from(Workspace workspace) {
        return new WorkspaceResponse(workspace.id(), workspace.name(), workspace.ownerUserId());
    }
}
