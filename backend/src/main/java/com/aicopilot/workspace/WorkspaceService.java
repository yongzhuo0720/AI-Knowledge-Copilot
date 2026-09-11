package com.aicopilot.workspace;

import com.aicopilot.common.exception.AccessDeniedException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Profile("local")
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    @Transactional
    public WorkspaceResponse create(CreateWorkspaceRequest request) {
        return create(null, request);
    }

    @Transactional
    public WorkspaceResponse create(Long userId, CreateWorkspaceRequest request) {
        if (userId != null && !userId.equals(request.ownerUserId())) {
            throw new AccessDeniedException("workspace owner must match authenticated user");
        }
        Workspace workspace = workspaceRepository.save(
                new Workspace(null, request.name().trim(), request.ownerUserId())
        );
        workspaceRepository.addMember(new WorkspaceMember(
                workspace.id(),
                workspace.ownerUserId(),
                "OWNER",
                null
        ));
        return WorkspaceResponse.from(workspace);
    }

    @Transactional
    public WorkspaceMemberResponse addMember(Long workspaceId, AddWorkspaceMemberRequest request) {
        WorkspaceMember member = new WorkspaceMember(
                workspaceId,
                request.userId(),
                request.normalizedRole(),
                null
        );
        workspaceRepository.addMember(member);
        return WorkspaceMemberResponse.from(member);
    }

    @Transactional
    public WorkspaceMemberResponse addMember(Long userId, Long workspaceId, AddWorkspaceMemberRequest request) {
        authorizeOwnerOrAdmin(workspaceId, userId);
        WorkspaceMember member = new WorkspaceMember(
                workspaceId,
                request.userId(),
                request.normalizedRole(),
                null
        );
        workspaceRepository.addMember(member);
        return WorkspaceMemberResponse.from(member);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> findMembers(Long workspaceId) {
        return workspaceRepository.findMembers(workspaceId).stream()
                .map(WorkspaceMemberResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> findMembers(Long userId, Long workspaceId) {
        authorizeMember(workspaceId, userId);
        return workspaceRepository.findMembers(workspaceId).stream()
                .map(WorkspaceMemberResponse::from)
                .toList();
    }

    private void authorizeMember(Long workspaceId, Long userId) {
        if (workspaceRepository != null && (userId == null || !workspaceRepository.isMember(workspaceId, userId))) {
            throw new AccessDeniedException("user is not a member of this workspace");
        }
    }

    private void authorizeOwnerOrAdmin(Long workspaceId, Long userId) {
        if (workspaceRepository != null && (userId == null || !workspaceRepository.isOwnerOrAdmin(workspaceId, userId))) {
            throw new AccessDeniedException("only workspace owner or admin can manage members");
        }
    }
}
