package com.aicopilot.admin;

import jakarta.validation.constraints.NotNull;

public record UpdateUserAdminRequest(
        @NotNull(message = "isAdmin must not be null")
        Boolean isAdmin
) {
}
