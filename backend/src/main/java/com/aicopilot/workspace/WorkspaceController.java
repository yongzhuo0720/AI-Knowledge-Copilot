package com.aicopilot.workspace;

import com.aicopilot.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/workspaces")
@Profile("local")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkspaceResponse> create(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CreateWorkspaceRequest request
    ) {
        return ApiResponse.success(workspaceService.create(userId, request));
    }

    @PostMapping("/{workspaceId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkspaceMemberResponse> addMember(
            @PathVariable Long workspaceId,
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody AddWorkspaceMemberRequest request
    ) {
        return ApiResponse.success(workspaceService.addMember(userId, workspaceId, request));
    }

    @GetMapping("/{workspaceId}/members")
    public ApiResponse<List<WorkspaceMemberResponse>> findMembers(
            @PathVariable Long workspaceId,
            @RequestHeader("X-User-Id") Long userId
    ) {
        return ApiResponse.success(workspaceService.findMembers(userId, workspaceId));
    }
}
