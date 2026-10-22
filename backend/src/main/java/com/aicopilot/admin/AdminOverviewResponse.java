package com.aicopilot.admin;

public record AdminOverviewResponse(
        long userCount,
        long activeUserCount,
        long suspendedUserCount,
        long adminCount,
        long workspaceCount,
        long knowledgeBaseCount,
        long documentCount,
        long conversationCount
) {
}
