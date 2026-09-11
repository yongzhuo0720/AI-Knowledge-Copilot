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
import jakarta.servlet.http.HttpServletRequest;
import com.aicopilot.user.AuthenticationInterceptor;

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
            HttpServletRequest httpRequest,
            @Valid @RequestBody CreateWorkspaceRequest request
    ) {
        return ApiResponse.success(workspaceService.create(authenticatedUserId(httpRequest), request));
    }

    @GetMapping
    public ApiResponse<List<WorkspaceResponse>> findForUser(HttpServletRequest httpRequest) {
        return ApiResponse.success(workspaceService.findForUser(authenticatedUserId(httpRequest)));
    }

    @PostMapping("/{workspaceId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkspaceMemberResponse> addMember(
            @PathVariable Long workspaceId,
            HttpServletRequest httpRequest,
            @Valid @RequestBody AddWorkspaceMemberRequest request
    ) {
        return ApiResponse.success(workspaceService.addMember(authenticatedUserId(httpRequest), workspaceId, request));
    }

    @GetMapping("/{workspaceId}/members")
    public ApiResponse<List<WorkspaceMemberResponse>> findMembers(
            @PathVariable Long workspaceId,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(workspaceService.findMembers(authenticatedUserId(httpRequest), workspaceId));
    }

    private Long authenticatedUserId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthenticationInterceptor.AUTHENTICATED_USER_ID);
    }
}
