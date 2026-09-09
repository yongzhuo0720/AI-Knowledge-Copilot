package com.aicopilot.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank(message = "username must not be blank")
        @Size(max = 64, message = "username must be at most 64 characters")
        String username,

        @NotBlank(message = "email must not be blank")
        @Email(message = "email must be valid")
        @Size(max = 255, message = "email must be at most 255 characters")
        String email,

        @NotBlank(message = "password must not be blank")
        @Size(min = 8, max = 128, message = "password must be between 8 and 128 characters")
        String password
) {
}
