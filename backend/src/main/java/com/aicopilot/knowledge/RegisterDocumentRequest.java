package com.aicopilot.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record RegisterDocumentRequest(
        @NotBlank(message = "originalFilename must not be blank")
        @Size(max = 255, message = "originalFilename must be at most 255 characters")
        String originalFilename,

        @NotBlank(message = "objectKey must not be blank")
        @Size(max = 512, message = "objectKey must be at most 512 characters")
        String objectKey,

        @NotBlank(message = "contentType must not be blank")
        @Size(max = 128, message = "contentType must be at most 128 characters")
        String contentType,

        @PositiveOrZero(message = "fileSize must be zero or greater")
        long fileSize
) {
}
