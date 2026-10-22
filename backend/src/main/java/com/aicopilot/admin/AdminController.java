package com.aicopilot.admin;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.aicopilot.common.api.ApiResponse;
import com.aicopilot.user.AuthenticationInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "后台管理", description = "面向系统管理员：账号/工作空间/知识库的全局管理与级联删除")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/overview")
    public ApiResponse<AdminOverviewResponse> overview(HttpServletRequest request) {
        return ApiResponse.success(adminService.overview(operatorId(request)));
    }

    @GetMapping("/users")
    public ApiResponse<List<AdminUserResponse>> findUsers(
            HttpServletRequest request,
            @RequestParam(name = "query", required = false) String query
    ) {
        return ApiResponse.success(adminService.findUsers(operatorId(request), query));
    }

    @PatchMapping("/users/{userId}/status")
    public ApiResponse<AdminUserResponse> updateUserStatus(
            @PathVariable Long userId,
            HttpServletRequest request,
            @Valid @RequestBody UpdateUserStatusRequest body
    ) {
        return ApiResponse.success(
                adminService.updateUserStatus(operatorId(request), userId, body.normalizedStatus()));
    }

    @PatchMapping("/users/{userId}/admin")
    public ApiResponse<AdminUserResponse> updateUserAdmin(
            @PathVariable Long userId,
            HttpServletRequest request,
            @Valid @RequestBody UpdateUserAdminRequest body
    ) {
        return ApiResponse.success(
                adminService.updateUserAdmin(operatorId(request), userId, body.isAdmin()));
    }

    @DeleteMapping("/users/{userId}")
    public ApiResponse<Void> deleteUser(@PathVariable Long userId, HttpServletRequest request) {
        adminService.deleteUser(operatorId(request), userId);
        return ApiResponse.success(null);
    }

    @GetMapping("/workspaces")
    public ApiResponse<List<AdminWorkspaceResponse>> findWorkspaces(
            HttpServletRequest request,
            @RequestParam(name = "query", required = false) String query
    ) {
        return ApiResponse.success(adminService.findWorkspaces(operatorId(request), query));
    }

    @DeleteMapping("/workspaces/{workspaceId}")
    public ApiResponse<Void> deleteWorkspace(@PathVariable Long workspaceId, HttpServletRequest request) {
        adminService.deleteWorkspace(operatorId(request), workspaceId);
        return ApiResponse.success(null);
    }

    @GetMapping("/knowledge-bases")
    public ApiResponse<List<AdminKnowledgeBaseResponse>> findKnowledgeBases(
            HttpServletRequest request,
            @RequestParam(name = "query", required = false) String query
    ) {
        return ApiResponse.success(adminService.findKnowledgeBases(operatorId(request), query));
    }

    @DeleteMapping("/knowledge-bases/{knowledgeBaseId}")
    public ApiResponse<Void> deleteKnowledgeBase(@PathVariable Long knowledgeBaseId, HttpServletRequest request) {
        adminService.deleteKnowledgeBase(operatorId(request), knowledgeBaseId);
        return ApiResponse.success(null);
    }

    private Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthenticationInterceptor.AUTHENTICATED_USER_ID);
    }
}
