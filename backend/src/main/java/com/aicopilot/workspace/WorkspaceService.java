package com.aicopilot.workspace;

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

    @Transactional(readOnly = true)
    public List<WorkspaceMemberResponse> findMembers(Long workspaceId) {
        return workspaceRepository.findMembers(workspaceId).stream()
                .map(WorkspaceMemberResponse::from)
                .toList();
    }
}
