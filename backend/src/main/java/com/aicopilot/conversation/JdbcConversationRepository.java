package com.aicopilot.conversation;

import com.aicopilot.knowledge.KnowledgeRetrievalChunk;
import com.aicopilot.knowledge.KnowledgeAgentStep;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
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
    public List<ConversationSession> findSessions(Long knowledgeBaseId, Long userId, String query, int limit, long offset) {
        String normalizedQuery = query == null ? "" : query.trim();
        return jdbcTemplate.query(
                "SELECT id, knowledge_base_id, user_id, title, created_at, updated_at "
                        + "FROM conversation_session WHERE knowledge_base_id = ? AND user_id = ? "
                        + "AND (? = '' OR title LIKE ? OR EXISTS (SELECT 1 FROM conversation_message m "
                        + "WHERE m.session_id = conversation_session.id AND m.content LIKE ?)) "
                        + "ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?",
                (resultSet, rowNumber) -> new ConversationSession(
                        resultSet.getLong("id"), resultSet.getLong("knowledge_base_id"),
                        resultSet.getLong("user_id"), resultSet.getString("title"),
                        toInstant(resultSet.getTimestamp("created_at")), toInstant(resultSet.getTimestamp("updated_at"))
                ), knowledgeBaseId, userId, normalizedQuery, "%" + normalizedQuery + "%",
                "%" + normalizedQuery + "%", limit, offset
        );
    }

    @Override
    public long countSessions(Long knowledgeBaseId, Long userId, String query) {
        String normalizedQuery = query == null ? "" : query.trim();
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_session WHERE knowledge_base_id = ? AND user_id = ? "
                        + "AND (? = '' OR title LIKE ? OR EXISTS (SELECT 1 FROM conversation_message m "
                        + "WHERE m.session_id = conversation_session.id AND m.content LIKE ?))",
                Long.class,
                knowledgeBaseId, userId, normalizedQuery, "%" + normalizedQuery + "%", "%" + normalizedQuery + "%"
        );
        return total == null ? 0L : total;
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

    /**
     * 消息主体 + 来源 + Agent 步骤必须一起落库。
     *
     * <p>这是一段**短本地事务**，和 {@link ConversationService#askAgent} /
     * {@link ConversationService#streamAsk} 刻意不加事务并不冲突：调用方不持有事务，
     * 每条 saveMessage 各自借还连接，不会把数据库连接跨越模型 HTTP 调用绑死 90 秒。
     * 若来源或步骤写到一半失败（比如对象键为空触发 NOT NULL），主消息也会回滚 ——
     * 否则会留下一条「有提问没引用」的半成品助手消息，刷新历史后无从解释。
     */
    @Override
    @Transactional
    public ConversationMessage saveMessage(ConversationMessage message) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO conversation_message (session_id, role, content, created_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setLong(1, message.sessionId());
            statement.setString(2, message.role());
            statement.setString(3, message.content());
            statement.setTimestamp(4, Timestamp.from(message.createdAt()));
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) throw new IllegalStateException("database did not return generated message id");
        if (message.sources() != null) {
            for (KnowledgeRetrievalChunk source : message.sources()) {
                jdbcTemplate.update(
                        "INSERT INTO conversation_message_source (message_id, document_object_key, content, score, retriever) VALUES (?, ?, ?, ?, ?)",
                        id.longValue(), source.documentObjectKey(), source.content(), source.score(),
                        source.retriever() == null ? "vector" : source.retriever()
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
    public List<ConversationMessage> findMessages(Long sessionId, int limit, long offset) {
        // 倒序取窗口，再在 Java 里翻回正序：这样 offset=0 就是「最近的一批」，
        // offset 越大越往历史深处走，正好对上聊天界面「往上翻」的读法。
        List<ConversationMessage> latestFirst = loadMessages(
                "SELECT id, session_id, role, content, created_at FROM conversation_message "
                        + "WHERE session_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                sessionId, limit, offset
        );
        List<ConversationMessage> chronological = new ArrayList<>(latestFirst);
        Collections.reverse(chronological);
        return chronological;
    }

    @Override
    public long countMessages(Long sessionId) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conversation_message WHERE session_id = ?", Long.class, sessionId);
        return total == null ? 0L : total;
    }

    /**
     * 先取「消息主体」，再按这批 id 批量补齐来源与执行轨迹。
     *
     * <p>为什么拆成两步而不是像以前那样 LEFT JOIN 一把查出来再在 Java 里分组：
     * 一条消息关联 N 个来源就会展开成 N 行，而 {@code LIMIT} 限制的是**行数**、
     * 不是消息数 —— 于是「取 20 条消息」实际可能只取到 3 条，分页彻底失真。
     * 拆开之后 LIMIT 作用在消息表上，语义才是对的；顺带也不需要那个
     * 「用 LinkedHashMap 去重」的分组逻辑了。
     */
    private List<ConversationMessage> loadMessages(String sql, Long sessionId, int limit, long offset) {
        List<MessageHeader> headers = jdbcTemplate.query(sql,
                (resultSet, rowNumber) -> new MessageHeader(
                        resultSet.getLong("id"), resultSet.getLong("session_id"), resultSet.getString("role"),
                        resultSet.getString("content"), toInstant(resultSet.getTimestamp("created_at"))),
                sessionId, limit, offset
        );
        if (headers.isEmpty()) {
            return List.of();
        }
        List<Long> messageIds = headers.stream().map(MessageHeader::id).toList();
        Map<Long, List<KnowledgeRetrievalChunk>> sources = loadSources(messageIds);
        Map<Long, List<KnowledgeAgentStep>> agentSteps = loadAgentSteps(messageIds);
        return headers.stream()
                .map(header -> new ConversationMessage(
                        header.id(), header.sessionId(), header.role(), header.content(), header.createdAt(),
                        sources.getOrDefault(header.id(), List.of()),
                        agentSteps.getOrDefault(header.id(), List.of())
                ))
                .toList();
    }

    private Map<Long, List<KnowledgeRetrievalChunk>> loadSources(List<Long> messageIds) {
        Map<Long, List<KnowledgeRetrievalChunk>> sources = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT message_id, document_object_key, content, score, retriever FROM conversation_message_source "
                        + "WHERE message_id IN (" + placeholders(messageIds.size()) + ") ORDER BY message_id, id",
                (resultSet, rowNumber) -> {
                    String retriever = resultSet.getString("retriever");
                    sources.computeIfAbsent(resultSet.getLong("message_id"), id -> new ArrayList<>())
                            .add(new KnowledgeRetrievalChunk(
                                    resultSet.getString("document_object_key"),
                                    resultSet.getString("content"),
                                    resultSet.getObject("score", Double.class),
                                    retriever == null ? "vector" : retriever
                            ));
                    return null;
                }, messageIds.toArray()
        );
        return sources;
    }

    private Map<Long, List<KnowledgeAgentStep>> loadAgentSteps(List<Long> messageIds) {
        Map<Long, List<KnowledgeAgentStep>> agentSteps = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT message_id, tool, query, result_count FROM conversation_message_agent_step "
                        + "WHERE message_id IN (" + placeholders(messageIds.size()) + ") ORDER BY message_id, id",
                (resultSet, rowNumber) -> {
                    agentSteps.computeIfAbsent(resultSet.getLong("message_id"), id -> new ArrayList<>())
                            .add(new KnowledgeAgentStep(
                                    resultSet.getString("tool"),
                                    resultSet.getString("query"),
                                    resultSet.getInt("result_count")
                            ));
                    return null;
                }, messageIds.toArray()
        );
        return agentSteps;
    }

    /**
     * 生成 `?,?,?`。IN 子句的占位符数量取决于上一批查到几条消息，
     * 没法写成静态 SQL，只能动态拼 —— 注意拼的是**占位符**，值仍然走参数绑定，
     * 所以不存在 SQL 注入。
     */
    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    @Override
    public void touchSession(Long sessionId) {
        jdbcTemplate.update("UPDATE conversation_session SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", sessionId);
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record MessageHeader(Long id, Long sessionId, String role, String content, Instant createdAt) {
    }
}
