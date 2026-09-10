package com.aicopilot.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

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
            AiServiceProperties properties,
            ObjectMapper objectMapper
    ) {
        return request -> {
            if (!properties.isEnabled()) {
                return new DocumentProcessingResponse("disabled", request.objectKey(), "DISABLED");
            }
            return submitToAiService(properties, objectMapper, request);
        };
    }

    private DocumentProcessingResponse submitToAiService(
            AiServiceProperties properties,
            ObjectMapper objectMapper,
            DocumentProcessingRequest request
    ) {
        try {
            String body = objectMapper.writeValueAsString(new AiServiceDocumentRequest(
                    request.objectKey(), request.filename(), request.contentType()
            ));
            HttpURLConnection connection = (HttpURLConnection) new URL(
                    properties.getBaseUrl() + "/api/v1/document-processing/tasks"
            ).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (var outputStream = connection.getOutputStream()) {
                outputStream.write(payload);
            }
            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("AI service returned HTTP " + statusCode);
            }
            try (InputStream inputStream = connection.getInputStream()) {
                return objectMapper.readValue(inputStream, DocumentProcessingResponseEnvelope.class).toResponse();
            } finally {
                connection.disconnect();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("failed to serialize document processing request", exception);
        }
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
