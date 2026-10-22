package com.aicopilot.knowledge;

import com.aicopilot.common.storage.ObjectStorageService;
import com.aicopilot.common.exception.AccessDeniedException;
import com.aicopilot.common.exception.BusinessException;
import com.aicopilot.workspace.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class KnowledgeService {

    /** 与 ai-service 的 document_blocks 解析能力保持一致。 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".txt", ".md", ".csv", ".tsv",
            ".pdf", ".docx",
            ".xlsx", ".xlsm", ".pptx",
            ".html", ".htm", ".xhtml"
    );

    private final KnowledgeRepository knowledgeRepository;
    private final DocumentProcessingClient documentProcessingClient;
    private final ObjectStorageService objectStorageService;
    private final KnowledgeRetrievalClient knowledgeRetrievalClient;
    private final KnowledgeAnswerClient knowledgeAnswerClient;
    private final WorkspaceRepository workspaceRepository;

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
    public KnowledgeDocumentResponse registerDocument(Long userId, Long knowledgeBaseId, RegisterDocumentRequest request) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        String originalFilename = request.originalFilename().trim();
        String objectKey = request.objectKey().trim();
        validateUploadMetadata(knowledgeBaseId, originalFilename, objectKey);
        KnowledgeDocument document = knowledgeRepository.saveDocument(new KnowledgeDocument(
                null,
                knowledgeBaseId,
                originalFilename,
                objectKey,
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
                // 两种分隔符都可能出现，必须先取两者中更靠后的那个位置再截。
                // 先按 '\' 截、再拿原串的 '/' 下标套到结果上，会把 guide.txt 截成 de.txt。
                : originalFilename.substring(
                        Math.max(originalFilename.lastIndexOf('\\'), originalFilename.lastIndexOf('/')) + 1
                ).trim();
        validateExtension(safeFilename);
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
    public List<KnowledgeDocumentResponse> findDocuments(Long userId, Long knowledgeBaseId) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        return knowledgeRepository.findDocuments(knowledgeBaseId).stream()
                .map(KnowledgeDocumentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WorkspaceDocumentResponse> findWorkspaceDocuments(Long userId, Long workspaceId, String query,
                                                                   String status, Long knowledgeBaseId) {
        authorizeWorkspace(workspaceId, userId);
        return knowledgeRepository.findDocumentsForWorkspace(workspaceId, query, status, knowledgeBaseId);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeRetrievalChunk> search(Long userId, Long knowledgeBaseId, KnowledgeSearchRequest request) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        return knowledgeRetrievalClient.search(
                knowledgeBaseId, request.query().trim(), request.resolvedLimit(), request.resolvedMode());
    }

    @Transactional(readOnly = true)
    public KnowledgeAnswer answer(Long userId, Long knowledgeBaseId, KnowledgeQuestionRequest request) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        return knowledgeAnswerClient.answer(knowledgeBaseId, request.question().trim());
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
        if (!workspaceRepository.isMember(workspaceId, userId)) {
            throw new AccessDeniedException("user is not a member of this workspace");
        }
    }

    private void validateUploadMetadata(Long knowledgeBaseId, String originalFilename, String objectKey) {
        validateExtension(originalFilename);
        // objectKey 由客户端传入时不能信任：只允许当前知识库前缀，否则可以把桶里
        // 其他知识库的对象注册进来，再借解析任务读出来。
        String expectedPrefix = "kb/" + knowledgeBaseId + "/";
        if (!objectKey.startsWith(expectedPrefix)
                || objectKey.length() <= expectedPrefix.length()
                || objectKey.contains("..")
                || objectKey.contains("\\")
                || objectKey.contains("//")) {
            throw new AccessDeniedException("object key does not belong to this knowledge base");
        }
    }

    private void validateExtension(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String extension = dot < 0 ? "" : lower.substring(dot);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(
                    "UNSUPPORTED_FILE_TYPE",
                    "unsupported file type; supported: txt, md, csv, tsv, pdf, docx, xlsx, xlsm, pptx, html, htm, xhtml"
            );
        }
    }

    private void authorizeKnowledgeBase(Long knowledgeBaseId, Long userId) {
        KnowledgeBase knowledgeBase = knowledgeRepository.findKnowledgeBase(knowledgeBaseId)
                .orElseThrow(() -> new AccessDeniedException("user cannot access this knowledge base"));
        authorizeWorkspace(knowledgeBase.workspaceId(), userId);
    }

    public void assertKnowledgeBaseAccess(Long knowledgeBaseId, Long userId) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
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
