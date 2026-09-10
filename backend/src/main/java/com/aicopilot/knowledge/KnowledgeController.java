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

    @PostMapping(value = "/{knowledgeBaseId}/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KnowledgeDocumentResponse> uploadDocument(
            @PathVariable Long knowledgeBaseId,
            @RequestPart("file") MultipartFile file
    ) throws java.io.IOException {
        if (file.isEmpty()) {
            throw new com.aicopilot.common.exception.BusinessException("EMPTY_FILE", "file must not be empty");
        }
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        return ApiResponse.success(knowledgeService.uploadDocument(
                knowledgeBaseId,
                file.getOriginalFilename(),
                contentType,
                file.getSize(),
                file.getInputStream()
        ));
    }

    @GetMapping("/{knowledgeBaseId}/documents")
    public ApiResponse<List<KnowledgeDocumentResponse>> findDocuments(@PathVariable Long knowledgeBaseId) {
        return ApiResponse.success(knowledgeService.findDocuments(knowledgeBaseId));
    }
}
