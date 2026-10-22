package com.aicopilot.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 * admin 为可选字段：true 表示「管理员登录」，要求该账号必须具备管理员身份。
 * 老客户端不传该字段时为 null，按普通登录处理，向后兼容。
 */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password,
        Boolean admin
) {

    public LoginRequest(String email, String password) {
        this(email, password, false);
    }

    public boolean requiresAdmin() {
        return Boolean.TRUE.equals(admin);
    }
}
