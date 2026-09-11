package com.aicopilot.user;

public record LoginResponse(String accessToken, UserResponse user) {
}
