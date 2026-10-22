package com.aicopilot.workspace;

import com.aicopilot.common.exception.AccessDeniedException;
import com.aicopilot.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    @Transactional
    public WorkspaceResponse create(Long userId, CreateWorkspaceRequest request) {
        if (!userId.equals(request.ownerUserId())) {
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

    @Transactional(readOnly = true)
    public List<WorkspaceResponse> findForUser(Long userId) {
        if (userId == null) {
            throw new AccessDeniedException("authentication is required");
        }
        return workspaceRepository.findByUserId(userId).stream()
                .map(WorkspaceResponse::from)
                .toList();
    }

    /** 仅供 {@link #addMember(Long, Long, AddWorkspaceMemberRequest)} 在已鉴权后复用的内部实现。 */
    private WorkspaceMemberResponse addMember(Long workspaceId, AddWorkspaceMemberRequest request) {
        String role = request.normalizedRole();
        // 成员接口只能分配 ADMIN / MEMBER。允许传 OWNER 等于允许任何 ADMIN 把自己
        // 或同伙提升为所有者，之后再改不掉 —— 所有权只能由创建者天然持有。
        if ("OWNER".equals(role)) {
            throw new BusinessException("OWNER_ROLE_PROTECTED",
                    "workspace ownership cannot be transferred through the member API");
        }
        // 同一条 upsert 也会命中已有 OWNER 行，把真正的所有者降级。
        if (workspaceRepository.isOwner(workspaceId, request.userId())) {
            throw new BusinessException("OWNER_ROLE_PROTECTED",
                    "the workspace owner's role cannot be changed");
        }
        WorkspaceMember member = new WorkspaceMember(workspaceId, request.userId(), role, null);
        workspaceRepository.addMember(member);
        return WorkspaceMemberResponse.from(member);
    }

    @Transactional
    public WorkspaceMemberResponse addMember(Long userId, Long workspaceId, AddWorkspaceMemberRequest request) {
        authorizeOwnerOrAdmin(workspaceId, userId);
        return addMember(workspaceId, request);
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
