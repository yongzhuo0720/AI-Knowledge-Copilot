package com.aicopilot.conversation;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository {

    ConversationSession saveSession(ConversationSession session);

    List<ConversationSession> findSessions(Long knowledgeBaseId, Long userId);

    Optional<ConversationSession> findSession(Long sessionId, Long knowledgeBaseId, Long userId);

    ConversationMessage saveMessage(ConversationMessage message);

    List<ConversationMessage> findMessages(Long sessionId);

    void touchSession(Long sessionId);
}
