package com.aicopilot.dashboard;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@Profile("local")
public class JdbcWorkspaceOverviewRepository implements WorkspaceOverviewRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcWorkspaceOverviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public WorkspaceOverview findByWorkspaceId(Long workspaceId, Long userId) {
        long knowledgeBaseCount = count(
                "SELECT COUNT(*) FROM knowledge_base WHERE workspace_id = ?", workspaceId);
        long documentCount = count(
                "SELECT COUNT(*) FROM knowledge_document d INNER JOIN knowledge_base k ON k.id = d.knowledge_base_id "
                        + "WHERE k.workspace_id = ?", workspaceId);
        long conversationCount = count(
                "SELECT COUNT(*) FROM conversation_session s INNER JOIN knowledge_base k ON k.id = s.knowledge_base_id "
                        + "WHERE k.workspace_id = ? AND s.user_id = ?", workspaceId, userId);
        long processingTaskCount = count(
                "SELECT COUNT(*) FROM document_processing_task t INNER JOIN knowledge_base k ON k.id = t.knowledge_base_id "
                        + "WHERE k.workspace_id = ? AND t.status IN ('ACCEPTED', 'PROCESSING', 'RETRYING')", workspaceId);

        List<WorkspaceOverview.RecentKnowledgeBase> recentKnowledgeBases = jdbcTemplate.query(
                "SELECT k.id, k.name, k.status, k.updated_at, COUNT(d.id) AS document_count "
                        + "FROM knowledge_base k LEFT JOIN knowledge_document d ON d.knowledge_base_id = k.id "
                        + "WHERE k.workspace_id = ? GROUP BY k.id, k.name, k.status, k.updated_at "
                        + "ORDER BY k.updated_at DESC, k.id DESC LIMIT 5",
                (resultSet, rowNumber) -> new WorkspaceOverview.RecentKnowledgeBase(
                        resultSet.getLong("id"), resultSet.getString("name"), resultSet.getString("status"),
                        toInstant(resultSet.getTimestamp("updated_at")), resultSet.getLong("document_count")
                ), workspaceId
        );

        List<WorkspaceOverview.RecentDocument> recentDocuments = jdbcTemplate.query(
                "SELECT d.id, d.knowledge_base_id, k.name AS knowledge_base_name, d.original_filename, "
                        + "d.content_type, d.file_size, d.status, d.created_at FROM knowledge_document d "
                        + "INNER JOIN knowledge_base k ON k.id = d.knowledge_base_id WHERE k.workspace_id = ? "
                        + "ORDER BY d.created_at DESC, d.id DESC LIMIT 5",
                (resultSet, rowNumber) -> new WorkspaceOverview.RecentDocument(
                        resultSet.getLong("id"), resultSet.getLong("knowledge_base_id"),
                        resultSet.getString("knowledge_base_name"), resultSet.getString("original_filename"),
                        resultSet.getString("content_type"), resultSet.getLong("file_size"),
                        resultSet.getString("status"), toInstant(resultSet.getTimestamp("created_at"))
                ), workspaceId
        );

        List<WorkspaceOverview.RecentConversation> recentConversations = jdbcTemplate.query(
                "SELECT s.id, s.title, s.knowledge_base_id, k.name AS knowledge_base_name, s.updated_at "
                        + "FROM conversation_session s INNER JOIN knowledge_base k ON k.id = s.knowledge_base_id "
                        + "WHERE k.workspace_id = ? AND s.user_id = ? ORDER BY s.updated_at DESC, s.id DESC LIMIT 5",
                (resultSet, rowNumber) -> new WorkspaceOverview.RecentConversation(
                        resultSet.getLong("id"), resultSet.getString("title"), resultSet.getLong("knowledge_base_id"),
                        resultSet.getString("knowledge_base_name"), toInstant(resultSet.getTimestamp("updated_at"))
                ), workspaceId, userId
        );

        return new WorkspaceOverview(
                workspaceId, knowledgeBaseCount, documentCount, null, conversationCount, processingTaskCount,
                recentKnowledgeBases, recentDocuments, recentConversations
        );
    }

    @Override
    public List<Long> findKnowledgeBaseIds(Long workspaceId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM knowledge_base WHERE workspace_id = ? ORDER BY id",
                Long.class,
                workspaceId
        );
    }

    private long count(String sql, Object... parameters) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, parameters);
        return value == null ? 0 : value;
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
