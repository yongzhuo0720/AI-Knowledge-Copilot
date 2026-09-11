package com.aicopilot.knowledge;

import com.aicopilot.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CreateKnowledgeBaseRequest request
    ) {
        return ApiResponse.success(knowledgeService.createKnowledgeBase(userId, request));
    }

    @PostMapping("/{knowledgeBaseId}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KnowledgeDocumentResponse> registerDocument(
            @PathVariable Long knowledgeBaseId,
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody RegisterDocumentRequest request
    ) {
        return ApiResponse.success(knowledgeService.registerDocument(userId, knowledgeBaseId, request));
    }

    @PostMapping(value = "/{knowledgeBaseId}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KnowledgeDocumentResponse> uploadDocument(
            @PathVariable Long knowledgeBaseId,
            @RequestHeader("X-User-Id") Long userId,
            @RequestPart("file") MultipartFile file
    ) throws java.io.IOException {
        if (file.isEmpty()) {
            throw new com.aicopilot.common.exception.BusinessException("EMPTY_FILE", "file must not be empty");
        }
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        return ApiResponse.success(knowledgeService.uploadDocument(
                userId, knowledgeBaseId,
                file.getOriginalFilename(),
                contentType,
                file.getSize(),
                file.getInputStream()
        ));
    }

    @GetMapping("/{knowledgeBaseId}/documents")
    public ApiResponse<List<KnowledgeDocumentResponse>> findDocuments(
            @PathVariable Long knowledgeBaseId,
            @RequestHeader("X-User-Id") Long userId
    ) {
        return ApiResponse.success(knowledgeService.findDocuments(userId, knowledgeBaseId));
    }

    @GetMapping("/{knowledgeBaseId}/documents/{documentId}/processing-status")
    public ApiResponse<KnowledgeDocumentResponse> refreshDocumentProcessingStatus(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long documentId,
            @RequestHeader("X-User-Id") Long userId
    ) {
        return ApiResponse.success(knowledgeService.refreshDocumentProcessingStatus(userId, knowledgeBaseId, documentId));
    }

    @PostMapping("/{knowledgeBaseId}/documents/{documentId}/reparse")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<KnowledgeDocumentResponse> reparseDocument(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long documentId,
            @RequestHeader("X-User-Id") Long userId
    ) {
        return ApiResponse.success(knowledgeService.reparseDocument(userId, knowledgeBaseId, documentId));
    }

    @PostMapping("/{knowledgeBaseId}/documents/{documentId}/processing-retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<KnowledgeDocumentResponse> retryDocumentProcessing(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long documentId,
            @RequestHeader("X-User-Id") Long userId
    ) {
        return ApiResponse.success(knowledgeService.retryDocumentProcessing(userId, knowledgeBaseId, documentId));
    }

    @PostMapping("/{knowledgeBaseId}/search")
    public ApiResponse<List<KnowledgeRetrievalChunk>> search(
            @PathVariable Long knowledgeBaseId,
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody KnowledgeSearchRequest request
    ) {
        return ApiResponse.success(knowledgeService.search(userId, knowledgeBaseId, request));
    }

    @PostMapping("/{knowledgeBaseId}/answer")
    public ApiResponse<KnowledgeAnswer> answer(
            @PathVariable Long knowledgeBaseId,
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody KnowledgeQuestionRequest request
    ) {
        return ApiResponse.success(knowledgeService.answer(userId, knowledgeBaseId, request));
    }
}
