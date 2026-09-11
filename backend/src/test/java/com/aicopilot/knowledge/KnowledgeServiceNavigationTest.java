package com.aicopilot.knowledge;

import com.aicopilot.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeServiceNavigationTest {

    @Test
    void listsKnowledgeBasesForWorkspaceMembers() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        when(workspaceRepository.isMember(10L, 7L)).thenReturn(true);
        when(repository.findKnowledgeBases(10L)).thenReturn(List.of(
                new KnowledgeBase(20L, 10L, "研发资料", "架构文档", "ACTIVE")
        ));

        KnowledgeService service = new KnowledgeService(
                repository,
                mock(DocumentProcessingClient.class),
                mock(com.aicopilot.common.storage.ObjectStorageService.class),
                mock(KnowledgeRetrievalClient.class),
                mock(KnowledgeAnswerClient.class),
                workspaceRepository
        );

        assertThat(service.findKnowledgeBases(7L, 10L))
                .containsExactly(new KnowledgeBaseResponse(20L, 10L, "研发资料", "架构文档", "ACTIVE"));
    }
}
