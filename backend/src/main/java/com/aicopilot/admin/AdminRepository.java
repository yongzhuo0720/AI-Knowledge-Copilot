package com.aicopilot.admin;

import java.util.List;

/**
 * 后台数据访问接口。
 * 与业务侧仓储分离：业务仓储只暴露「某个用户自己的数据」，
 * 后台仓储需要跨用户的全量视角，两者权限语义完全不同，所以单独成接口。
 */
public interface AdminRepository {

    AdminOverviewResponse findOverview();

    List<AdminUserResponse> findUsers(String query);

    List<AdminWorkspaceResponse> findWorkspaces(String query);

    List<AdminKnowledgeBaseResponse> findKnowledgeBases(String query);

    boolean userExists(Long userId);

    void updateUserStatus(Long userId, String status);

    void updateUserAdmin(Long userId, boolean admin);

    /**
     * 按「子表优先」顺序删除用户及其全部私有数据，最后删除 app_user 自身。
     * 顺序不可调换，否则会撞上外键约束。
     */
    void deleteUserCascade(Long userId);

    void deleteWorkspaceCascade(Long workspaceId);

    void deleteKnowledgeBaseCascade(Long knowledgeBaseId);

    /**
     * 删除前先取出存储侧坐标：MySQL 元数据一旦删除，就再也定位不到
     * MinIO 对象和 Milvus 向量了。
     */
    List<Long> findKnowledgeBaseIdsByWorkspace(Long workspaceId);

    List<Long> findKnowledgeBaseIdsOwnedBy(Long ownerUserId);

    List<String> findDocumentObjectKeysByKnowledgeBases(List<Long> knowledgeBaseIds);
}
