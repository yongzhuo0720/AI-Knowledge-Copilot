package com.aicopilot.conversation;

import com.aicopilot.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CreateConversationRequest request
    ) {
        return ApiResponse.success(conversationService.createSession(userId, knowledgeBaseId, request));
    }

    @GetMapping
    public ApiResponse<List<ConversationSession>> findSessions(
            @PathVariable Long knowledgeBaseId,
            @RequestHeader("X-User-Id") Long userId
    ) {
        return ApiResponse.success(conversationService.findSessions(userId, knowledgeBaseId));
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResponse<List<ConversationMessage>> findMessages(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long sessionId,
            @RequestHeader("X-User-Id") Long userId
    ) {
        return ApiResponse.success(conversationService.findMessages(userId, knowledgeBaseId, sessionId));
    }

    @PostMapping("/{sessionId}/messages")
    public ApiResponse<ConversationReply> ask(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long sessionId,
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CreateMessageRequest request
    ) {
        return ApiResponse.success(conversationService.ask(userId, knowledgeBaseId, sessionId, request));
    }
}
