package com.aicopilot.knowledge;

import com.aicopilot.common.storage.ObjectStorageService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Profile("local")
public class KnowledgeService {

    private final KnowledgeRepository knowledgeRepository;
    private final DocumentProcessingClient documentProcessingClient;
    private final ObjectStorageService objectStorageService;

    public KnowledgeService(
            KnowledgeRepository knowledgeRepository,
            DocumentProcessingClient documentProcessingClient,
            ObjectStorageService objectStorageService
    ) {
        this.knowledgeRepository = knowledgeRepository;
        this.documentProcessingClient = documentProcessingClient;
        this.objectStorageService = objectStorageService;
    }

    @Transactional
    public KnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        KnowledgeBase knowledgeBase = knowledgeRepository.saveKnowledgeBase(new KnowledgeBase(
                null,
                request.workspaceId(),
                request.name().trim(),
                normalizeDescription(request.description()),
                "ACTIVE"
        ));
        return KnowledgeBaseResponse.from(knowledgeBase);
    }

    @Transactional
    public KnowledgeDocumentResponse registerDocument(Long knowledgeBaseId, RegisterDocumentRequest request) {
        KnowledgeDocument document = knowledgeRepository.saveDocument(new KnowledgeDocument(
                null,
                knowledgeBaseId,
                request.originalFilename().trim(),
                request.objectKey().trim(),
                request.contentType().trim(),
                request.fileSize(),
                "UPLOADED",
                Instant.now()
        ));
        documentProcessingClient.submit(new DocumentProcessingRequest(
                document.objectKey(),
                document.originalFilename(),
                document.contentType()
        ));
        return KnowledgeDocumentResponse.from(document);
    }

    @Transactional
    public KnowledgeDocumentResponse uploadDocument(
            Long knowledgeBaseId,
            String originalFilename,
            String contentType,
            long fileSize,
            InputStream inputStream
    ) {
        String safeFilename = originalFilename == null || originalFilename.isBlank()
                ? "unnamed-file"
                : originalFilename.substring(originalFilename.lastIndexOf('\\') + 1)
                .substring(originalFilename.lastIndexOf('/') + 1)
                .trim();
        String objectKey = "kb/" + knowledgeBaseId + "/" + UUID.randomUUID() + "/" + safeFilename;
        objectStorageService.upload(objectKey, inputStream, fileSize, contentType);
        return registerDocument(knowledgeBaseId, new RegisterDocumentRequest(
                safeFilename,
                objectKey,
                contentType,
                fileSize
        ));
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentResponse> findDocuments(Long knowledgeBaseId) {
        return knowledgeRepository.findDocuments(knowledgeBaseId).stream()
                .map(KnowledgeDocumentResponse::from)
                .toList();
    }

    private String normalizeDescription(String description) {
        return description == null ? null : description.trim();
    }
}
