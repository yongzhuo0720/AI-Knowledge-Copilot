package com.aicopilot.conversation;

import com.aicopilot.common.api.PageQuery;
import com.aicopilot.common.api.PageResponse;
import com.aicopilot.common.exception.AccessDeniedException;
import com.aicopilot.knowledge.KnowledgeAnswer;
import com.aicopilot.knowledge.KnowledgeAnswerClient;
import com.aicopilot.knowledge.KnowledgeAgentAnswer;
import com.aicopilot.knowledge.KnowledgeAgentClient;
import org.springframework.beans.factory.annotation.Autowired;
import com.aicopilot.knowledge.KnowledgeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

@Service
public class ConversationService {

    /**
     * 送给模型的**历史消息条数上限**。
     *
     * <p>这个数字直接决定每次问答的输入 token 量。以前是把整个会话的消息全塞给模型，
     * 一个聊到 200 轮的会话，每次提问都要把前面 400 条消息重新计费一遍：
     * 越聊越贵、越聊越慢，最后撞上下文窗口直接报错。
     *
     * <p>为什么取 20：一轮问答是「用户 + 助手」两条，20 条约等于最近 10 轮对话，
     * 足够支撑「刚才说的那个」这类指代；再多的话收益递减而成本线性上升。
     * 更讲究的做法是「按 token 数裁剪」而不是「按条数」（长消息和短消息差很多），
     * 但按条数实现简单、结果可预期，作为第一版是合理选择。
     */
    private static final int HISTORY_MESSAGE_LIMIT = 20;

    private final ConversationRepository conversationRepository;
    private final KnowledgeService knowledgeService;
    private final KnowledgeAnswerClient answerClient;
    private final KnowledgeAgentClient agentClient;

    public ConversationService(
            ConversationRepository conversationRepository,
            KnowledgeService knowledgeService,
            KnowledgeAnswerClient answerClient
    ) {
        this(conversationRepository, knowledgeService, answerClient, null);
    }

    @Autowired
    public ConversationService(
            ConversationRepository conversationRepository,
            KnowledgeService knowledgeService,
            KnowledgeAnswerClient answerClient,
            KnowledgeAgentClient agentClient
    ) {
        this.conversationRepository = conversationRepository;
        this.knowledgeService = knowledgeService;
        this.answerClient = answerClient;
        this.agentClient = agentClient;
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
    public PageResponse<ConversationSession> findSessions(
            Long userId, Long knowledgeBaseId, String query, PageQuery pageQuery) {
        knowledgeService.assertKnowledgeBaseAccess(knowledgeBaseId, userId);
        List<ConversationSession> items = conversationRepository.findSessions(
                knowledgeBaseId, userId, query, pageQuery.size(), pageQuery.offset());
        // 先查列表再查总数：如果先查总数、后查列表，中间插进来一条新会话，
        // 返回的 totalElements 就会比实际能翻到的条数多一条，前端会显示一个点不开的「下一页」。
        // 反过来（列表新、总数旧）顶多是少显示一条，用户再点一次就看到了，危害小得多。
        long totalElements = conversationRepository.countSessions(knowledgeBaseId, userId, query);
        return PageResponse.of(items, pageQuery, totalElements);
    }

    /**
     * 会话消息分页。**页号从最新一条往回数**：page=0 是最近的一批，
     * page=1 是再往前的一批。
     *
     * <p>为什么不是「从第一条往后翻」：聊天界面打开会话时先看到的是最新消息，
     * 如果 page=0 返回的是最早的 20 条，用户打开一个长会话只能看到开头几个月前的
     * 寒暄，还得一路点到最后一页 —— 这正是把「列表分页」的直觉硬套到「消息流」上的典型错误。
     */
    @Transactional(readOnly = true)
    public PageResponse<ConversationMessage> findMessages(
            Long userId, Long knowledgeBaseId, Long sessionId, PageQuery pageQuery) {
        findOwnedSession(userId, knowledgeBaseId, sessionId);
        List<ConversationMessage> items = conversationRepository.findMessages(
                sessionId, pageQuery.size(), pageQuery.offset());
        long totalElements = conversationRepository.countMessages(sessionId);
        return PageResponse.of(items, pageQuery, totalElements);
    }

    @Transactional
    public ConversationSession renameSession(Long userId, Long knowledgeBaseId, Long sessionId,
                                              RenameConversationRequest request) {
        findOwnedSession(userId, knowledgeBaseId, sessionId);
        conversationRepository.updateSessionTitle(sessionId, request.title().trim());
        return conversationRepository.findSession(sessionId, knowledgeBaseId, userId)
                .orElseThrow(() -> new AccessDeniedException("user cannot access this conversation"));
    }

    @Transactional
    public void deleteSession(Long userId, Long knowledgeBaseId, Long sessionId) {
        findOwnedSession(userId, knowledgeBaseId, sessionId);
        conversationRepository.deleteSession(sessionId);
    }

    @Transactional
    public ConversationReply ask(Long userId, Long knowledgeBaseId, Long sessionId, CreateMessageRequest request) {
        findOwnedSession(userId, knowledgeBaseId, sessionId);
        List<ConversationMessage> history = recentHistory(sessionId);
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
    public ConversationReply askAgent(Long userId, Long knowledgeBaseId, Long sessionId, CreateMessageRequest request) {
        findOwnedSession(userId, knowledgeBaseId, sessionId);
        if (agentClient == null) {
            throw new IllegalStateException("knowledge agent is not configured");
        }
        List<ConversationMessage> history = recentHistory(sessionId);
        String question = request.question().trim();
        ConversationMessage userMessage = conversationRepository.saveMessage(new ConversationMessage(
                null, sessionId, "USER", question, Instant.now(), List.of()
        ));
        KnowledgeAgentAnswer answer = agentClient.run(knowledgeBaseId, question, history);
        ConversationMessage assistantMessage = conversationRepository.saveMessage(new ConversationMessage(
                null, sessionId, "ASSISTANT", answer.answer(), Instant.now(), answer.sources(), answer.steps()
        ));
        conversationRepository.touchSession(sessionId);
        return new ConversationReply(userMessage, assistantMessage);
    }

    @Transactional
    public void streamAsk(Long userId, Long knowledgeBaseId, Long sessionId, CreateMessageRequest request,
                          Consumer<ConversationStreamEvent> onEvent) {
        findOwnedSession(userId, knowledgeBaseId, sessionId);
        List<ConversationMessage> history = recentHistory(sessionId);
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

    /**
     * 取送给模型的历史上下文：**最近** {@link #HISTORY_MESSAGE_LIMIT} 条，按时间正序。
     *
     * <p>{@code findMessages(sessionId, limit, 0)} 的语义就是「从最新往回取 limit 条」，
     * 所以这里不需要另开一个查询方法 —— 界面翻页和上下文窗口用的是同一个查询，
     * 只是 offset 不同。
     */
    private List<ConversationMessage> recentHistory(Long sessionId) {
        return conversationRepository.findMessages(sessionId, HISTORY_MESSAGE_LIMIT, 0);
    }

    private ConversationSession findOwnedSession(Long userId, Long knowledgeBaseId, Long sessionId) {
        knowledgeService.assertKnowledgeBaseAccess(knowledgeBaseId, userId);
        return conversationRepository.findSession(sessionId, knowledgeBaseId, userId)
                .orElseThrow(() -> new AccessDeniedException("user cannot access this conversation"));
    }
}
