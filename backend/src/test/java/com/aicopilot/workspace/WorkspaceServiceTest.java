package com.aicopilot.workspace;

import org.junit.jupiter.api.Test;
import com.aicopilot.common.exception.AccessDeniedException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceServiceTest {

    @Test
    void createsWorkspaceAndAddsOwnerAsMember() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        Workspace savedWorkspace = new Workspace(10L, "知识库", 1L);
        when(repository.save(any(Workspace.class))).thenReturn(savedWorkspace);

        WorkspaceResponse response = new WorkspaceService(repository)
                .create(new CreateWorkspaceRequest(" 知识库 ", 1L));

        assertThat(response).isEqualTo(new WorkspaceResponse(10L, "知识库", 1L));
        verify(repository).save(new Workspace(null, "知识库", 1L));
        verify(repository).addMember(new WorkspaceMember(10L, 1L, "OWNER", null));
    }

    @Test
    void addsMemberWithDefaultRole() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);

        WorkspaceMemberResponse response = new WorkspaceService(repository)
                .addMember(10L, new AddWorkspaceMemberRequest(2L, null));

        assertThat(response).isEqualTo(new WorkspaceMemberResponse(10L, 2L, "MEMBER", null));
        verify(repository).addMember(new WorkspaceMember(10L, 2L, "MEMBER", null));
    }

    @Test
    void mapsMembersToResponses() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        Instant joinedAt = Instant.parse("2026-09-09T00:00:00Z");
        when(repository.findMembers(10L)).thenReturn(List.of(
                new WorkspaceMember(10L, 1L, "OWNER", joinedAt)
        ));

        List<WorkspaceMemberResponse> response = new WorkspaceService(repository).findMembers(10L);

        assertThat(response).containsExactly(
                new WorkspaceMemberResponse(10L, 1L, "OWNER", joinedAt)
        );
    }

    @Test
    void rejectsMemberManagementForNonAdmin() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        when(repository.isOwnerOrAdmin(10L, 2L)).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new WorkspaceService(repository).addMember(2L, 10L, new AddWorkspaceMemberRequest(3L, null)))
                .isInstanceOf(AccessDeniedException.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).addMember(any());
    }
}
