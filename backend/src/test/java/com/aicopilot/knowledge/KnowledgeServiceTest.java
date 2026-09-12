package com.aicopilot.knowledge;

import com.aicopilot.common.storage.ObjectStorageService;
import com.aicopilot.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeServiceTest {

    @Test
    void createsKnowledgeBaseWithNormalizedFields() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeBase saved = new KnowledgeBase(20L, 10L, "研发资料", "架构文档", "ACTIVE");
        when(repository.saveKnowledgeBase(any(KnowledgeBase.class))).thenReturn(saved);

        KnowledgeBaseResponse response = new KnowledgeService(repository, mock(DocumentProcessingClient.class), mock(ObjectStorageService.class), mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class))
                .createKnowledgeBase(new CreateKnowledgeBaseRequest(10L, " 研发资料 ", " 架构文档 "));

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

        KnowledgeDocumentResponse response = new KnowledgeService(repository, processingClient, storageService, mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class))
                .registerDocument(20L, new RegisterDocumentRequest(
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

        List<KnowledgeDocumentResponse> response = new KnowledgeService(repository, mock(DocumentProcessingClient.class), mock(ObjectStorageService.class), mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class))
                .findDocuments(20L);

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

        KnowledgeDocumentResponse response = new KnowledgeService(repository, processingClient, mock(ObjectStorageService.class), mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class))
                .refreshDocumentProcessingStatus(20L, 30L);

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

        KnowledgeDocumentResponse response = new KnowledgeService(repository, processingClient, mock(ObjectStorageService.class), mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class))
                .reparseDocument(20L, 30L);

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

        KnowledgeDocumentResponse response = new KnowledgeService(repository, processingClient, mock(ObjectStorageService.class), mock(KnowledgeRetrievalClient.class), mock(KnowledgeAnswerClient.class))
                .retryDocumentProcessing(20L, 30L);

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
                workspaceRepository
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
                workspaceRepository
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
                workspaceRepository
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.findWorkspaceDocuments(99L, 10L, null, null, null))
                .isInstanceOf(com.aicopilot.common.exception.AccessDeniedException.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .findDocumentsForWorkspace(10L, null, null, null);
    }
}
