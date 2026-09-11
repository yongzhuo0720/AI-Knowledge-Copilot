package com.aicopilot.conversation;

public record ConversationReply(
        ConversationMessage userMessage,
        ConversationMessage assistantMessage
) {
}
