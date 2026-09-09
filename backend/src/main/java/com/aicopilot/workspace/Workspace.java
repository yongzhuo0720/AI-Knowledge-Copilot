package com.aicopilot.workspace;

public record Workspace(
        Long id,
        String name,
        Long ownerUserId
) {
}
