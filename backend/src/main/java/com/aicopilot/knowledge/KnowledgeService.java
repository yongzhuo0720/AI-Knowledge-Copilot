package com.aicopilot.knowledge;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Profile("local")
public class KnowledgeService {

    private final KnowledgeRepository knowledgeRepository;
    private final DocumentProcessingClient documentProcessingClient;

    public KnowledgeService(
            KnowledgeRepository knowledgeRepository,
            DocumentProcessingClient documentProcessingClient
    ) {
        this.knowledgeRepository = knowledgeRepository;
        this.documentProcessingClient = documentProcessingClient;
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
