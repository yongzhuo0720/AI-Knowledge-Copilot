package com.aicopilot.knowledge;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.aicopilot.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import com.aicopilot.user.AuthenticationInterceptor;
import com.aicopilot.common.ratelimit.RateLimitProperties;
import com.aicopilot.common.ratelimit.RateLimiter;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
@Tag(name = "知识库与问答", description = "知识库管理、文档上传、检索、问答与 Agent 任务")
public class KnowledgeController {

    /** 检索也要调用 Embedding 接口，和问答共用一个「按 token 计费」的额度。 */
    private static final String ASK_BUCKET = "ask";
    private static final String UPLOAD_BUCKET = "upload";

    private final KnowledgeService knowledgeService;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public KnowledgeController(
            KnowledgeService knowledgeService,
            RateLimiter rateLimiter,
            RateLimitProperties rateLimitProperties
    ) {
        this.knowledgeService = knowledgeService;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    @GetMapping
    public ApiResponse<List<KnowledgeBaseResponse>> findKnowledgeBases(
            @RequestParam Long workspaceId,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(knowledgeService.findKnowledgeBases(authenticatedUserId(httpRequest), workspaceId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KnowledgeBaseResponse> createKnowledgeBase(
            HttpServletRequest httpRequest,
            @Valid @RequestBody CreateKnowledgeBaseRequest request
    ) {
        return ApiResponse.success(knowledgeService.createKnowledgeBase(authenticatedUserId(httpRequest), request));
    }

    @PostMapping(value = "/{knowledgeBaseId}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KnowledgeDocumentResponse> uploadDocument(
            @PathVariable Long knowledgeBaseId,
            HttpServletRequest httpRequest,
            @RequestPart("file") MultipartFile file
    ) throws java.io.IOException {
        if (file.isEmpty()) {
            throw new com.aicopilot.common.exception.BusinessException("EMPTY_FILE", "file must not be empty");
        }
        Long userId = authenticatedUserId(httpRequest);
        consumeQuota(UPLOAD_BUCKET, userId, rateLimitProperties.getUpload());
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
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(knowledgeService.findDocuments(authenticatedUserId(httpRequest), knowledgeBaseId));
    }

    @GetMapping("/{knowledgeBaseId}/documents/{documentId}/processing-status")
    public ApiResponse<KnowledgeDocumentResponse> refreshDocumentProcessingStatus(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long documentId,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(knowledgeService.refreshDocumentProcessingStatus(authenticatedUserId(httpRequest), knowledgeBaseId, documentId));
    }

    @PostMapping("/{knowledgeBaseId}/documents/{documentId}/reparse")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<KnowledgeDocumentResponse> reparseDocument(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long documentId,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(knowledgeService.reparseDocument(authenticatedUserId(httpRequest), knowledgeBaseId, documentId));
    }

    @PostMapping("/{knowledgeBaseId}/documents/{documentId}/processing-retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<KnowledgeDocumentResponse> retryDocumentProcessing(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long documentId,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(knowledgeService.retryDocumentProcessing(authenticatedUserId(httpRequest), knowledgeBaseId, documentId));
    }

    @PostMapping("/{knowledgeBaseId}/search")
    public ApiResponse<List<KnowledgeRetrievalChunk>> search(
            @PathVariable Long knowledgeBaseId,
            HttpServletRequest httpRequest,
            @Valid @RequestBody KnowledgeSearchRequest request
    ) {
        Long userId = authenticatedUserId(httpRequest);
        consumeQuota(ASK_BUCKET, userId, rateLimitProperties.getAsk());
        return ApiResponse.success(knowledgeService.search(userId, knowledgeBaseId, request));
    }

    @PostMapping("/{knowledgeBaseId}/answer")
    public ApiResponse<KnowledgeAnswer> answer(
            @PathVariable Long knowledgeBaseId,
            HttpServletRequest httpRequest,
            @Valid @RequestBody KnowledgeQuestionRequest request
    ) {
        Long userId = authenticatedUserId(httpRequest);
        consumeQuota(ASK_BUCKET, userId, rateLimitProperties.getAsk());
        return ApiResponse.success(knowledgeService.answer(userId, knowledgeBaseId, request));
    }

    private Long authenticatedUserId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthenticationInterceptor.AUTHENTICATED_USER_ID);
    }

    /**
     * 在 Controller 里扣配额，保证「未授权 / 参数错误」的请求也能先被拦住，
     * 同时不把限流这种横切逻辑沉到 Service 里。
     */
    private void consumeQuota(String bucket, Long userId, RateLimitProperties.Policy policy) {
        rateLimiter.consume(bucket, String.valueOf(userId), policy);
    }
}
