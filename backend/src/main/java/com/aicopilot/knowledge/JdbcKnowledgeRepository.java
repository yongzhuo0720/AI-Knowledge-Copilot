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
    public KnowledgeDocument saveDocument(KnowledgeDocument document) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO knowledge_document "
                            + "(knowledge_base_id, original_filename, object_key, content_type, file_size, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setLong(1, document.knowledgeBaseId());
            statement.setString(2, document.originalFilename());
            statement.setString(3, document.objectKey());
            statement.setString(4, document.contentType());
            statement.setLong(5, document.fileSize());
            statement.setString(6, document.status());
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
                Instant.now()
        );
    }

    @Override
    public List<KnowledgeDocument> findDocuments(Long knowledgeBaseId) {
        return jdbcTemplate.query(
                "SELECT id, knowledge_base_id, original_filename, object_key, content_type, file_size, status, created_at "
                        + "FROM knowledge_document WHERE knowledge_base_id = ? ORDER BY created_at, id",
                (resultSet, rowNumber) -> new KnowledgeDocument(
                        resultSet.getLong("id"),
                        resultSet.getLong("knowledge_base_id"),
                        resultSet.getString("original_filename"),
                        resultSet.getString("object_key"),
                        resultSet.getString("content_type"),
                        resultSet.getLong("file_size"),
                        resultSet.getString("status"),
                        toInstant(resultSet.getTimestamp("created_at"))
                ),
                knowledgeBaseId
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
