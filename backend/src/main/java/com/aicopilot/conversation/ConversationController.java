package com.aicopilot.conversation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.aicopilot.common.api.ApiResponse;
import com.aicopilot.common.api.PageQuery;
import com.aicopilot.common.api.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletRequest;
import com.aicopilot.user.AuthenticationInterceptor;
import com.aicopilot.common.ratelimit.RateLimitProperties;
import com.aicopilot.common.ratelimit.RateLimiter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/conversations")
@Tag(name = "会话与消息", description = "会话增删改查，以及 SSE 流式问答")
public class ConversationController {

    /**
     * 限流桶名。ask 桶管的是「要花钱的接口」。
     */
    private static final String ASK_BUCKET = "ask";

    private final ConversationService conversationService;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public ConversationController(
            ConversationService conversationService,
            RateLimiter rateLimiter,
            RateLimitProperties rateLimitProperties
    ) {
        this.conversationService = conversationService;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationSession> createSession(
            @PathVariable Long knowledgeBaseId,
            HttpServletRequest httpRequest,
            @Valid @RequestBody CreateConversationRequest request
    ) {
        return ApiResponse.success(conversationService.createSession(authenticatedUserId(httpRequest), knowledgeBaseId, request));
    }

    @GetMapping
    public ApiResponse<PageResponse<ConversationSession>> findSessions(
            @PathVariable Long knowledgeBaseId,
            HttpServletRequest httpRequest,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return ApiResponse.success(conversationService.findSessions(
                authenticatedUserId(httpRequest), knowledgeBaseId, query, PageQuery.of(page, size)));
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResponse<PageResponse<ConversationMessage>> findMessages(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long sessionId,
            HttpServletRequest httpRequest,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return ApiResponse.success(conversationService.findMessages(
                authenticatedUserId(httpRequest), knowledgeBaseId, sessionId, PageQuery.of(page, size)));
    }

    @PatchMapping("/{sessionId}")
    public ApiResponse<ConversationSession> renameSession(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long sessionId,
            HttpServletRequest httpRequest,
            @Valid @RequestBody RenameConversationRequest request
    ) {
        return ApiResponse.success(conversationService.renameSession(
                authenticatedUserId(httpRequest), knowledgeBaseId, sessionId, request
        ));
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long sessionId,
            HttpServletRequest httpRequest
    ) {
        conversationService.deleteSession(authenticatedUserId(httpRequest), knowledgeBaseId, sessionId);
    }

    @PostMapping("/{sessionId}/messages")
    public ApiResponse<ConversationReply> ask(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long sessionId,
            HttpServletRequest httpRequest,
            @Valid @RequestBody CreateMessageRequest request
    ) {
        Long userId = authenticatedUserId(httpRequest);
        consumeAskQuota(userId);
        return ApiResponse.success(conversationService.ask(userId, knowledgeBaseId, sessionId, request));
    }

    @PostMapping("/{sessionId}/agent-messages")
    public ApiResponse<ConversationReply> askAgent(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long sessionId,
            HttpServletRequest httpRequest,
            @Valid @RequestBody CreateMessageRequest request
    ) {
        Long userId = authenticatedUserId(httpRequest);
        consumeAskQuota(userId);
        return ApiResponse.success(conversationService.askAgent(userId, knowledgeBaseId, sessionId, request));
    }

    @PostMapping(value = "/{sessionId}/messages/stream", produces = "text/event-stream")
    public SseEmitter stream(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long sessionId,
            HttpServletRequest httpRequest,
            @Valid @RequestBody CreateMessageRequest request
    ) {
        Long userId = authenticatedUserId(httpRequest);
        // 必须在 new SseEmitter() 之前扣配额，否则拒绝时响应头已经按 200 + event-stream 发出去了。
        consumeAskQuota(userId);
        SseEmitter emitter = new SseEmitter(120_000L);
        CompletableFuture.runAsync(() -> {
            try {
                conversationService.streamAsk(userId, knowledgeBaseId, sessionId, request, event -> {
                    try {
                        emitter.send(SseEmitter.event().name(event.event()).data(event.data()));
                    } catch (Exception exception) {
                        throw new IllegalStateException("client disconnected", exception);
                    }
                });
                emitter.complete();
            } catch (Exception exception) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(java.util.Map.of("message", exception.getMessage() == null ? "stream failed" : exception.getMessage())));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    private Long authenticatedUserId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthenticationInterceptor.AUTHENTICATED_USER_ID);
    }

    /**
     * 扣一次「要花钱的接口」配额。
     *
     * <p>为什么放在 Controller 而不是 Service：**流式接口**要求这个判断发生在
     * 打开 SSE 连接之前。一旦进了异步线程再抛限流异常，HTTP 响应头早就发出去了，
     * 想拒绝只能改成往事件流里塞一个 error 帧 —— 前端拿不到 429 状态码，
     * 也就分不清「我被限流了」和「模型调用失败了」，提示语和重试策略都会写错。
     *
     * <p>计数的维度是**用户**而不是 IP：额度是跟账号绑定的（同一账号换网络也该受同样的限制），
     * 而且用户 id 已经由鉴权拦截器解析好了，不需要再去信任 X-Forwarded-For 这类可伪造的头。
     */
    private void consumeAskQuota(Long userId) {
        rateLimiter.consume(ASK_BUCKET, String.valueOf(userId), rateLimitProperties.getAsk());
    }
}
