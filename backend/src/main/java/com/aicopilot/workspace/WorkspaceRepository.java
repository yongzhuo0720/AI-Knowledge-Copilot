package com.aicopilot.workspace;

import java.util.List;

public interface WorkspaceRepository {

    Workspace save(Workspace workspace);

    void addMember(WorkspaceMember member);

    List<WorkspaceMember> findMembers(Long workspaceId);
}
