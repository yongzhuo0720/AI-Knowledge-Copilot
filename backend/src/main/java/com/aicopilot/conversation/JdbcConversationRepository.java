package com.aicopilot.conversation;

import com.aicopilot.knowledge.KnowledgeRetrievalChunk;
import com.aicopilot.knowledge.KnowledgeAgentStep;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@Profile("local")
public class JdbcConversationRepository implements ConversationRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ConversationSession saveSession(ConversationSession session) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO conversation_session (knowledge_base_id, user_id, title) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setLong(1, session.knowledgeBaseId());
            statement.setLong(2, session.userId());
            statement.setString(3, session.title());
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) throw new IllegalStateException("database did not return generated session id");
        Instant now = Instant.now();
        return new ConversationSession(id.longValue(), session.knowledgeBaseId(), session.userId(), session.title(), now, now);
    }

    @Override
    public List<ConversationSession> findSessions(Long knowledgeBaseId, Long userId) {
        return jdbcTemplate.query(
                "SELECT id, knowledge_base_id, user_id, title, created_at, updated_at "
                        + "FROM conversation_session WHERE knowledge_base_id = ? AND user_id = ? "
                        + "ORDER BY updated_at DESC, id DESC",
                (resultSet, rowNumber) -> new ConversationSession(
                        resultSet.getLong("id"), resultSet.getLong("knowledge_base_id"),
                        resultSet.getLong("user_id"), resultSet.getString("title"),
                        toInstant(resultSet.getTimestamp("created_at")), toInstant(resultSet.getTimestamp("updated_at"))
                ), knowledgeBaseId, userId
        );
    }

    @Override
    public Optional<ConversationSession> findSession(Long sessionId, Long knowledgeBaseId, Long userId) {
        return jdbcTemplate.query(
                "SELECT id, knowledge_base_id, user_id, title, created_at, updated_at FROM conversation_session "
                        + "WHERE id = ? AND knowledge_base_id = ? AND user_id = ?",
                (resultSet, rowNumber) -> new ConversationSession(
                        resultSet.getLong("id"), resultSet.getLong("knowledge_base_id"),
                        resultSet.getLong("user_id"), resultSet.getString("title"),
                        toInstant(resultSet.getTimestamp("created_at")), toInstant(resultSet.getTimestamp("updated_at"))
                ), sessionId, knowledgeBaseId, userId
        ).stream().findFirst();
    }

    @Override
    public void updateSessionTitle(Long sessionId, String title) {
        jdbcTemplate.update(
                "UPDATE conversation_session SET title = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                title, sessionId
        );
    }

    @Override
    public void deleteSession(Long sessionId) {
        jdbcTemplate.update(
                "DELETE FROM conversation_message_agent_step WHERE message_id IN "
                        + "(SELECT id FROM conversation_message WHERE session_id = ?)",
                sessionId
        );
        jdbcTemplate.update(
                "DELETE FROM conversation_message_source WHERE message_id IN "
                        + "(SELECT id FROM conversation_message WHERE session_id = ?)",
                sessionId
        );
        jdbcTemplate.update("DELETE FROM conversation_message WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM conversation_session WHERE id = ?", sessionId);
    }

    @Override
    public ConversationMessage saveMessage(ConversationMessage message) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO conversation_message (session_id, role, content) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setLong(1, message.sessionId());
            statement.setString(2, message.role());
            statement.setString(3, message.content());
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) throw new IllegalStateException("database did not return generated message id");
        if (message.sources() != null) {
            for (KnowledgeRetrievalChunk source : message.sources()) {
                jdbcTemplate.update(
                        "INSERT INTO conversation_message_source (message_id, document_object_key, content, score) VALUES (?, ?, ?, ?)",
                        id.longValue(), source.documentObjectKey(), source.content(), source.score()
                );
            }
        }
        if (message.agentSteps() != null) {
            for (KnowledgeAgentStep step : message.agentSteps()) {
                jdbcTemplate.update(
                        "INSERT INTO conversation_message_agent_step (message_id, tool, query, result_count) VALUES (?, ?, ?, ?)",
                        id.longValue(), step.tool(), step.query(), step.resultCount()
                );
            }
        }
        return new ConversationMessage(id.longValue(), message.sessionId(), message.role(), message.content(), Instant.now(),
                message.sources() == null ? List.of() : message.sources(),
                message.agentSteps() == null ? List.of() : message.agentSteps());
    }

    @Override
    public List<ConversationMessage> findMessages(Long sessionId) {
        List<MessageRow> rows = jdbcTemplate.query(
                "SELECT m.id, m.session_id, m.role, m.content, m.created_at, "
                        + "s.document_object_key, s.content AS source_content, s.score "
                        + "FROM conversation_message m LEFT JOIN conversation_message_source s ON s.message_id = m.id "
                        + "WHERE m.session_id = ? ORDER BY m.created_at, m.id, s.id",
                (resultSet, rowNumber) -> new MessageRow(
                        resultSet.getLong("id"), resultSet.getLong("session_id"), resultSet.getString("role"),
                        resultSet.getString("content"), toInstant(resultSet.getTimestamp("created_at")),
                        resultSet.getString("document_object_key"), resultSet.getString("source_content"), resultSet.getObject("score", Double.class)
                ), sessionId
        );
        Map<Long, ConversationMessage> grouped = new LinkedHashMap<>();
        for (MessageRow row : rows) {
            ConversationMessage message = grouped.computeIfAbsent(row.id(), id -> new ConversationMessage(
                    row.id(), row.sessionId(), row.role(), row.content(), row.createdAt(), new ArrayList<>()));
            if (row.documentObjectKey() != null) {
                message.sources().add(new KnowledgeRetrievalChunk(row.documentObjectKey(), row.sourceContent(), row.score()));
            }
        }
        Map<Long, List<KnowledgeAgentStep>> agentSteps = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT message_id, tool, query, result_count FROM conversation_message_agent_step "
                        + "WHERE message_id IN (SELECT id FROM conversation_message WHERE session_id = ?) "
                        + "ORDER BY message_id, id",
                (resultSet, rowNumber) -> {
                    agentSteps.computeIfAbsent(resultSet.getLong("message_id"), id -> new ArrayList<>())
                            .add(new KnowledgeAgentStep(
                                    resultSet.getString("tool"),
                                    resultSet.getString("query"),
                                    resultSet.getInt("result_count")
                            ));
                    return null;
                }, sessionId
        );
        return grouped.values().stream().map(message -> new ConversationMessage(
                message.id(), message.sessionId(), message.role(), message.content(), message.createdAt(),
                List.copyOf(message.sources()),
                List.copyOf(agentSteps.getOrDefault(message.id(), List.of()))
        )).toList();
    }

    @Override
    public void touchSession(Long sessionId) {
        jdbcTemplate.update("UPDATE conversation_session SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", sessionId);
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record MessageRow(Long id, Long sessionId, String role, String content, Instant createdAt,
                              String documentObjectKey, String sourceContent, Double score) {
    }
}
