package com.aicopilot.conversation;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository {

    ConversationSession saveSession(ConversationSession session);

    List<ConversationSession> findSessions(Long knowledgeBaseId, Long userId, String query);

    default List<ConversationSession> findSessions(Long knowledgeBaseId, Long userId) {
        return findSessions(knowledgeBaseId, userId, "");
    }

    Optional<ConversationSession> findSession(Long sessionId, Long knowledgeBaseId, Long userId);

    void updateSessionTitle(Long sessionId, String title);

    void deleteSession(Long sessionId);

    ConversationMessage saveMessage(ConversationMessage message);

    List<ConversationMessage> findMessages(Long sessionId);

    void touchSession(Long sessionId);
}
