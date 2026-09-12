package com.aicopilot.conversation;

import com.aicopilot.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
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

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/conversations")
@Profile("local")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
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
    public ApiResponse<List<ConversationSession>> findSessions(
            @PathVariable Long knowledgeBaseId,
            HttpServletRequest httpRequest,
            @RequestParam(defaultValue = "") String query
    ) {
        return ApiResponse.success(conversationService.findSessions(authenticatedUserId(httpRequest), knowledgeBaseId, query));
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResponse<List<ConversationMessage>> findMessages(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long sessionId,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(conversationService.findMessages(authenticatedUserId(httpRequest), knowledgeBaseId, sessionId));
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
        return ApiResponse.success(conversationService.ask(authenticatedUserId(httpRequest), knowledgeBaseId, sessionId, request));
    }

    @PostMapping("/{sessionId}/agent-messages")
    public ApiResponse<ConversationReply> askAgent(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long sessionId,
            HttpServletRequest httpRequest,
            @Valid @RequestBody CreateMessageRequest request
    ) {
        return ApiResponse.success(conversationService.askAgent(
                authenticatedUserId(httpRequest), knowledgeBaseId, sessionId, request
        ));
    }

    @PostMapping(value = "/{sessionId}/messages/stream", produces = "text/event-stream")
    public SseEmitter stream(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long sessionId,
            HttpServletRequest httpRequest,
            @Valid @RequestBody CreateMessageRequest request
    ) {
        Long userId = authenticatedUserId(httpRequest);
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
}
