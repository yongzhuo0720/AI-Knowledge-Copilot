package com.aicopilot.knowledge;

import com.aicopilot.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
@Profile("local")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KnowledgeBaseResponse> createKnowledgeBase(
            @Valid @RequestBody CreateKnowledgeBaseRequest request
    ) {
        return ApiResponse.success(knowledgeService.createKnowledgeBase(request));
    }

    @PostMapping("/{knowledgeBaseId}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KnowledgeDocumentResponse> registerDocument(
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody RegisterDocumentRequest request
    ) {
        return ApiResponse.success(knowledgeService.registerDocument(knowledgeBaseId, request));
    }

    @GetMapping("/{knowledgeBaseId}/documents")
    public ApiResponse<List<KnowledgeDocumentResponse>> findDocuments(@PathVariable Long knowledgeBaseId) {
        return ApiResponse.success(knowledgeService.findDocuments(knowledgeBaseId));
    }
}
