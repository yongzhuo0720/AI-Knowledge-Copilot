package com.aicopilot.knowledge;

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

        KnowledgeBaseResponse response = new KnowledgeService(repository)
                .createKnowledgeBase(new CreateKnowledgeBaseRequest(10L, " 研发资料 ", " 架构文档 "));

        assertThat(response).isEqualTo(new KnowledgeBaseResponse(20L, 10L, "研发资料", "架构文档", "ACTIVE"));
        verify(repository).saveKnowledgeBase(new KnowledgeBase( null, 10L, "研发资料", "架构文档", "ACTIVE"));
    }

    @Test
    void registersDocumentWithUploadedStatus() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeDocument saved = new KnowledgeDocument(
                30L, 20L, "guide.pdf", "kb/20/guide.pdf", "application/pdf", 2048L, "UPLOADED", Instant.now()
        );
        when(repository.saveDocument(any(KnowledgeDocument.class))).thenReturn(saved);

        KnowledgeDocumentResponse response = new KnowledgeService(repository)
                .registerDocument(20L, new RegisterDocumentRequest(
                        " guide.pdf ", " kb/20/guide.pdf ", " application/pdf ", 2048L
                ));

        assertThat(response.id()).isEqualTo(30L);
        assertThat(response.status()).isEqualTo("UPLOADED");
        verify(repository).saveDocument(any(KnowledgeDocument.class));
    }

    @Test
    void listsDocuments() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        Instant createdAt = Instant.parse("2026-09-09T00:00:00Z");
        when(repository.findDocuments(20L)).thenReturn(List.of(
                new KnowledgeDocument(30L, 20L, "guide.pdf", "kb/20/guide.pdf", "application/pdf", 2048L, "UPLOADED", createdAt)
        ));

        List<KnowledgeDocumentResponse> response = new KnowledgeService(repository).findDocuments(20L);

        assertThat(response).containsExactly(new KnowledgeDocumentResponse(
                30L, 20L, "guide.pdf", "kb/20/guide.pdf", "application/pdf", 2048L, "UPLOADED", createdAt
        ));
    }
}
