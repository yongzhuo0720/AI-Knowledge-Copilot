package com.aicopilot.admin;

import com.aicopilot.common.storage.ObjectStorageService;
import com.aicopilot.knowledge.KnowledgeIndexClient;
import com.aicopilot.user.User;
import com.aicopilot.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminServiceTest {

    private AdminRepository adminRepository;
    private ObjectStorageService storageService;
    private KnowledgeIndexClient indexClient;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        adminRepository = mock(AdminRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        storageService = mock(ObjectStorageService.class);
        indexClient = mock(KnowledgeIndexClient.class);
        when(userRepository.findById(1L)).thenReturn(Optional.of(
                new User(1L, "admin", "admin@example.com", "hash", "ACTIVE", true)));
        when(adminRepository.findUsers(null)).thenReturn(List.of(
                new AdminUserResponse(1L, "admin", "admin@example.com", "ACTIVE", true, null, 0, 0, 0),
                new AdminUserResponse(7L, "target", "target@example.com", "ACTIVE", true, null, 0, 0, 0)));
        adminService = new AdminService(adminRepository, userRepository, storageService, indexClient);
    }

    @Test
    void deletesVectorsAndObjectsBeforeRemovingKnowledgeBaseMetadata() {
        when(adminRepository.findDocumentObjectKeysByKnowledgeBases(List.of(20L)))
                .thenReturn(List.of("kb/20/a/guide.pdf", "kb/20/b/notes.md"));

        adminService.deleteKnowledgeBase(1L, 20L);

        InOrder order = inOrder(indexClient, storageService, adminRepository);
        order.verify(indexClient).deleteKnowledgeBaseIndex(20L);
        order.verify(storageService).delete("kb/20/a/guide.pdf");
        order.verify(storageService).delete("kb/20/b/notes.md");
        order.verify(adminRepository).deleteKnowledgeBaseCascade(20L);
    }

    @Test
    void cleansUpEveryKnowledgeBaseInAWorkspace() {
        when(adminRepository.findKnowledgeBaseIdsByWorkspace(9L)).thenReturn(List.of(20L, 21L));
        when(adminRepository.findDocumentObjectKeysByKnowledgeBases(List.of(20L, 21L)))
                .thenReturn(List.of("kb/20/a.pdf", "kb/21/b.pdf"));

        adminService.deleteWorkspace(1L, 9L);

        verify(indexClient).deleteKnowledgeBaseIndex(20L);
        verify(indexClient).deleteKnowledgeBaseIndex(21L);
        verify(storageService).delete("kb/20/a.pdf");
        verify(storageService).delete("kb/21/b.pdf");
        verify(adminRepository).deleteWorkspaceCascade(9L);
    }

    @Test
    void deletesOwnedKnowledgeBaseStorageWhenDeletingAUser() {
        when(adminRepository.userExists(7L)).thenReturn(true);
        when(adminRepository.findKnowledgeBaseIdsOwnedBy(7L)).thenReturn(List.of(20L));
        when(adminRepository.findDocumentObjectKeysByKnowledgeBases(List.of(20L)))
                .thenReturn(List.of("kb/20/a.pdf"));

        adminService.deleteUser(1L, 7L);

        InOrder order = inOrder(indexClient, storageService, adminRepository);
        order.verify(indexClient).deleteKnowledgeBaseIndex(20L);
        order.verify(storageService).delete("kb/20/a.pdf");
        order.verify(adminRepository).deleteUserCascade(7L);
    }

    @Test
    void storageFailuresDoNotBlockMetadataDeletion() {
        when(adminRepository.findDocumentObjectKeysByKnowledgeBases(List.of(20L)))
                .thenReturn(List.of("kb/20/a.pdf"));
        doThrow(new IllegalStateException("minio down")).when(storageService).delete("kb/20/a.pdf");
        doThrow(new IllegalStateException("ai-service down")).when(indexClient).deleteKnowledgeBaseIndex(20L);

        adminService.deleteKnowledgeBase(1L, 20L);

        verify(adminRepository).deleteKnowledgeBaseCascade(20L);
    }

    @Test
    void skipsStorageCleanupWhenThereAreNoKnowledgeBases() {
        when(adminRepository.findKnowledgeBaseIdsByWorkspace(9L)).thenReturn(List.of());

        adminService.deleteWorkspace(1L, 9L);

        verify(indexClient, never()).deleteKnowledgeBaseIndex(any());
        verify(storageService, never()).delete(any());
        verify(adminRepository).deleteWorkspaceCascade(9L);
    }
}
