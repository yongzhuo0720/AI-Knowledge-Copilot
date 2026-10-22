package com.aicopilot.admin;

import java.time.Instant;

/**
 * 后台视角下的用户记录。
 * 相对普通 UserResponse 多出创建时间与资源占用统计，供管理员做删除决策使用。
 */
public record AdminUserResponse(
        Long id,
        String username,
        String email,
        String status,
        boolean isAdmin,
        Instant createdAt,
        long workspaceCount,
        long knowledgeBaseCount,
        long conversationCount
) {
}
