package com.aicopilot.dashboard;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.aicopilot.common.api.ApiResponse;
import com.aicopilot.user.AuthenticationInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
@Tag(name = "概览统计", description = "工作空间维度的用量与状态统计")
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
