package com.aicopilot.knowledge;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration
@Profile("local")
@EnableConfigurationProperties(AiServiceProperties.class)
public class AiServiceConfiguration {

    @Bean
    RestClient aiServiceRestClient(RestClient.Builder builder, AiServiceProperties properties) {
        return builder.baseUrl(properties.getBaseUrl()).build();
    }

    @Bean
    DocumentProcessingClient documentProcessingClient(
            RestClient aiServiceRestClient,
            AiServiceProperties properties
    ) {
        return request -> {
            if (!properties.isEnabled()) {
                return new DocumentProcessingResponse("disabled", request.objectKey(), "DISABLED");
            }
            return aiServiceRestClient.post()
                    .uri("/api/v1/document-processing/tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AiServiceDocumentRequest(
                            request.objectKey(), request.filename(), request.contentType()
                    ))
                    .retrieve()
                    .body(DocumentProcessingResponseEnvelope.class)
                    .toResponse();
        };
    }

    private record AiServiceDocumentRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("object_key") String objectKey,
            @com.fasterxml.jackson.annotation.JsonProperty("filename") String filename,
            @com.fasterxml.jackson.annotation.JsonProperty("content_type") String contentType
    ) {
    }

    private record DocumentProcessingResponseEnvelope(
            String code,
            String message,
            DocumentProcessingResponse data
    ) {
        private DocumentProcessingResponse toResponse() {
            if (!"0".equals(code) || data == null) {
                throw new IllegalStateException("AI service rejected document processing task");
            }
            return data;
        }
    }
}
