package com.aicopilot.knowledge;

import com.aicopilot.common.api.ApiResponse;
import com.aicopilot.user.AuthenticationInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/documents")
@Profile("local")
public class WorkspaceDocumentController {

    private final KnowledgeService knowledgeService;

    public WorkspaceDocumentController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    public ApiResponse<List<WorkspaceDocumentResponse>> findDocuments(
            @PathVariable Long workspaceId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long knowledgeBaseId,
            HttpServletRequest request
    ) {
        Long userId = (Long) request.getAttribute(AuthenticationInterceptor.AUTHENTICATED_USER_ID);
        return ApiResponse.success(knowledgeService.findWorkspaceDocuments(userId, workspaceId, query, status, knowledgeBaseId));
    }
}
