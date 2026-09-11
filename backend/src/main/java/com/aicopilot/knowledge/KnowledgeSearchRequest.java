package com.aicopilot.knowledge;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeSearchRequest(
        @NotBlank @Size(max = 1000) String query,
        @Min(1) @Max(20) Integer limit
) {
    public int resolvedLimit() {
        return limit == null ? 5 : limit;
    }
}
