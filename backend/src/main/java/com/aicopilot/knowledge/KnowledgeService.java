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

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(KnowledgeService.class);

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
    private final KnowledgeIndexClient knowledgeIndexClient;
    private final DocumentPreviewClient documentPreviewClient;

    @Autowired
    public KnowledgeService(
            KnowledgeRepository knowledgeRepository,
            DocumentProcessingClient documentProcessingClient,
            ObjectStorageService objectStorageService,
            KnowledgeRetrievalClient knowledgeRetrievalClient,
            KnowledgeAnswerClient knowledgeAnswerClient,
            WorkspaceRepository workspaceRepository,
            KnowledgeIndexClient knowledgeIndexClient,
            DocumentPreviewClient documentPreviewClient
    ) {
        this.knowledgeRepository = knowledgeRepository;
        this.documentProcessingClient = documentProcessingClient;
        this.objectStorageService = objectStorageService;
        this.knowledgeRetrievalClient = knowledgeRetrievalClient;
        this.knowledgeAnswerClient = knowledgeAnswerClient;
        this.workspaceRepository = workspaceRepository;
        this.knowledgeIndexClient = knowledgeIndexClient;
        this.documentPreviewClient = documentPreviewClient;
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

    /**
     * 登记文档并提交解析任务。**刻意不加 {@code @Transactional}**：submit 是一次
     * 远程 HTTP 调用，放进事务会让数据库连接陪跑整个请求超时窗口。
     * 各语句各自提交，失败路径见 {@link #uploadDocument} 的说明。
     */
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

    /**
     * 上传：先写 MinIO，再登记元数据并提交解析任务。**刻意不加 {@code @Transactional}**
     * （理由与 {@link #deleteDocument} 相同，详见 {@link #registerDocument}）。
     *
     * <p>这里还顺带修掉一个隐藏问题：旧实现整个方法在事务内，若提交解析任务失败
     * 会把元数据一起回滚——但对象已经传进 MinIO，回滚后它就成了**没有任何元数据
     * 指向的孤儿对象**，永远没人能删掉它。现在元数据保留为 UPLOADED 状态，
     * 用户点「重新解析」即可重新提交任务，对象始终有主。
     */
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

    /** 检索调试与问答共用的检索入口。检索本身是 ai-service 的远程调用，不能放进事务。 */
    public List<KnowledgeRetrievalChunk> search(Long userId, Long knowledgeBaseId, KnowledgeSearchRequest request) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        return knowledgeRetrievalClient.search(
                knowledgeBaseId, request.query().trim(), request.resolvedLimit(), request.resolvedMode());
    }

    /** 带引用的直接回答。模型调用是远程 HTTP，见 {@code ConversationService.streamAsk} 的结案注释。 */
    public KnowledgeAnswer answer(Long userId, Long knowledgeBaseId, KnowledgeQuestionRequest request) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        return knowledgeAnswerClient.answer(knowledgeBaseId, request.question().trim());
    }

    /** 刷新解析状态。中间有一次到 ai-service 的远程查询，不放事务。 */
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

    /** 重新解析 = 重新提交解析任务。远程提交不放事务，理由同 {@link #registerDocument}。 */
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

    /** 重试既有任务 / 退化成重新解析。远程调用不放事务，理由同 {@link #registerDocument}。 */
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

    /**
     * 删除单篇文档：先清理派生存储（Milvus 向量、MinIO 对象），再删 MySQL 元数据。
     *
     * <p>顺序必须是「先派生、后元数据」：元数据一删就再也拿不到 object_key，
     * 向量与对象会永久残留。</p>
     *
     * <p><b>向量删除失败必须中止整个删除（fail-closed）</b>：元数据是清理向量的唯一线索，
     * 一旦先删掉元数据，残留在 Milvus 里的内容就既检索得到、又永远无从定位删除。
     * 失败时抛 {@code DOCUMENT_DELETE_FAILED}，文档原样保留，用户重试即可 ——
     * 删除本身是幂等的。</p>
     *
     * <p>对象删除仍是尽力而为：向量已清理后，孤儿对象不可检索、也无人引用，
     * 失败只记日志（与 {@code AdminService.deleteKnowledgeBase} 同一取舍）。</p>
     *
     * <p>刻意不加 {@code @Transactional}：MySQL 侧只有一条自原子的 DELETE，
     * 而向量/对象删除是远程调用，放进事务只会白白占着数据库连接。</p>
     */
    public void deleteDocument(Long userId, Long knowledgeBaseId, Long documentId) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        KnowledgeDocument document = findDocument(knowledgeBaseId, documentId);
        try {
            knowledgeIndexClient.deleteDocumentIndex(knowledgeBaseId, document.objectKey());
        } catch (RuntimeException exception) {
            logger.error("failed to delete vectors for document {} (object key {}); deletion aborted",
                    document.id(), document.objectKey(), exception);
            throw new BusinessException("DOCUMENT_DELETE_FAILED",
                    "failed to delete document vectors; the document was kept, please retry");
        }
        try {
            objectStorageService.delete(document.objectKey());
        } catch (RuntimeException exception) {
            logger.warn("failed to delete object {} for document {}", document.objectKey(), document.id(), exception);
        }
        knowledgeRepository.deleteDocument(document.id());
    }

    private KnowledgeDocument findDocument(Long knowledgeBaseId, Long documentId) {
        return knowledgeRepository.findDocument(knowledgeBaseId, documentId)
                .orElseThrow(() -> new com.aicopilot.common.exception.BusinessException("DOCUMENT_NOT_FOUND", "document not found"));
    }

    /**
     * 单篇文档预览。**刻意不加 {@code @Transactional}**（与 {@link #deleteDocument} 同一取舍）：
     * ai-service 要重新下载原文并做结构化抽取，是一次可能到秒级的远程调用。
     * object_key 取自 MySQL 元数据行——归属校验（行级）在这里完成，
     * 前缀校验（纵深防御）由 ai-service 兜底。
     */
    public DocumentPreview previewDocument(Long userId, Long knowledgeBaseId, Long documentId) {
        authorizeKnowledgeBase(knowledgeBaseId, userId);
        KnowledgeDocument document = findDocument(knowledgeBaseId, documentId);
        return documentPreviewClient.preview(knowledgeBaseId, document.objectKey());
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
