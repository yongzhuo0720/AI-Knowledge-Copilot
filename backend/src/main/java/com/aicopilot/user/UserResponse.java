package com.aicopilot.user;

public record UserResponse(
        Long id,
        String username,
        String email,
        String status
) {

    public static UserResponse from(User user) {
        return new UserResponse(user.id(), user.username(), user.email(), user.status());
    }
}
