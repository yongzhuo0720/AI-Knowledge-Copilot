package com.aicopilot.knowledge;

import com.aicopilot.common.storage.ObjectStorageService;
import com.aicopilot.common.exception.AccessDeniedException;
import com.aicopilot.common.exception.BusinessException;
import com.aicopilot.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"kb/21/private.pdf", "kb/200/private.pdf", "private.pdf"})
    void rejectsRegistrationOfObjectsOutsideTheAuthorizedKnowledgeBase(String objectKey) {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        DocumentProcessingClient processingClient = mock(DocumentProcessingClient.class);
        when(repository.findKnowledgeBase(20L)).thenReturn(Optional.of(
                new KnowledgeBase(20L, 10L, "资料", null, "ACTIVE")));
        when(workspaceRepository.isMember(10L, 7L)).thenReturn(true);
        KnowledgeService service = new KnowledgeService(repository, processingClient,
                mock(ObjectStorageService.class), mock(KnowledgeRetrievalClient.class),
                mock(KnowledgeAnswerClient.class), workspaceRepository, mock(KnowledgeIndexClient.class), mock(DocumentPreviewClient.class));

        assertThatThrownBy(() -> service.registerDocument(7L, 20L,
                new RegisterDocumentRequest("private.pdf", objectKey, "application/pdf", 1024L)))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(processingClient);
        verify(repository, org.mockito.Mockito.never()).saveDocument(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"guide.txt", "folder/guide.txt", "C:\\folder\\guide.txt", "C:\\folder/sub/guide.txt", "folder/sub\\guide.txt"})
    void uploadsOnlyTheBasenameWhenFilenameIncludesDirectories(String filename) {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        DocumentProcessingClient processingClient = mock(DocumentProcessingClient.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        when(repository.saveDocument(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            KnowledgeDocument document = invocation.getArgument(0);
            return new KnowledgeDocument(30L, document.knowledgeBaseId(), document.originalFilename(),
                    document.objectKey(), document.contentType(), document.fileSize(), document.status(),
                    document.processingTaskId(), document.createdAt());
        });
        when(processingClient.submit(any(DocumentProcessingRequest.class)))
                .thenReturn(new DocumentProcessingResponse("task-1", "", "ACCEPTED"));
        KnowledgeService service = new KnowledgeService(repository, processingClient, storageService,
                mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class),
                authorize(repository, 20L, 10L, 7L), mock(KnowledgeIndexClient.class), mock(DocumentPreviewClient.class));
        ByteArrayInputStream inputStream = new ByteArrayInputStream(new byte[] {1});

        KnowledgeDocumentResponse response = service.uploadDocument(7L, 20L, filename, "text/plain", 1L, inputStream);

        assertThat(response.originalFilename()).isEqualTo("guide.txt");
        verify(storageService).upload(argThat(key -> key.startsWith("kb/20/") && key.endsWith("/guide.txt")),
                eq(inputStream), eq(1L), eq("text/plain"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"report.xlsx", "legacy.xlsm", "slides.pptx", "page.html", "page.htm", "page.xhtml", "notes.md"})
    void uploadsEverySupportedDocumentFormat(String filename) {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        DocumentProcessingClient processingClient = mock(DocumentProcessingClient.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        when(repository.saveDocument(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            KnowledgeDocument document = invocation.getArgument(0);
            return new KnowledgeDocument(30L, document.knowledgeBaseId(), document.originalFilename(),
                    document.objectKey(), document.contentType(), document.fileSize(), document.status(),
                    document.processingTaskId(), document.createdAt());
        });
        when(processingClient.submit(any(DocumentProcessingRequest.class)))
                .thenReturn(new DocumentProcessingResponse("task-1", "", "ACCEPTED"));
        KnowledgeService service = new KnowledgeService(repository, processingClient, storageService,
                mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class),
                authorize(repository, 20L, 10L, 7L), mock(KnowledgeIndexClient.class), mock(DocumentPreviewClient.class));

        KnowledgeDocumentResponse response = service.uploadDocument(
                7L, 20L, filename, "application/octet-stream", 1L, new ByteArrayInputStream(new byte[] {1}));

        assertThat(response.originalFilename()).isEqualTo(filename);
        verify(storageService).upload(argThat(key -> key.startsWith("kb/20/") && key.endsWith("/" + filename)),
                any(), eq(1L), eq("application/octet-stream"));
    }

    @Test
    void rejectsUnsupportedFileTypeBeforeStoringTheObject() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        DocumentProcessingClient processingClient = mock(DocumentProcessingClient.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        KnowledgeService service = new KnowledgeService(repository, processingClient, storageService,
                mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class),
                authorize(repository, 20L, 10L, 7L), mock(KnowledgeIndexClient.class), mock(DocumentPreviewClient.class));

        assertThatThrownBy(() -> service.uploadDocument(
                7L, 20L, "malware.exe", "application/octet-stream", 1L, new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("UNSUPPORTED_FILE_TYPE"));

        verifyNoInteractions(storageService, processingClient);
        verify(repository, org.mockito.Mockito.never()).saveDocument(any());
    }

    @Test
    void createsKnowledgeBaseWithNormalizedFields() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeBase saved = new KnowledgeBase(20L, 10L, "研发资料", "架构文档", "ACTIVE");
        when(repository.saveKnowledgeBase(any(KnowledgeBase.class))).thenReturn(saved);

        KnowledgeBaseResponse response = new KnowledgeService(repository, mock(DocumentProcessingClient.class), mock(ObjectStorageService.class), mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class), authorize(repository, 10L, 10L, 7L), mock(KnowledgeIndexClient.class), mock(DocumentPreviewClient.class))
                .createKnowledgeBase(7L, new CreateKnowledgeBaseRequest(10L, " 研发资料 ", " 架构文档 "));

        assertThat(response).isEqualTo(new KnowledgeBaseResponse(20L, 10L, "研发资料", "架构文档", "ACTIVE"));
        verify(repository).saveKnowledgeBase(new KnowledgeBase( null, 10L, "研发资料", "架构文档", "ACTIVE"));
    }

    @Test
    void registersDocumentWithUploadedStatus() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        DocumentProcessingClient processingClient = mock(DocumentProcessingClient.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        KnowledgeDocument saved = new KnowledgeDocument(
                30L, 20L, "guide.pdf", "kb/20/guide.pdf", "application/pdf", 2048L, "UPLOADED", null, Instant.now()
        );
        when(repository.saveDocument(any(KnowledgeDocument.class))).thenReturn(saved);
        when(processingClient.submit(any(DocumentProcessingRequest.class)))
                .thenReturn(new DocumentProcessingResponse("task-1", "kb/20/guide.pdf", "ACCEPTED"));

        KnowledgeDocumentResponse response = new KnowledgeService(repository, processingClient, storageService, mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class), authorize(repository, 20L, 10L, 7L), mock(KnowledgeIndexClient.class), mock(DocumentPreviewClient.class))
                .registerDocument(7L, 20L, new RegisterDocumentRequest(
                        " guide.pdf ", " kb/20/guide.pdf ", " application/pdf ", 2048L
                ));

        assertThat(response.id()).isEqualTo(30L);
        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.processingTaskId()).isEqualTo("task-1");
        verify(repository).saveDocument(any(KnowledgeDocument.class));
        verify(processingClient).submit(new DocumentProcessingRequest(
                20L,
                "kb/20/guide.pdf", "guide.pdf", "application/pdf"
        ));
        verify(repository).updateProcessingStatus(30L, "task-1", "ACCEPTED");
    }

    @Test
    void listsDocuments() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        Instant createdAt = Instant.parse("2026-09-09T00:00:00Z");
        when(repository.findDocuments(20L)).thenReturn(List.of(
                new KnowledgeDocument(30L, 20L, "guide.pdf", "kb/20/guide.pdf", "application/pdf", 2048L, "UPLOADED", "task-1", createdAt)
        ));

        List<KnowledgeDocumentResponse> response = new KnowledgeService(repository, mock(DocumentProcessingClient.class), mock(ObjectStorageService.class), mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class), authorize(repository, 20L, 10L, 7L), mock(KnowledgeIndexClient.class), mock(DocumentPreviewClient.class))
                .findDocuments(7L, 20L);

        assertThat(response).containsExactly(new KnowledgeDocumentResponse(
                30L, 20L, "guide.pdf", "kb/20/guide.pdf", "application/pdf", 2048L, "UPLOADED", "task-1", createdAt
        ));
    }

    @Test
    void refreshesDocumentProcessingStatus() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        DocumentProcessingClient processingClient = mock(DocumentProcessingClient.class);
        Instant createdAt = Instant.parse("2026-09-09T00:00:00Z");
        KnowledgeDocument document = new KnowledgeDocument(
                30L, 20L, "guide.pdf", "kb/20/guide.pdf", "application/pdf", 2048L, "ACCEPTED", "task-1", createdAt
        );
        when(repository.findDocument(20L, 30L)).thenReturn(java.util.Optional.of(document));
        when(processingClient.findTask("task-1"))
                .thenReturn(new DocumentProcessingResponse("task-1", "kb/20/guide.pdf", "PROCESSING"));

        KnowledgeDocumentResponse response = new KnowledgeService(repository, processingClient, mock(ObjectStorageService.class), mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class), authorize(repository, 20L, 10L, 7L), mock(KnowledgeIndexClient.class), mock(DocumentPreviewClient.class))
                .refreshDocumentProcessingStatus(7L, 20L, 30L);

        assertThat(response.status()).isEqualTo("PROCESSING");
        verify(repository).updateProcessingStatus(30L, "task-1", "PROCESSING");
    }

    @Test
    void reparsesDocumentWithReplacementFlag() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        DocumentProcessingClient processingClient = mock(DocumentProcessingClient.class);
        Instant createdAt = Instant.parse("2026-09-09T00:00:00Z");
        KnowledgeDocument document = new KnowledgeDocument(
                30L, 20L, "guide.pdf", "kb/20/guide.pdf", "application/pdf", 2048L, "COMPLETED", "task-1", createdAt
        );
        when(repository.findDocument(20L, 30L)).thenReturn(java.util.Optional.of(document));
        when(processingClient.submit(any(DocumentProcessingRequest.class)))
                .thenReturn(new DocumentProcessingResponse("task-2", "kb/20/guide.pdf", "ACCEPTED"));

        KnowledgeDocumentResponse response = new KnowledgeService(repository, processingClient, mock(ObjectStorageService.class), mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class), authorize(repository, 20L, 10L, 7L), mock(KnowledgeIndexClient.class), mock(DocumentPreviewClient.class))
                .reparseDocument(7L, 20L, 30L);

        assertThat(response.status()).isEqualTo("ACCEPTED");
        verify(processingClient).submit(new DocumentProcessingRequest(
                20L, "kb/20/guide.pdf", "guide.pdf", "application/pdf", true
        ));
        verify(repository).updateProcessingStatus(30L, "task-2", "ACCEPTED");
    }

    @Test
    void retriesExistingFailedProcessingTask() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        DocumentProcessingClient processingClient = mock(DocumentProcessingClient.class);
        Instant createdAt = Instant.parse("2026-09-09T00:00:00Z");
        KnowledgeDocument document = new KnowledgeDocument(
                30L, 20L, "guide.pdf", "kb/20/guide.pdf", "application/pdf", 2048L, "FAILED", "task-1", createdAt
        );
        when(repository.findDocument(20L, 30L)).thenReturn(java.util.Optional.of(document));
        when(processingClient.retry("task-1"))
                .thenReturn(new DocumentProcessingResponse("task-1", "kb/20/guide.pdf", "ACCEPTED", "", 0));

        KnowledgeDocumentResponse response = new KnowledgeService(repository, processingClient, mock(ObjectStorageService.class), mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class), authorize(repository, 20L, 10L, 7L), mock(KnowledgeIndexClient.class), mock(DocumentPreviewClient.class))
                .retryDocumentProcessing(7L, 20L, 30L);

        assertThat(response.status()).isEqualTo("ACCEPTED");
        verify(processingClient).retry("task-1");
        verify(repository).updateProcessingStatus(30L, "task-1", "ACCEPTED");
    }

    @Test
    void rejectsKnowledgeBaseAccessForNonMember() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        when(repository.findKnowledgeBase(20L)).thenReturn(java.util.Optional.of(
                new KnowledgeBase(20L, 10L, "研发资料", null, "ACTIVE")
        ));
        when(workspaceRepository.isMember(10L, 99L)).thenReturn(false);

        KnowledgeService service = new KnowledgeService(
                repository,
                mock(DocumentProcessingClient.class),
                mock(ObjectStorageService.class),
                mock(KnowledgeRetrievalClient.class),
                mock(KnowledgeAnswerClient.class),
                workspaceRepository,
                mock(KnowledgeIndexClient.class),
                mock(DocumentPreviewClient.class)
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.findDocuments(99L, 20L))
                .isInstanceOf(com.aicopilot.common.exception.AccessDeniedException.class);
        verify(repository, org.mockito.Mockito.never()).findDocuments(20L);
    }

    @Test
    void allowsKnowledgeBaseAccessForMember() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        when(repository.findKnowledgeBase(20L)).thenReturn(java.util.Optional.of(
                new KnowledgeBase(20L, 10L, "研发资料", null, "ACTIVE")
        ));
        when(workspaceRepository.isMember(10L, 7L)).thenReturn(true);
        when(repository.findDocuments(20L)).thenReturn(List.of());

        KnowledgeService service = new KnowledgeService(
                repository,
                mock(DocumentProcessingClient.class),
                mock(ObjectStorageService.class),
                mock(KnowledgeRetrievalClient.class),
                mock(KnowledgeAnswerClient.class),
                workspaceRepository,
                mock(KnowledgeIndexClient.class),
                mock(DocumentPreviewClient.class)
        );

        assertThat(service.findDocuments(7L, 20L)).isEmpty();
        verify(repository).findDocuments(20L);
    }

    @Test
    void rejectsWorkspaceDocumentAccessForNonMember() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        when(workspaceRepository.isMember(10L, 99L)).thenReturn(false);

        KnowledgeService service = new KnowledgeService(
                repository,
                mock(DocumentProcessingClient.class),
                mock(ObjectStorageService.class),
                mock(KnowledgeRetrievalClient.class),
                mock(KnowledgeAnswerClient.class),
                workspaceRepository,
                mock(KnowledgeIndexClient.class),
                mock(DocumentPreviewClient.class)
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.findWorkspaceDocuments(99L, 10L, null, null, null))
                .isInstanceOf(com.aicopilot.common.exception.AccessDeniedException.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .findDocumentsForWorkspace(10L, null, null, null);
    }

    /** 打通授权链的桩：knowledgeBaseId 挂在 workspaceId 下，且 userId 是成员。 */
    private static WorkspaceRepository authorize(KnowledgeRepository repository, long knowledgeBaseId,
                                                 long workspaceId, long userId) {
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        when(repository.findKnowledgeBase(knowledgeBaseId)).thenReturn(Optional.of(
                new KnowledgeBase(knowledgeBaseId, workspaceId, "资料", null, "ACTIVE")));
        when(workspaceRepository.isMember(workspaceId, userId)).thenReturn(true);
        return workspaceRepository;
    }

    @Test
    void deletesDocumentThroughVectorObjectThenMetadata() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeIndexClient indexClient = mock(KnowledgeIndexClient.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        Instant createdAt = Instant.parse("2026-09-09T00:00:00Z");
        KnowledgeDocument document = new KnowledgeDocument(
                30L, 20L, "guide.pdf", "kb/20/guide.pdf", "application/pdf", 2048L, "COMPLETED", "task-1", createdAt
        );
        WorkspaceRepository workspaceRepository = authorize(repository, 20L, 10L, 7L);
        when(repository.findDocument(20L, 30L)).thenReturn(Optional.of(document));

        new KnowledgeService(repository, mock(DocumentProcessingClient.class), storageService,
                mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class),
                workspaceRepository, indexClient, mock(DocumentPreviewClient.class))
                .deleteDocument(7L, 20L, 30L);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(indexClient, storageService, repository);
        inOrder.verify(indexClient).deleteDocumentIndex(20L, "kb/20/guide.pdf");
        inOrder.verify(storageService).delete("kb/20/guide.pdf");
        inOrder.verify(repository).deleteDocument(30L);
    }

    @Test
    void stillDeletesMetadataWhenStorageCleanupFails() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeIndexClient indexClient = mock(KnowledgeIndexClient.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        Instant createdAt = Instant.parse("2026-09-09T00:00:00Z");
        KnowledgeDocument document = new KnowledgeDocument(
                30L, 20L, "guide.pdf", "kb/20/guide.pdf", "application/pdf", 2048L, "COMPLETED", "task-1", createdAt
        );
        WorkspaceRepository workspaceRepository = authorize(repository, 20L, 10L, 7L);
        when(repository.findDocument(20L, 30L)).thenReturn(Optional.of(document));
        org.mockito.Mockito.doThrow(new RuntimeException("milvus down")).when(indexClient)
                .deleteDocumentIndex(20L, "kb/20/guide.pdf");
        org.mockito.Mockito.doThrow(new RuntimeException("minio down")).when(storageService).delete("kb/20/guide.pdf");

        new KnowledgeService(repository, mock(DocumentProcessingClient.class), storageService,
                mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class),
                workspaceRepository, indexClient, mock(DocumentPreviewClient.class))
                .deleteDocument(7L, 20L, 30L);

        verify(repository).deleteDocument(30L);
    }

    @Test
    void rejectsDeletionWhenDocumentIsMissing() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeIndexClient indexClient = mock(KnowledgeIndexClient.class);
        WorkspaceRepository workspaceRepository = authorize(repository, 20L, 10L, 7L);
        when(repository.findDocument(20L, 30L)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new KnowledgeService(repository, mock(DocumentProcessingClient.class),
                                mock(ObjectStorageService.class), mock(KnowledgeRetrievalClient.class),
                                mock(KnowledgeAnswerClient.class), workspaceRepository, indexClient, mock(DocumentPreviewClient.class))
                                .deleteDocument(7L, 20L, 30L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("DOCUMENT_NOT_FOUND"));

        verify(repository, org.mockito.Mockito.never()).deleteDocument(any());
        verifyNoInteractions(indexClient);
    }

    @Test
    void rejectsDeletionForNonMember() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeIndexClient indexClient = mock(KnowledgeIndexClient.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        when(repository.findKnowledgeBase(20L)).thenReturn(Optional.of(
                new KnowledgeBase(20L, 10L, "资料", null, "ACTIVE")));
        when(workspaceRepository.isMember(10L, 99L)).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new KnowledgeService(repository, mock(DocumentProcessingClient.class), storageService,
                                mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class),
                                workspaceRepository, indexClient, mock(DocumentPreviewClient.class))
                                .deleteDocument(99L, 20L, 30L))
                .isInstanceOf(AccessDeniedException.class);

        verify(repository, org.mockito.Mockito.never()).deleteDocument(any());
        verifyNoInteractions(indexClient, storageService);
    }


    @Test
    void previewsDocumentByForwardingTheMetadataObjectKey() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        DocumentPreviewClient previewClient = mock(DocumentPreviewClient.class);
        Instant createdAt = Instant.parse("2026-09-09T00:00:00Z");
        KnowledgeDocument document = new KnowledgeDocument(
                30L, 20L, "guide.pdf", "kb/20/guide.pdf", "application/pdf", 2048L, "COMPLETED", "task-1", createdAt
        );
        when(repository.findKnowledgeBase(20L)).thenReturn(Optional.of(
                new KnowledgeBase(20L, 10L, "资料", null, "ACTIVE")));
        when(repository.findDocument(20L, 30L)).thenReturn(Optional.of(document));
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        when(workspaceRepository.isMember(10L, 7L)).thenReturn(true);
        DocumentPreview preview = new DocumentPreview("guide.pdf", 1, 2, false,
                List.of(new DocumentPreviewSection(List.of("日常巡检"), List.of("每天检查电量。"))));
        when(previewClient.preview(20L, "kb/20/guide.pdf")).thenReturn(preview);

        DocumentPreview result = new KnowledgeService(repository, mock(DocumentProcessingClient.class),
                mock(ObjectStorageService.class), mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class),
                workspaceRepository, mock(KnowledgeIndexClient.class), previewClient)
                .previewDocument(7L, 20L, 30L);

        // object_key 来自 MySQL 元数据行而不是调用方输入：预览永远只读「这一行」指向的对象
        assertThat(result).isSameAs(preview);
        verify(previewClient).preview(20L, "kb/20/guide.pdf");
    }

    @Test
    void rejectsDocumentPreviewForNonMembersBeforeTouchingTheClient() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        DocumentPreviewClient previewClient = mock(DocumentPreviewClient.class);
        when(repository.findKnowledgeBase(20L)).thenReturn(Optional.of(
                new KnowledgeBase(20L, 10L, "资料", null, "ACTIVE")));
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        when(workspaceRepository.isMember(10L, 99L)).thenReturn(false);

        assertThatThrownBy(() -> new KnowledgeService(repository, mock(DocumentProcessingClient.class),
                mock(ObjectStorageService.class), mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class),
                workspaceRepository, mock(KnowledgeIndexClient.class), previewClient)
                .previewDocument(99L, 20L, 30L))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(previewClient);
        verify(repository, org.mockito.Mockito.never()).findDocument(any(), any());
    }
}
