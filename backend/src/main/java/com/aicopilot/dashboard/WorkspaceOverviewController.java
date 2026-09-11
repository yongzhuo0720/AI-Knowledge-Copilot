package com.aicopilot.dashboard;

import com.aicopilot.common.api.ApiResponse;
import com.aicopilot.user.AuthenticationInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
@Profile("local")
public class WorkspaceOverviewController {

    private final WorkspaceOverviewService overviewService;

    public WorkspaceOverviewController(WorkspaceOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/{workspaceId}/overview")
    public ApiResponse<WorkspaceOverview> overview(@PathVariable Long workspaceId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthenticationInterceptor.AUTHENTICATED_USER_ID);
        return ApiResponse.success(overviewService.findForUser(userId, workspaceId));
    }
}
