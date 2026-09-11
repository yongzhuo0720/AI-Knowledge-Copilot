package com.aicopilot.knowledge;

import com.aicopilot.common.storage.ObjectStorageService;
import com.aicopilot.common.exception.AccessDeniedException;
import com.aicopilot.workspace.WorkspaceRepository;
import com.aicopilot.conversation.ConversationMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

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
    private final KnowledgeRetrievalClient knowledgeRetrievalClient;
    private final KnowledgeAnswerClient knowledgeAnswerClient;
    private final WorkspaceRepository workspaceRepository;

    public KnowledgeService(
            KnowledgeRepository knowledgeRepository,
            DocumentProcessingClient documentProcessingClient,
            ObjectStorageService objectStorageService,
            KnowledgeRetrievalClient knowledgeRetrievalClient,
            KnowledgeAnswerClient knowledgeAnswerClient
    ) {
        this(knowledgeRepository, documentProcessingClient, objectStorageService, knowledgeRetrievalClient,
                knowledgeAnswerClient, null);
    }

    @Autowired
    public KnowledgeService(
            KnowledgeRepository knowledgeRepository,
            DocumentProcessingClient documentProcessingClient,
            ObjectStorageService objectStorageService,
            KnowledgeRetrievalClient knowledgeRetrievalClient,
            KnowledgeAnswerClient knowledgeAnswerClient,
            WorkspaceRepository workspaceRepository
    ) {
        this.knowledgeRepository = knowledgeRepository;
        this.documentProcessingClient = documentProcessingClient;
        this.objectStorageService = objectStorageService;
        this.knowledgeRetrievalClient = knowledgeRetrievalClient;
        this.knowledgeAnswerClient = knowledgeAnswerClient;
        this.workspaceRepository = workspaceRepository;
    }

    @Transactional
    public KnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        return createKnowledgeBase(null, request);
    }

    @Transactional
    public KnowledgeBaseResponse createKnowledgeBase(Long userId, CreateKnowledgeBaseRequest request) {
        authorizeWorkspace(request.workspaceId(), userId);
        KnowledgeBase knowledgeBase = knowledgeRepository.saveKnowledgeBase(new KnowledgeBase(
                null,
                request.workspaceId(),
                request.name().trim(),
                normalizeDescription(request.description()),
                "ACTIVE"
        ));
        return KnowledgeBaseResponse.from(knowledgeBase);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBaseResponse> findKnowledgeBases(Long userId, Long workspaceId) {
        authorizeWorkspace(workspaceId, userId);
        return knowledgeRepository.findKnowledgeBases(workspaceId).stream()
                .map(KnowledgeBaseResponse::from)
                .toList();
    }

    @Transactional
    public KnowledgeDocumentResponse registerDocument(Long knowledgeBaseId, RegisterDocumentRequest request) {
        return registerDocument(null, knowledgeBaseId, request);
    }

    @Transactional
    public KnowledgeDocumentResponse registerDocument(Long userId, Long knowledgeBaseId, RegisterDocumentRequest request) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        KnowledgeDocument document = knowledgeRepository.saveDocument(new KnowledgeDocument(
                null,
                knowledgeBaseId,
                request.originalFilename().trim(),
                request.objectKey().trim(),
                request.contentType().trim(),
                request.fileSize(),
                "UPLOADED",
                null,
                Instant.now()
        ));
        DocumentProcessingResponse task = documentProcessingClient.submit(new DocumentProcessingRequest(
                knowledgeBaseId,
                document.objectKey(),
                document.originalFilename(),
                document.contentType(),
                false
        ));
        knowledgeRepository.updateProcessingStatus(document.id(), task.taskId(), task.status());
        return KnowledgeDocumentResponse.from(withProcessingTask(document, task.taskId(), task.status()), task);
    }

    @Transactional
    public KnowledgeDocumentResponse uploadDocument(
            Long knowledgeBaseId,
            String originalFilename,
            String contentType,
            long fileSize,
            InputStream inputStream
    ) {
        return uploadDocument(null, knowledgeBaseId, originalFilename, contentType, fileSize, inputStream);
    }

    @Transactional
    public KnowledgeDocumentResponse uploadDocument(
            Long userId,
            Long knowledgeBaseId,
            String originalFilename,
            String contentType,
            long fileSize,
            InputStream inputStream
    ) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        String safeFilename = originalFilename == null || originalFilename.isBlank()
                ? "unnamed-file"
                : originalFilename.substring(originalFilename.lastIndexOf('\\') + 1)
                .substring(originalFilename.lastIndexOf('/') + 1)
                .trim();
        String objectKey = "kb/" + knowledgeBaseId + "/" + UUID.randomUUID() + "/" + safeFilename;
        objectStorageService.upload(objectKey, inputStream, fileSize, contentType);
        return registerDocument(userId, knowledgeBaseId, new RegisterDocumentRequest(
                safeFilename,
                objectKey,
                contentType,
                fileSize
        ));
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentResponse> findDocuments(Long knowledgeBaseId) {
        return findDocuments(null, knowledgeBaseId);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentResponse> findDocuments(Long userId, Long knowledgeBaseId) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        return knowledgeRepository.findDocuments(knowledgeBaseId).stream()
                .map(KnowledgeDocumentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<KnowledgeRetrievalChunk> search(Long knowledgeBaseId, KnowledgeSearchRequest request) {
        return search(null, knowledgeBaseId, request);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeRetrievalChunk> search(Long userId, Long knowledgeBaseId, KnowledgeSearchRequest request) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        return knowledgeRetrievalClient.search(knowledgeBaseId, request.query().trim(), request.resolvedLimit());
    }

    @Transactional(readOnly = true)
    public KnowledgeAnswer answer(Long knowledgeBaseId, KnowledgeQuestionRequest request) {
        return answer(null, knowledgeBaseId, request);
    }

    @Transactional(readOnly = true)
    public KnowledgeAnswer answer(Long userId, Long knowledgeBaseId, KnowledgeQuestionRequest request) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        return knowledgeAnswerClient.answer(knowledgeBaseId, request.question().trim());
    }

    @Transactional
    public KnowledgeDocumentResponse refreshDocumentProcessingStatus(Long knowledgeBaseId, Long documentId) {
        return refreshDocumentProcessingStatus(null, knowledgeBaseId, documentId);
    }

    @Transactional
    public KnowledgeDocumentResponse refreshDocumentProcessingStatus(Long userId, Long knowledgeBaseId, Long documentId) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        KnowledgeDocument document = knowledgeRepository.findDocument(knowledgeBaseId, documentId)
                .orElseThrow(() -> new com.aicopilot.common.exception.BusinessException("DOCUMENT_NOT_FOUND", "document not found"));
        if (document.processingTaskId() == null || document.processingTaskId().isBlank()) {
            return KnowledgeDocumentResponse.from(document);
        }
        DocumentProcessingResponse task = documentProcessingClient.findTask(document.processingTaskId());
        knowledgeRepository.updateProcessingStatus(document.id(), document.processingTaskId(), task.status());
        return KnowledgeDocumentResponse.from(withProcessingTask(document, document.processingTaskId(), task.status()), task);
    }

    @Transactional
    public KnowledgeDocumentResponse reparseDocument(Long knowledgeBaseId, Long documentId) {
        return reparseDocument(null, knowledgeBaseId, documentId);
    }

    @Transactional
    public KnowledgeDocumentResponse reparseDocument(Long userId, Long knowledgeBaseId, Long documentId) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        KnowledgeDocument document = findDocument(knowledgeBaseId, documentId);
        DocumentProcessingResponse task = documentProcessingClient.submit(new DocumentProcessingRequest(
                knowledgeBaseId,
                document.objectKey(),
                document.originalFilename(),
                document.contentType(),
                true
        ));
        knowledgeRepository.updateProcessingStatus(document.id(), task.taskId(), task.status());
        return KnowledgeDocumentResponse.from(withProcessingTask(document, task.taskId(), task.status()), task);
    }

    @Transactional
    public KnowledgeDocumentResponse retryDocumentProcessing(Long knowledgeBaseId, Long documentId) {
        return retryDocumentProcessing(null, knowledgeBaseId, documentId);
    }

    @Transactional
    public KnowledgeDocumentResponse retryDocumentProcessing(Long userId, Long knowledgeBaseId, Long documentId) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        KnowledgeDocument document = findDocument(knowledgeBaseId, documentId);
        if (document.processingTaskId() == null || document.processingTaskId().isBlank()) {
            return reparseDocument(userId, knowledgeBaseId, documentId);
        }
        DocumentProcessingResponse task = documentProcessingClient.retry(document.processingTaskId());
        knowledgeRepository.updateProcessingStatus(document.id(), document.processingTaskId(), task.status());
        return KnowledgeDocumentResponse.from(withProcessingTask(document, document.processingTaskId(), task.status()), task);
    }

    private KnowledgeDocument findDocument(Long knowledgeBaseId, Long documentId) {
        return knowledgeRepository.findDocument(knowledgeBaseId, documentId)
                .orElseThrow(() -> new com.aicopilot.common.exception.BusinessException("DOCUMENT_NOT_FOUND", "document not found"));
    }

    private void authorizeWorkspace(Long workspaceId, Long userId) {
        if (workspaceRepository != null && (userId == null || !workspaceRepository.isMember(workspaceId, userId))) {
            throw new AccessDeniedException("user is not a member of this workspace");
        }
    }

    private void authorizeKnowledgeBase(Long knowledgeBaseId, Long userId) {
        if (workspaceRepository == null) {
            return;
        }
        KnowledgeBase knowledgeBase = knowledgeRepository.findKnowledgeBase(knowledgeBaseId)
                .orElseThrow(() -> new AccessDeniedException("user cannot access this knowledge base"));
        authorizeWorkspace(knowledgeBase.workspaceId(), userId);
    }

    public void assertKnowledgeBaseAccess(Long knowledgeBaseId, Long userId) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
    }

    public KnowledgeAnswer answerWithHistory(Long userId, Long knowledgeBaseId, String question,
                                              List<ConversationMessage> history) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        return knowledgeAnswerClient.answer(knowledgeBaseId, question.trim(), history);
    }

    private KnowledgeDocument withProcessingTask(KnowledgeDocument document, String taskId, String status) {
        return new KnowledgeDocument(
                document.id(), document.knowledgeBaseId(), document.originalFilename(), document.objectKey(),
                document.contentType(), document.fileSize(), status, taskId, document.createdAt()
        );
    }

    private String normalizeDescription(String description) {
        return description == null ? null : description.trim();
    }
}
