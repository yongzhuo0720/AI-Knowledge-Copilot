package com.aicopilot.knowledge;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * ai-service 解析任务的响应。{@code stage}/{@code progress} 是解析中的瞬时进度
 * （内存态，随任务查询合并），只对 PROCESSING/RETRYING 有意义；终态为 null。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentProcessingResponse(
        String taskId,
        String objectKey,
        String status,
        String failureReason,
        int retryCount,
        String stage,
        Integer progress
) {

    public DocumentProcessingResponse(String taskId, String objectKey, String status) {
        this(taskId, objectKey, status, null, 0, null, null);
    }

    public DocumentProcessingResponse(String taskId, String objectKey, String status, String failureReason, int retryCount) {
        this(taskId, objectKey, status, failureReason, retryCount, null, null);
    }
}
