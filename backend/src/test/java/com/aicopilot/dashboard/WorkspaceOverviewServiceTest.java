package com.aicopilot.dashboard;

import com.aicopilot.common.exception.AccessDeniedException;
import com.aicopilot.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceOverviewServiceTest {

    @Test
    void rejectsOverviewForNonMember() {
        WorkspaceOverviewRepository overviewRepository = mock(WorkspaceOverviewRepository.class);
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        IndexedChunkClient indexedChunkClient = mock(IndexedChunkClient.class);
        when(workspaceRepository.isMember(10L, 99L)).thenReturn(false);

        WorkspaceOverviewService service = new WorkspaceOverviewService(
                overviewRepository, workspaceRepository, indexedChunkClient
        );

        assertThatThrownBy(() -> service.findForUser(99L, 10L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("user is not a member of this workspace");
        verify(overviewRepository, never()).findByWorkspaceId(10L, 99L);
        verify(indexedChunkClient, never()).countChunks(List.of());
    }
}
