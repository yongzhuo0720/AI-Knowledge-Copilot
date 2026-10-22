package com.aicopilot.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateUserStatusRequest(
        @NotBlank(message = "status must not be blank")
        @Pattern(regexp = "ACTIVE|SUSPENDED", message = "status must be either ACTIVE or SUSPENDED")
        String status
) {

    public String normalizedStatus() {
        return status.trim().toUpperCase();
    }
}
