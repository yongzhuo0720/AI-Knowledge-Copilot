package com.aicopilot.conversation;

import com.aicopilot.common.exception.AccessDeniedException;
import com.aicopilot.knowledge.KnowledgeAnswer;
import com.aicopilot.knowledge.KnowledgeAnswerClient;
import com.aicopilot.knowledge.KnowledgeService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

@Service
@Profile("local")
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final KnowledgeService knowledgeService;
    private final KnowledgeAnswerClient answerClient;

    public ConversationService(
            ConversationRepository conversationRepository,
            KnowledgeService knowledgeService,
            KnowledgeAnswerClient answerClient
    ) {
        this.conversationRepository = conversationRepository;
        this.knowledgeService = knowledgeService;
        this.answerClient = answerClient;
    }

    @Transactional
    public ConversationSession createSession(Long userId, Long knowledgeBaseId, CreateConversationRequest request) {
        knowledgeService.assertKnowledgeBaseAccess(knowledgeBaseId, userId);
        String title = request.title() == null || request.title().isBlank()
                ? "新会话"
                : request.title().trim();
        return conversationRepository.saveSession(new ConversationSession(
                null, knowledgeBaseId, userId, title, Instant.now(), Instant.now()
        ));
    }

    @Transactional(readOnly = true)
    public List<ConversationSession> findSessions(Long userId, Long knowledgeBaseId) {
        knowledgeService.assertKnowledgeBaseAccess(knowledgeBaseId, userId);
        return conversationRepository.findSessions(knowledgeBaseId, userId);
    }

    @Transactional(readOnly = true)
    public List<ConversationMessage> findMessages(Long userId, Long knowledgeBaseId, Long sessionId) {
        findOwnedSession(userId, knowledgeBaseId, sessionId);
        return conversationRepository.findMessages(sessionId);
    }

    @Transactional
    public ConversationReply ask(Long userId, Long knowledgeBaseId, Long sessionId, CreateMessageRequest request) {
        findOwnedSession(userId, knowledgeBaseId, sessionId);
        List<ConversationMessage> history = conversationRepository.findMessages(sessionId);
        ConversationMessage userMessage = conversationRepository.saveMessage(new ConversationMessage(
                null, sessionId, "USER", request.question().trim(), Instant.now(), List.of()
        ));
        KnowledgeAnswer answer = answerClient.answer(knowledgeBaseId, request.question().trim(), history);
        ConversationMessage assistantMessage = conversationRepository.saveMessage(new ConversationMessage(
                null, sessionId, "ASSISTANT", answer.answer(), Instant.now(), answer.sources()
        ));
        conversationRepository.touchSession(sessionId);
        return new ConversationReply(userMessage, assistantMessage);
    }

    @Transactional
    public void streamAsk(Long userId, Long knowledgeBaseId, Long sessionId, CreateMessageRequest request,
                          Consumer<ConversationStreamEvent> onEvent) {
        findOwnedSession(userId, knowledgeBaseId, sessionId);
        List<ConversationMessage> history = conversationRepository.findMessages(sessionId);
        String question = request.question().trim();
        ConversationMessage userMessage = conversationRepository.saveMessage(new ConversationMessage(
                null, sessionId, "USER", question, Instant.now(), List.of()
        ));
        onEvent.accept(new ConversationStreamEvent("user", userMessage));
        KnowledgeAnswer answer = answerClient.stream(knowledgeBaseId, question, history, delta ->
                onEvent.accept(new ConversationStreamEvent("delta", java.util.Map.of("content", delta)))
        );
        onEvent.accept(new ConversationStreamEvent("sources", java.util.Map.of("sources", answer.sources())));
        ConversationMessage assistantMessage = conversationRepository.saveMessage(new ConversationMessage(
                null, sessionId, "ASSISTANT", answer.answer(), Instant.now(), answer.sources()
        ));
        conversationRepository.touchSession(sessionId);
        onEvent.accept(new ConversationStreamEvent("complete", new ConversationReply(userMessage, assistantMessage)));
    }

    private ConversationSession findOwnedSession(Long userId, Long knowledgeBaseId, Long sessionId) {
        knowledgeService.assertKnowledgeBaseAccess(knowledgeBaseId, userId);
        return conversationRepository.findSession(sessionId, knowledgeBaseId, userId)
                .orElseThrow(() -> new AccessDeniedException("user cannot access this conversation"));
    }
}
