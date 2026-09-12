package com.aicopilot.conversation;

import com.aicopilot.knowledge.KnowledgeAnswer;
import com.aicopilot.knowledge.KnowledgeAnswerClient;
import com.aicopilot.knowledge.KnowledgeAgentAnswer;
import com.aicopilot.knowledge.KnowledgeAgentClient;
import com.aicopilot.knowledge.KnowledgeAgentStep;
import com.aicopilot.knowledge.KnowledgeRetrievalChunk;
import com.aicopilot.knowledge.KnowledgeService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class ConversationServiceTest {

    @Test
    void savesUserAndAssistantMessagesWithSources() {
        ConversationRepository repository = mock(ConversationRepository.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        KnowledgeAnswerClient answerClient = mock(KnowledgeAnswerClient.class);
        ConversationSession session = new ConversationSession(7L, 20L, 3L, "产品问答", Instant.now(), Instant.now());
        ConversationMessage previous = new ConversationMessage(1L, 7L, "USER", "之前的问题", Instant.now(), List.of());
        KnowledgeRetrievalChunk source = new KnowledgeRetrievalChunk("kb/20/guide.txt", "资料内容", 0.8);
        when(repository.findSession(7L, 20L, 3L)).thenReturn(java.util.Optional.of(session));
        when(repository.findMessages(7L)).thenReturn(List.of(previous));
        when(repository.saveMessage(any(ConversationMessage.class))).thenAnswer(invocation -> {
            ConversationMessage message = invocation.getArgument(0);
            return new ConversationMessage(message.role().equals("USER") ? 2L : 3L, message.sessionId(),
                    message.role(), message.content(), Instant.now(), message.sources());
        });
        when(answerClient.answer(20L, "当前问题", List.of(previous)))
                .thenReturn(new KnowledgeAnswer("回答内容", List.of(source)));

        ConversationReply reply = new ConversationService(repository, knowledgeService, answerClient)
                .ask(3L, 20L, 7L, new CreateMessageRequest("当前问题"));

        assertThat(reply.userMessage().role()).isEqualTo("USER");
        assertThat(reply.assistantMessage().content()).isEqualTo("回答内容");
        assertThat(reply.assistantMessage().sources()).containsExactly(source);
        verify(knowledgeService).assertKnowledgeBaseAccess(20L, 3L);
        verify(repository).touchSession(7L);
    }

    @Test
    void rejectsAccessToAnotherUsersSession() {
        ConversationRepository repository = mock(ConversationRepository.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        when(repository.findSession(7L, 20L, 99L)).thenReturn(java.util.Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new ConversationService(repository, knowledgeService, mock(KnowledgeAnswerClient.class))
                                .findMessages(99L, 20L, 7L))
                .isInstanceOf(com.aicopilot.common.exception.AccessDeniedException.class)
                .hasMessage("user cannot access this conversation");
        verify(repository, never()).findMessages(7L);
    }

    @Test
    void renamesOwnedSession() {
        ConversationRepository repository = mock(ConversationRepository.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        ConversationSession session = new ConversationSession(7L, 20L, 3L, "旧标题", Instant.now(), Instant.now());
        ConversationSession renamed = new ConversationSession(7L, 20L, 3L, "研发规范问答", Instant.now(), Instant.now());
        when(repository.findSession(7L, 20L, 3L)).thenReturn(java.util.Optional.of(session), java.util.Optional.of(renamed));

        ConversationSession result = new ConversationService(repository, knowledgeService, mock(KnowledgeAnswerClient.class))
                .renameSession(3L, 20L, 7L, new RenameConversationRequest(" 研发规范问答 "));

        assertThat(result.title()).isEqualTo("研发规范问答");
        verify(repository).updateSessionTitle(7L, "研发规范问答");
    }

    @Test
    void deletesOwnedSessionAndDoesNotAllowAnotherUsersSession() {
        ConversationRepository repository = mock(ConversationRepository.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        when(repository.findSession(7L, 20L, 3L)).thenReturn(java.util.Optional.of(
                new ConversationSession(7L, 20L, 3L, "产品问答", Instant.now(), Instant.now())
        ));

        ConversationService service = new ConversationService(repository, knowledgeService, mock(KnowledgeAnswerClient.class));
        service.deleteSession(3L, 20L, 7L);

        verify(repository).deleteSession(7L);
        when(repository.findSession(8L, 20L, 99L)).thenReturn(java.util.Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.deleteSession(99L, 20L, 8L))
                .isInstanceOf(com.aicopilot.common.exception.AccessDeniedException.class);
        verify(repository, never()).deleteSession(8L);
    }

    @Test
    void savesAgentAnswerSourcesAndExecutionSteps() {
        ConversationRepository repository = mock(ConversationRepository.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        KnowledgeAgentClient agentClient = mock(KnowledgeAgentClient.class);
        ConversationSession session = new ConversationSession(7L, 20L, 3L, "Agent 会话", Instant.now(), Instant.now());
        KnowledgeRetrievalChunk source = new KnowledgeRetrievalChunk("kb/20/guide.txt", "资料内容", 0.9);
        KnowledgeAgentStep step = new KnowledgeAgentStep("knowledge_search", "发布流程", 1);
        when(repository.findSession(7L, 20L, 3L)).thenReturn(java.util.Optional.of(session));
        when(repository.findMessages(7L)).thenReturn(List.of());
        when(repository.saveMessage(any(ConversationMessage.class))).thenAnswer(invocation -> {
            ConversationMessage message = invocation.getArgument(0);
            return new ConversationMessage(message.role().equals("USER") ? 2L : 3L, message.sessionId(),
                    message.role(), message.content(), Instant.now(), message.sources(), message.agentSteps());
        });
        when(agentClient.run(20L, "当前问题", List.of()))
                .thenReturn(new KnowledgeAgentAnswer("Agent 回答", List.of(source), List.of(step)));

        ConversationReply reply = new ConversationService(repository, knowledgeService, mock(KnowledgeAnswerClient.class), agentClient)
                .askAgent(3L, 20L, 7L, new CreateMessageRequest("当前问题"));

        assertThat(reply.assistantMessage().content()).isEqualTo("Agent 回答");
        assertThat(reply.assistantMessage().sources()).containsExactly(source);
        assertThat(reply.assistantMessage().agentSteps()).containsExactly(step);
        verify(repository).touchSession(7L);
    }
}
