package com.aicopilot.conversation;

import com.aicopilot.common.api.PageQuery;
import com.aicopilot.common.api.PageResponse;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class ConversationServiceTest {

    @Test
    void streamsUserSourcesAndCompletionWithAssistantSources() {
        ConversationRepository repository = mock(ConversationRepository.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        KnowledgeAnswerClient answerClient = mock(KnowledgeAnswerClient.class);
        ConversationSession session = new ConversationSession(7L, 20L, 3L, "产品问答", Instant.now(), Instant.now());
        ConversationMessage previous = new ConversationMessage(1L, 7L, "USER", "之前的问题", Instant.now(), List.of());
        KnowledgeRetrievalChunk source = new KnowledgeRetrievalChunk("kb/20/guide.txt", "资料内容", 0.8, "hybrid");
        when(repository.findSession(7L, 20L, 3L)).thenReturn(java.util.Optional.of(session));
        when(repository.findMessages(7L, 20, 0)).thenReturn(List.of(previous));
        when(repository.saveMessage(any(ConversationMessage.class))).thenAnswer(invocation -> {
            ConversationMessage message = invocation.getArgument(0);
            return new ConversationMessage(message.role().equals("USER") ? 2L : 3L, message.sessionId(),
                    message.role(), message.content(), Instant.now(), message.sources());
        });
        when(answerClient.stream(eq(20L), eq("当前问题"), eq(List.of(previous)), any()))
                .thenReturn(new KnowledgeAnswer("回答内容", List.of(source)));
        List<ConversationStreamEvent> events = new java.util.ArrayList<>();

        new ConversationService(repository, knowledgeService, answerClient, mock(KnowledgeAgentClient.class))
                .streamAsk(3L, 20L, 7L, new CreateMessageRequest("当前问题"), events::add);

        assertThat(events).extracting(ConversationStreamEvent::event)
                .containsExactly("user", "sources", "complete");
        ConversationReply complete = (ConversationReply) events.get(2).data();
        assertThat(complete.userMessage().role()).isEqualTo("USER");
        assertThat(complete.assistantMessage().content()).isEqualTo("回答内容");
        assertThat(complete.assistantMessage().sources()).containsExactly(source);
        verify(knowledgeService).assertKnowledgeBaseAccess(20L, 3L);
        verify(repository).touchSession(7L);
    }

    @Test
    void sendsOnlyTheRecentHistoryWindowToTheModel() {
        ConversationRepository repository = mock(ConversationRepository.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        KnowledgeAnswerClient answerClient = mock(KnowledgeAnswerClient.class);
        when(repository.findSession(7L, 20L, 3L)).thenReturn(java.util.Optional.of(
                new ConversationSession(7L, 20L, 3L, "长会话", Instant.now(), Instant.now())));
        when(repository.findMessages(7L, 20, 0)).thenReturn(List.of());
        when(repository.saveMessage(any(ConversationMessage.class))).thenAnswer(invocation -> {
            ConversationMessage message = invocation.getArgument(0);
            return new ConversationMessage(9L, message.sessionId(), message.role(), message.content(),
                    Instant.now(), message.sources());
        });
        when(answerClient.stream(eq(20L), eq("当前问题"), eq(List.of()), any()))
                .thenReturn(new KnowledgeAnswer("回答", List.of()));

        new ConversationService(repository, knowledgeService, answerClient, mock(KnowledgeAgentClient.class))
                .streamAsk(3L, 20L, 7L, new CreateMessageRequest("当前问题"), event -> { });

        // 这条断言守的是「成本不会随会话长度无界增长」：
        // 无论会话聊到第 3 轮还是第 300 轮，喂给模型的历史都只能是最近 20 条。
        // 如果哪天有人把它改回「查全部」，这个测试会立刻红。
        verify(repository).findMessages(7L, 20, 0);
        // 而且整个提问过程只查这一次历史 —— 多查一次就说明有人又加了一条「查全部」的路径
        verify(repository, times(1)).findMessages(anyLong(), anyInt(), anyLong());
    }

    @Test
    void rejectsAccessToAnotherUsersSession() {
        ConversationRepository repository = mock(ConversationRepository.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        when(repository.findSession(7L, 20L, 99L)).thenReturn(java.util.Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new ConversationService(repository, knowledgeService, mock(KnowledgeAnswerClient.class), mock(KnowledgeAgentClient.class))
                                .findMessages(99L, 20L, 7L, new com.aicopilot.common.api.PageQuery(0, 20)))
                .isInstanceOf(com.aicopilot.common.exception.AccessDeniedException.class)
                .hasMessage("user cannot access this conversation");
        verify(repository, never()).findMessages(any(Long.class), anyInt(), anyLong());
    }

    @Test
    void renamesOwnedSession() {
        ConversationRepository repository = mock(ConversationRepository.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        ConversationSession session = new ConversationSession(7L, 20L, 3L, "旧标题", Instant.now(), Instant.now());
        ConversationSession renamed = new ConversationSession(7L, 20L, 3L, "研发规范问答", Instant.now(), Instant.now());
        when(repository.findSession(7L, 20L, 3L)).thenReturn(java.util.Optional.of(session), java.util.Optional.of(renamed));

        ConversationSession result = new ConversationService(repository, knowledgeService, mock(KnowledgeAnswerClient.class), mock(KnowledgeAgentClient.class))
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

        ConversationService service = new ConversationService(repository, knowledgeService, mock(KnowledgeAnswerClient.class), mock(KnowledgeAgentClient.class));
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
        KnowledgeRetrievalChunk source = new KnowledgeRetrievalChunk("kb/20/guide.txt", "资料内容", 0.9, "hybrid");
        KnowledgeAgentStep step = new KnowledgeAgentStep("knowledge_search", "发布流程", 1);
        when(repository.findSession(7L, 20L, 3L)).thenReturn(java.util.Optional.of(session));
        when(repository.findMessages(7L, 20, 0)).thenReturn(List.of());
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

    @Test
    void keepsTheUserMessageWhenTheAgentCallFails() {
        ConversationRepository repository = mock(ConversationRepository.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        KnowledgeAgentClient agentClient = mock(KnowledgeAgentClient.class);
        ConversationSession session = new ConversationSession(7L, 20L, 3L, "Agent 会话", Instant.now(), Instant.now());
        when(repository.findSession(7L, 20L, 3L)).thenReturn(java.util.Optional.of(session));
        when(repository.findMessages(7L, 20, 0)).thenReturn(List.of());
        when(repository.saveMessage(any(ConversationMessage.class))).thenAnswer(invocation -> {
            ConversationMessage message = invocation.getArgument(0);
            return new ConversationMessage(2L, message.sessionId(), message.role(), message.content(),
                    Instant.now(), message.sources(), message.agentSteps());
        });
        when(agentClient.run(anyLong(), anyString(), any()))
                .thenThrow(new IllegalStateException("ai service down"));

        // 模型调用在事务外执行（连接不跨 HTTP 持有），代价是失败时不再整体回滚：
        // 提问记录保留，错误提示交给调用方——与主流聊天产品的行为一致。
        assertThatThrownBy(() -> new ConversationService(repository, knowledgeService,
                        mock(KnowledgeAnswerClient.class), agentClient)
                .askAgent(3L, 20L, 7L, new CreateMessageRequest("当前问题")))
                .isInstanceOf(IllegalStateException.class);
        verify(repository).saveMessage(any(ConversationMessage.class));
        verify(repository, never()).touchSession(7L);
    }

    @Test
    void keepsTheUserMessageAndStopsTheEventFlowWhenStreamingFails() {
        ConversationRepository repository = mock(ConversationRepository.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        KnowledgeAnswerClient answerClient = mock(KnowledgeAnswerClient.class);
        ConversationSession session = new ConversationSession(7L, 20L, 3L, "产品问答", Instant.now(), Instant.now());
        when(repository.findSession(7L, 20L, 3L)).thenReturn(java.util.Optional.of(session));
        when(repository.findMessages(7L, 20, 0)).thenReturn(List.of());
        when(repository.saveMessage(any(ConversationMessage.class))).thenAnswer(invocation -> {
            ConversationMessage message = invocation.getArgument(0);
            return new ConversationMessage(2L, message.sessionId(), message.role(), message.content(),
                    Instant.now(), message.sources());
        });
        when(answerClient.stream(eq(20L), eq("当前问题"), eq(List.of()), any()))
                .thenThrow(new IllegalStateException("ai service down"));
        List<ConversationStreamEvent> events = new java.util.ArrayList<>();

        assertThatThrownBy(() -> new ConversationService(repository, knowledgeService, answerClient,
                        mock(KnowledgeAgentClient.class))
                .streamAsk(3L, 20L, 7L, new CreateMessageRequest("当前问题"), events::add))
                .isInstanceOf(IllegalStateException.class);

        // 流失败后只有 user 事件，绝不能有 sources/complete（半截答案当成功是另一类 bug）
        assertThat(events).extracting(ConversationStreamEvent::event).containsExactly("user");
        verify(repository).saveMessage(any(ConversationMessage.class));
    }

    @Test
    void forwardsConversationSearchAfterCheckingAccess() {
        ConversationRepository repository = mock(ConversationRepository.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        ConversationSession session = new ConversationSession(7L, 20L, 3L, "发布流程", Instant.now(), Instant.now());
        when(repository.findSessions(20L, 3L, "发布", 5, 10L)).thenReturn(List.of(session));
        when(repository.countSessions(20L, 3L, "发布")).thenReturn(17L);

        PageResponse<ConversationSession> result = new ConversationService(
                repository, knowledgeService, mock(KnowledgeAnswerClient.class), mock(KnowledgeAgentClient.class)
        ).findSessions(3L, 20L, "发布", new PageQuery(2, 5));

        assertThat(result.items()).containsExactly(session);
        // 搜索条件、分页参数都要原样传到 SQL 层，并且总数要能对上
        assertThat(result.totalElements()).isEqualTo(17);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(5);
        verify(knowledgeService).assertKnowledgeBaseAccess(20L, 3L);
        verify(repository).findSessions(20L, 3L, "发布", 5, 10L);
    }

    @Test
    void reportsWhetherThereAreOlderMessagesToLoad() {
        ConversationRepository repository = mock(ConversationRepository.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        when(repository.findSession(7L, 20L, 3L)).thenReturn(java.util.Optional.of(
                new ConversationSession(7L, 20L, 3L, "长会话", Instant.now(), Instant.now())));
        ConversationMessage message = new ConversationMessage(
                30L, 7L, "USER", "最近一句", Instant.now(), List.of());
        when(repository.findMessages(7L, 20, 0L)).thenReturn(List.of(message));
        when(repository.countMessages(7L)).thenReturn(137L);

        PageResponse<ConversationMessage> firstPage = new ConversationService(
                repository, knowledgeService, mock(KnowledgeAnswerClient.class), mock(KnowledgeAgentClient.class)
        ).findMessages(3L, 20L, 7L, PageQuery.of(0, 20));

        assertThat(firstPage.items()).containsExactly(message);
        assertThat(firstPage.totalElements()).isEqualTo(137);
        assertThat(firstPage.totalPages()).isEqualTo(7);
        // 前端靠它决定要不要显示「加载更早的消息」
        assertThat(firstPage.hasNext()).isTrue();
    }

    @Test
    void reportsNoMorePagesWhenTheLastWindowIsReached() {
        ConversationRepository repository = mock(ConversationRepository.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        when(repository.findSession(7L, 20L, 3L)).thenReturn(java.util.Optional.of(
                new ConversationSession(7L, 20L, 3L, "短会话", Instant.now(), Instant.now())));
        ConversationMessage message = new ConversationMessage(1L, 7L, "USER", "第一句", Instant.now(), List.of());
        when(repository.findMessages(7L, 20, 20L)).thenReturn(List.of(message));
        when(repository.countMessages(7L)).thenReturn(21L);

        PageResponse<ConversationMessage> lastPage = new ConversationService(
                repository, knowledgeService, mock(KnowledgeAnswerClient.class), mock(KnowledgeAgentClient.class)
        ).findMessages(3L, 20L, 7L, PageQuery.of(1, 20));

        assertThat(lastPage.hasNext()).isFalse();
        assertThat(lastPage.totalPages()).isEqualTo(2);
    }

    @Test
    void clampsAnOversizedPageRequestInsteadOfTrustingTheClient() {
        ConversationRepository repository = mock(ConversationRepository.class);
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        when(repository.findSession(7L, 20L, 3L)).thenReturn(java.util.Optional.of(
                new ConversationSession(7L, 20L, 3L, "会话", Instant.now(), Instant.now())));
        when(repository.findMessages(eq(7L), anyInt(), anyLong())).thenReturn(List.of());
        when(repository.countMessages(7L)).thenReturn(0L);

        new ConversationService(repository, knowledgeService, mock(KnowledgeAnswerClient.class), mock(KnowledgeAgentClient.class))
                .findMessages(3L, 20L, 7L, PageQuery.of(-3, 1_000_000));

        // `?size=1000000` 不能真的变成「一次捞一百万条」
        verify(repository).findMessages(7L, 100, 0L);
    }
}
