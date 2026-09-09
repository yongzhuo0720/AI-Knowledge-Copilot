package com.aicopilot.user;

public record User(
        Long id,
        String username,
        String email,
        String passwordHash,
        String status
) {
}
