package com.aicopilot.conversation;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository {

    ConversationSession saveSession(ConversationSession session);

    /**
     * 会话列表分页。{@code query} 为空串表示不过滤。
     *
     * <p>排序必须是**确定**的（这里用 {@code updated_at DESC, id DESC}）。
     * 只按 updated_at 排的话，同一秒更新的两条会话顺序不确定，
     * 翻页时会出现「第 1 页见过、第 2 页又见到」或者「两条都没出现」。
     * 加 id 兜底是为了让排序在时间相同时仍然唯一。
     */
    List<ConversationSession> findSessions(Long knowledgeBaseId, Long userId, String query, int limit, long offset);

    long countSessions(Long knowledgeBaseId, Long userId, String query);

    Optional<ConversationSession> findSession(Long sessionId, Long knowledgeBaseId, Long userId);

    void updateSessionTitle(Long sessionId, String title);

    void deleteSession(Long sessionId);

    ConversationMessage saveMessage(ConversationMessage message);

    /**
     * 消息分页。**页号从最新一条往回数**：offset=0 拿到的是最近的一批，
     * offset=limit 是再往前的一批；返回结果内部按时间正序排列。
     *
     * <p>所以 {@code findMessages(sessionId, 20, 0)} 同时也是「取最近 20 条历史」，
     * 拼给模型做上下文用的就是它 —— 一个方法同时服务「界面翻页」和「上下文窗口」。
     */
    List<ConversationMessage> findMessages(Long sessionId, int limit, long offset);

    long countMessages(Long sessionId);

    void touchSession(Long sessionId);
}
