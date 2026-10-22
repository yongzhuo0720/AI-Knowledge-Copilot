package com.aicopilot.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class JdbcAdminRepository implements AdminRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAdminRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AdminOverviewResponse findOverview() {
        return jdbcTemplate.queryForObject(
                "SELECT "
                        + "(SELECT COUNT(*) FROM app_user) AS user_count, "
                        + "(SELECT COUNT(*) FROM app_user WHERE status = 'ACTIVE') AS active_user_count, "
                        + "(SELECT COUNT(*) FROM app_user WHERE status <> 'ACTIVE') AS suspended_user_count, "
                        + "(SELECT COUNT(*) FROM app_user WHERE is_admin = TRUE) AS admin_count, "
                        + "(SELECT COUNT(*) FROM workspace) AS workspace_count, "
                        + "(SELECT COUNT(*) FROM knowledge_base) AS knowledge_base_count, "
                        + "(SELECT COUNT(*) FROM knowledge_document) AS document_count, "
                        + "(SELECT COUNT(*) FROM conversation_session) AS conversation_count",
                (resultSet, rowNumber) -> new AdminOverviewResponse(
                        resultSet.getLong("user_count"),
                        resultSet.getLong("active_user_count"),
                        resultSet.getLong("suspended_user_count"),
                        resultSet.getLong("admin_count"),
                        resultSet.getLong("workspace_count"),
                        resultSet.getLong("knowledge_base_count"),
                        resultSet.getLong("document_count"),
                        resultSet.getLong("conversation_count")
                )
        );
    }

    @Override
    public List<AdminUserResponse> findUsers(String query) {
        StringBuilder sql = new StringBuilder(
                "SELECT u.id, u.username, u.email, u.status, u.is_admin, u.created_at, "
                        + "(SELECT COUNT(*) FROM workspace w WHERE w.owner_user_id = u.id) AS workspace_count, "
                        + "(SELECT COUNT(*) FROM workspace_member m WHERE m.user_id = u.id) AS member_count, "
                        + "(SELECT COUNT(*) FROM conversation_session s WHERE s.user_id = u.id) AS conversation_count "
                        + "FROM app_user u WHERE 1 = 1"
        );
        List<Object> parameters = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            sql.append(" AND (LOWER(u.username) LIKE ? OR LOWER(u.email) LIKE ?)");
            String pattern = "%" + query.trim().toLowerCase() + "%";
            parameters.add(pattern);
            parameters.add(pattern);
        }
        sql.append(" ORDER BY u.id");
        return jdbcTemplate.query(
                sql.toString(),
                (resultSet, rowNumber) -> new AdminUserResponse(
                        resultSet.getLong("id"),
                        resultSet.getString("username"),
                        resultSet.getString("email"),
                        resultSet.getString("status"),
                        resultSet.getBoolean("is_admin"),
                        toInstant(resultSet.getTimestamp("created_at")),
                        resultSet.getLong("workspace_count"),
                        resultSet.getLong("member_count"),
                        resultSet.getLong("conversation_count")
                ),
                parameters.toArray()
        );
    }

    @Override
    public List<AdminWorkspaceResponse> findWorkspaces(String query) {
        StringBuilder sql = new StringBuilder(
                "SELECT w.id, w.name, w.owner_user_id, u.username AS owner_username, w.created_at, "
                        + "(SELECT COUNT(*) FROM workspace_member m WHERE m.workspace_id = w.id) AS member_count, "
                        + "(SELECT COUNT(*) FROM knowledge_base k WHERE k.workspace_id = w.id) AS knowledge_base_count "
                        + "FROM workspace w LEFT JOIN app_user u ON u.id = w.owner_user_id WHERE 1 = 1"
        );
        List<Object> parameters = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            sql.append(" AND LOWER(w.name) LIKE ?");
            parameters.add("%" + query.trim().toLowerCase() + "%");
        }
        sql.append(" ORDER BY w.id");
        return jdbcTemplate.query(
                sql.toString(),
                (resultSet, rowNumber) -> new AdminWorkspaceResponse(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getLong("owner_user_id"),
                        resultSet.getString("owner_username"),
                        resultSet.getLong("member_count"),
                        resultSet.getLong("knowledge_base_count"),
                        toInstant(resultSet.getTimestamp("created_at"))
                ),
                parameters.toArray()
        );
    }

    @Override
    public List<AdminKnowledgeBaseResponse> findKnowledgeBases(String query) {
        StringBuilder sql = new StringBuilder(
                "SELECT k.id, k.name, k.description, k.status, k.workspace_id, w.name AS workspace_name, k.created_at, "
                        + "(SELECT COUNT(*) FROM knowledge_document d WHERE d.knowledge_base_id = k.id) AS document_count, "
                        + "(SELECT COUNT(*) FROM conversation_session s WHERE s.knowledge_base_id = k.id) AS conversation_count "
                        + "FROM knowledge_base k LEFT JOIN workspace w ON w.id = k.workspace_id WHERE 1 = 1"
        );
        List<Object> parameters = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            sql.append(" AND LOWER(k.name) LIKE ?");
            parameters.add("%" + query.trim().toLowerCase() + "%");
        }
        sql.append(" ORDER BY k.id");
        return jdbcTemplate.query(
                sql.toString(),
                (resultSet, rowNumber) -> new AdminKnowledgeBaseResponse(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getString("description"),
                        resultSet.getString("status"),
                        resultSet.getLong("workspace_id"),
                        resultSet.getString("workspace_name"),
                        resultSet.getLong("document_count"),
                        resultSet.getLong("conversation_count"),
                        toInstant(resultSet.getTimestamp("created_at"))
                ),
                parameters.toArray()
        );
    }

    @Override
    public boolean userExists(Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE id = ?", Integer.class, userId);
        return count != null && count > 0;
    }

    @Override
    public void updateUserStatus(Long userId, String status) {
        jdbcTemplate.update("UPDATE app_user SET status = ? WHERE id = ?", status, userId);
    }

    @Override
    public void updateUserAdmin(Long userId, boolean admin) {
        jdbcTemplate.update("UPDATE app_user SET is_admin = ? WHERE id = ?", admin, userId);
    }

    /**
     * 删除用户。顺序就是「从叶子往根走」：
     * conversation_message_agent_step / conversation_message_source（最深的两张）
     *   → conversation_message → conversation_session
     *   → document_processing_task → knowledge_document → knowledge_base
     *   → workspace_member → workspace
     *   → app_user
     * 任何一步提前，都会被 MySQL 用 ERROR 1451 拦下来。
     */
    @Override
    public void deleteUserCascade(Long userId) {
        deleteMessagesOfSessionsByUser(userId);
        jdbcTemplate.update(
                "DELETE FROM conversation_session WHERE user_id = ?", userId);
        // 关键：被删用户拥有的知识库里，可能还挂着【其他成员】建的会话。
        // 只删 user_id = ? 的会话是不够的——下面删 knowledge_base 时这些残留会话会触发
        // ERROR 1451 外键冲突，导致整个 @Transactional 回滚、删除用户直接 500。
        deleteMessagesOfSessionsInOwnedKnowledgeBases(userId);
        jdbcTemplate.update(
                "DELETE FROM conversation_session WHERE knowledge_base_id IN "
                        + "(SELECT id FROM knowledge_base WHERE workspace_id IN "
                        + "(SELECT id FROM workspace WHERE owner_user_id = ?))", userId);
        jdbcTemplate.update(
                "DELETE FROM document_processing_task WHERE knowledge_base_id IN "
                        + "(SELECT id FROM knowledge_base WHERE workspace_id IN "
                        + "(SELECT id FROM workspace WHERE owner_user_id = ?))", userId);
        jdbcTemplate.update(
                "DELETE FROM knowledge_document WHERE knowledge_base_id IN "
                        + "(SELECT id FROM knowledge_base WHERE workspace_id IN "
                        + "(SELECT id FROM workspace WHERE owner_user_id = ?))", userId);
        jdbcTemplate.update(
                "DELETE FROM knowledge_base WHERE workspace_id IN "
                        + "(SELECT id FROM workspace WHERE owner_user_id = ?)", userId);
        jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE workspace_id IN "
                        + "(SELECT id FROM workspace WHERE owner_user_id = ?)", userId);
        jdbcTemplate.update("DELETE FROM workspace_member WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM workspace WHERE owner_user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    @Override
    public void deleteWorkspaceCascade(Long workspaceId) {
        deleteMessagesOfSessionsInWorkspace(workspaceId);
        jdbcTemplate.update(
                "DELETE FROM conversation_session WHERE knowledge_base_id IN "
                        + "(SELECT id FROM knowledge_base WHERE workspace_id = ?)", workspaceId);
        jdbcTemplate.update(
                "DELETE FROM document_processing_task WHERE knowledge_base_id IN "
                        + "(SELECT id FROM knowledge_base WHERE workspace_id = ?)", workspaceId);
        jdbcTemplate.update(
                "DELETE FROM knowledge_document WHERE knowledge_base_id IN "
                        + "(SELECT id FROM knowledge_base WHERE workspace_id = ?)", workspaceId);
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspaceId);
        jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
    }

    @Override
    public void deleteKnowledgeBaseCascade(Long knowledgeBaseId) {
        deleteMessagesOfSessionsInKnowledgeBase(knowledgeBaseId);
        jdbcTemplate.update("DELETE FROM conversation_session WHERE knowledge_base_id = ?", knowledgeBaseId);
        jdbcTemplate.update("DELETE FROM document_processing_task WHERE knowledge_base_id = ?", knowledgeBaseId);
        jdbcTemplate.update("DELETE FROM knowledge_document WHERE knowledge_base_id = ?", knowledgeBaseId);
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", knowledgeBaseId);
    }

    @Override
    public List<Long> findKnowledgeBaseIdsByWorkspace(Long workspaceId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM knowledge_base WHERE workspace_id = ? ORDER BY id", Long.class, workspaceId);
    }

    @Override
    public List<Long> findKnowledgeBaseIdsOwnedBy(Long ownerUserId) {
        return jdbcTemplate.queryForList(
                "SELECT k.id FROM knowledge_base k JOIN workspace w ON w.id = k.workspace_id "
                        + "WHERE w.owner_user_id = ? ORDER BY k.id", Long.class, ownerUserId);
    }

    @Override
    public List<String> findDocumentObjectKeysByKnowledgeBases(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(knowledgeBaseIds.size(), "?"));
        return jdbcTemplate.queryForList(
                "SELECT object_key FROM knowledge_document WHERE knowledge_base_id IN (" + placeholders + ")",
                String.class, knowledgeBaseIds.toArray());
    }

    private void deleteMessagesOfSessionsByUser(Long userId) {
        deleteAgentStepsAndSources("SELECT id FROM conversation_session WHERE user_id = ?", userId);
        jdbcTemplate.update(
                "DELETE FROM conversation_message WHERE session_id IN "
                        + "(SELECT id FROM conversation_session WHERE user_id = ?)", userId);
    }

    private void deleteMessagesOfSessionsInWorkspace(Long workspaceId) {
        deleteAgentStepsAndSources(
                "SELECT id FROM conversation_session WHERE knowledge_base_id IN "
                        + "(SELECT id FROM knowledge_base WHERE workspace_id = ?)", workspaceId);
        jdbcTemplate.update(
                "DELETE FROM conversation_message WHERE session_id IN "
                        + "(SELECT id FROM conversation_session WHERE knowledge_base_id IN "
                        + "(SELECT id FROM knowledge_base WHERE workspace_id = ?))", workspaceId);
    }

    private void deleteMessagesOfSessionsInOwnedKnowledgeBases(Long ownerUserId) {
        String sessionIdSql = "SELECT id FROM conversation_session WHERE knowledge_base_id IN "
                + "(SELECT id FROM knowledge_base WHERE workspace_id IN "
                + "(SELECT id FROM workspace WHERE owner_user_id = ?))";
        deleteAgentStepsAndSources(sessionIdSql, ownerUserId);
        jdbcTemplate.update(
                "DELETE FROM conversation_message WHERE session_id IN (" + sessionIdSql + ")",
                ownerUserId);
    }

    private void deleteMessagesOfSessionsInKnowledgeBase(Long knowledgeBaseId) {
        deleteAgentStepsAndSources(
                "SELECT id FROM conversation_session WHERE knowledge_base_id = ?", knowledgeBaseId);
        jdbcTemplate.update(
                "DELETE FROM conversation_message WHERE session_id IN "
                        + "(SELECT id FROM conversation_session WHERE knowledge_base_id = ?)", knowledgeBaseId);
    }

    private void deleteAgentStepsAndSources(String sessionIdSql, Object parameter) {
        jdbcTemplate.update(
                "DELETE FROM conversation_message_agent_step WHERE message_id IN "
                        + "(SELECT id FROM conversation_message WHERE session_id IN (" + sessionIdSql + "))",
                parameter);
        jdbcTemplate.update(
                "DELETE FROM conversation_message_source WHERE message_id IN "
                        + "(SELECT id FROM conversation_message WHERE session_id IN (" + sessionIdSql + "))",
                parameter);
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
