package com.aicopilot.knowledge;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 检索请求。
 *
 * @param mode 检索模式，留空则由 ai-service 用服务端默认值（当前为 hybrid）
 */
public record KnowledgeSearchRequest(
        @NotBlank @Size(max = 1000) String query,
        @Min(1) @Max(20) Integer limit,
        @Pattern(regexp = "vector|keyword|hybrid", message = "mode must be one of vector, keyword, hybrid")
        String mode
) {
    public int resolvedLimit() {
        return limit == null ? 5 : limit;
    }

    public String resolvedMode() {
        return mode == null || mode.isBlank() ? null : mode;
    }
}
