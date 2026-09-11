package com.aicopilot.workspace;

import java.util.List;

public interface WorkspaceRepository {

    Workspace save(Workspace workspace);

    List<Workspace> findByUserId(Long userId);

    void addMember(WorkspaceMember member);

    List<WorkspaceMember> findMembers(Long workspaceId);

    boolean isMember(Long workspaceId, Long userId);

    boolean isOwnerOrAdmin(Long workspaceId, Long userId);
}
