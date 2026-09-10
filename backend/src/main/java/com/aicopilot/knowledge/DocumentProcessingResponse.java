package com.aicopilot.knowledge;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentProcessingResponse(
        String taskId,
        String objectKey,
        String status
) {
}
