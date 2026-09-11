package com.aicopilot.knowledge;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@Profile("local")
public class JdbcKnowledgeRepository implements KnowledgeRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public KnowledgeBase saveKnowledgeBase(KnowledgeBase knowledgeBase) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO knowledge_base (workspace_id, name, description, status) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setLong(1, knowledgeBase.workspaceId());
            statement.setString(2, knowledgeBase.name());
            statement.setString(3, knowledgeBase.description());
            statement.setString(4, knowledgeBase.status());
            return statement;
        }, keyHolder);

        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("database did not return generated knowledge base id");
        }
        return new KnowledgeBase(
                id.longValue(),
                knowledgeBase.workspaceId(),
                knowledgeBase.name(),
                knowledgeBase.description(),
                knowledgeBase.status()
        );
    }

    @Override
    public java.util.Optional<KnowledgeBase> findKnowledgeBase(Long knowledgeBaseId) {
        List<KnowledgeBase> bases = jdbcTemplate.query(
                "SELECT id, workspace_id, name, description, status FROM knowledge_base WHERE id = ?",
                (resultSet, rowNumber) -> new KnowledgeBase(
                        resultSet.getLong("id"),
                        resultSet.getLong("workspace_id"),
                        resultSet.getString("name"),
                        resultSet.getString("description"),
                        resultSet.getString("status")
                ),
                knowledgeBaseId
        );
        return bases.stream().findFirst();
    }

    @Override
    public List<KnowledgeBase> findKnowledgeBases(Long workspaceId) {
        return jdbcTemplate.query(
                "SELECT id, workspace_id, name, description, status FROM knowledge_base "
                        + "WHERE workspace_id = ? ORDER BY created_at, id",
                (resultSet, rowNumber) -> new KnowledgeBase(
                        resultSet.getLong("id"),
                        resultSet.getLong("workspace_id"),
                        resultSet.getString("name"),
                        resultSet.getString("description"),
                        resultSet.getString("status")
                ),
                workspaceId
        );
    }

    @Override
    public KnowledgeDocument saveDocument(KnowledgeDocument document) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO knowledge_document "
                            + "(knowledge_base_id, original_filename, object_key, content_type, file_size, status, processing_task_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setLong(1, document.knowledgeBaseId());
            statement.setString(2, document.originalFilename());
            statement.setString(3, document.objectKey());
            statement.setString(4, document.contentType());
            statement.setLong(5, document.fileSize());
            statement.setString(6, document.status());
            statement.setString(7, document.processingTaskId());
            return statement;
        }, keyHolder);

        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("database did not return generated document id");
        }
        return new KnowledgeDocument(
                id.longValue(),
                document.knowledgeBaseId(),
                document.originalFilename(),
                document.objectKey(),
                document.contentType(),
                document.fileSize(),
                document.status(),
                document.processingTaskId(),
                Instant.now(),
                null,
                0
        );
    }

    @Override
    public List<KnowledgeDocument> findDocuments(Long knowledgeBaseId) {
        return jdbcTemplate.query(
                "SELECT d.id, d.knowledge_base_id, d.original_filename, d.object_key, d.content_type, d.file_size, "
                        + "d.status, d.processing_task_id, d.created_at, t.failure_reason, t.retry_count "
                        + "FROM knowledge_document d LEFT JOIN document_processing_task t "
                        + "ON t.task_id = d.processing_task_id WHERE d.knowledge_base_id = ? ORDER BY d.created_at, d.id",
                (resultSet, rowNumber) -> new KnowledgeDocument(
                        resultSet.getLong("id"),
                        resultSet.getLong("knowledge_base_id"),
                        resultSet.getString("original_filename"),
                        resultSet.getString("object_key"),
                        resultSet.getString("content_type"),
                        resultSet.getLong("file_size"),
                        resultSet.getString("status"),
                        resultSet.getString("processing_task_id"),
                        toInstant(resultSet.getTimestamp("created_at")),
                        resultSet.getString("failure_reason"),
                        resultSet.getInt("retry_count")
                ),
                knowledgeBaseId
        );
    }

    @Override
    public java.util.Optional<KnowledgeDocument> findDocument(Long knowledgeBaseId, Long documentId) {
        List<KnowledgeDocument> documents = jdbcTemplate.query(
                "SELECT d.id, d.knowledge_base_id, d.original_filename, d.object_key, d.content_type, d.file_size, "
                        + "d.status, d.processing_task_id, d.created_at, t.failure_reason, t.retry_count "
                        + "FROM knowledge_document d LEFT JOIN document_processing_task t "
                        + "ON t.task_id = d.processing_task_id WHERE d.knowledge_base_id = ? AND d.id = ?",
                (resultSet, rowNumber) -> new KnowledgeDocument(
                        resultSet.getLong("id"), resultSet.getLong("knowledge_base_id"),
                        resultSet.getString("original_filename"), resultSet.getString("object_key"),
                        resultSet.getString("content_type"), resultSet.getLong("file_size"),
                        resultSet.getString("status"), resultSet.getString("processing_task_id"),
                        toInstant(resultSet.getTimestamp("created_at")),
                        resultSet.getString("failure_reason"),
                        resultSet.getInt("retry_count")
                ),
                knowledgeBaseId, documentId
        );
        return documents.stream().findFirst();
    }

    @Override
    public void updateProcessingStatus(Long documentId, String processingTaskId, String status) {
        jdbcTemplate.update(
                "UPDATE knowledge_document SET processing_task_id = ?, status = ? WHERE id = ?",
                processingTaskId, status, documentId
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
