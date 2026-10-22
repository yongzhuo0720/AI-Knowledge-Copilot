package com.aicopilot.admin;

import com.aicopilot.common.exception.AccessDeniedException;
import com.aicopilot.common.exception.BusinessException;
import com.aicopilot.common.storage.ObjectStorageService;
import com.aicopilot.knowledge.KnowledgeIndexClient;
import com.aicopilot.user.User;
import com.aicopilot.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminService {

    private static final Logger logger = LoggerFactory.getLogger(AdminService.class);
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final AdminRepository adminRepository;
    private final UserRepository userRepository;
    private final ObjectStorageService objectStorageService;
    private final KnowledgeIndexClient knowledgeIndexClient;

    public AdminService(
            AdminRepository adminRepository,
            UserRepository userRepository,
            ObjectStorageService objectStorageService,
            KnowledgeIndexClient knowledgeIndexClient
    ) {
        this.adminRepository = adminRepository;
        this.userRepository = userRepository;
        this.objectStorageService = objectStorageService;
        this.knowledgeIndexClient = knowledgeIndexClient;
    }

    @Transactional(readOnly = true)
    public AdminOverviewResponse overview(Long operatorId) {
        requireAdmin(operatorId);
        return adminRepository.findOverview();
    }

    @Transactional(readOnly = true)
    public List<AdminUserResponse> findUsers(Long operatorId, String query) {
        requireAdmin(operatorId);
        return adminRepository.findUsers(query);
    }

    @Transactional(readOnly = true)
    public List<AdminWorkspaceResponse> findWorkspaces(Long operatorId, String query) {
        requireAdmin(operatorId);
        return adminRepository.findWorkspaces(query);
    }

    @Transactional(readOnly = true)
    public List<AdminKnowledgeBaseResponse> findKnowledgeBases(Long operatorId, String query) {
        requireAdmin(operatorId);
        return adminRepository.findKnowledgeBases(query);
    }

    @Transactional
    public AdminUserResponse updateUserStatus(Long operatorId, Long targetUserId, String status) {
        requireAdmin(operatorId);
        requireUserExists(targetUserId);
        if (operatorId.equals(targetUserId)) {
            throw new BusinessException("SELF_OPERATION_FORBIDDEN", "cannot change your own account status");
        }
        adminRepository.updateUserStatus(targetUserId, status);
        return findUser(targetUserId);
    }

    @Transactional
    public AdminUserResponse updateUserAdmin(Long operatorId, Long targetUserId, boolean admin) {
        requireAdmin(operatorId);
        requireUserExists(targetUserId);
        if (operatorId.equals(targetUserId)) {
            throw new BusinessException("SELF_OPERATION_FORBIDDEN", "cannot change your own administrator privilege");
        }
        if (!admin && isLastAdmin(targetUserId)) {
            throw new BusinessException("LAST_ADMIN_PROTECTED", "at least one administrator must remain");
        }
        adminRepository.updateUserAdmin(targetUserId, admin);
        return findUser(targetUserId);
    }

    @Transactional
    public void deleteUser(Long operatorId, Long targetUserId) {
        requireAdmin(operatorId);
        requireUserExists(targetUserId);
        if (operatorId.equals(targetUserId)) {
            throw new BusinessException("SELF_OPERATION_FORBIDDEN", "cannot delete your own account");
        }
        if (isLastAdmin(targetUserId)) {
            throw new BusinessException("LAST_ADMIN_PROTECTED", "cannot delete the only administrator");
        }
        cleanupStorage(adminRepository.findKnowledgeBaseIdsOwnedBy(targetUserId));
        adminRepository.deleteUserCascade(targetUserId);
    }

    @Transactional
    public void deleteWorkspace(Long operatorId, Long workspaceId) {
        requireAdmin(operatorId);
        cleanupStorage(adminRepository.findKnowledgeBaseIdsByWorkspace(workspaceId));
        adminRepository.deleteWorkspaceCascade(workspaceId);
    }

    @Transactional
    public void deleteKnowledgeBase(Long operatorId, Long knowledgeBaseId) {
        requireAdmin(operatorId);
        cleanupStorage(List.of(knowledgeBaseId));
        adminRepository.deleteKnowledgeBaseCascade(knowledgeBaseId);
    }

    /**
     * 必须先于 MySQL 删除：元数据一没，就再也拿不到 object_key / knowledge_base_id，
     * MinIO 对象与 Milvus 向量会永久留在存储里（还能被直接查询到）。
     *
     * 单个对象/知识库清理失败只记日志，不阻塞管理员的删除操作：ai-service 或 MinIO
     * 短暂不可用不应让后台完全无法删数据；两个 delete 都是幂等的，日志里保留了
     * 具体坐标，可在恢复后人工补偿。
     */
    private void cleanupStorage(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds.isEmpty()) {
            return;
        }
        for (Long knowledgeBaseId : knowledgeBaseIds) {
            try {
                knowledgeIndexClient.deleteKnowledgeBaseIndex(knowledgeBaseId);
            } catch (RuntimeException exception) {
                logger.warn("failed to delete vectors for knowledge base {}", knowledgeBaseId, exception);
            }
        }
        for (String objectKey : adminRepository.findDocumentObjectKeysByKnowledgeBases(knowledgeBaseIds)) {
            try {
                objectStorageService.delete(objectKey);
            } catch (RuntimeException exception) {
                logger.warn("failed to delete object {} while deleting knowledge bases {}",
                        objectKey, knowledgeBaseIds, exception);
            }
        }
    }

    private AdminUserResponse findUser(Long targetUserId) {
        return adminRepository.findUsers(null).stream()
                .filter(user -> user.id().equals(targetUserId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("RESOURCE_NOT_FOUND", "user does not exist"));
    }

    private void requireAdmin(Long operatorId) {
        if (operatorId == null) {
            throw new AccessDeniedException("authentication is required");
        }
        User operator = userRepository.findById(operatorId)
                .orElseThrow(() -> new AccessDeniedException("authenticated user does not exist"));
        if (!operator.admin()) {
            throw new AccessDeniedException("administrator privilege is required");
        }
    }

    private void requireUserExists(Long userId) {
        if (!adminRepository.userExists(userId)) {
            throw new BusinessException("RESOURCE_NOT_FOUND", "user does not exist");
        }
    }

    private boolean isLastAdmin(Long targetUserId) {
        return adminRepository.findUsers(null).stream()
                .filter(AdminUserResponse::isAdmin)
                .allMatch(user -> user.id().equals(targetUserId));
    }
}
