package com.aicopilot.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.aicopilot.conversation.ConversationMessage;
import com.aicopilot.dashboard.IndexedChunkClient;
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
import java.util.List;
import java.util.function.Consumer;
import java.io.BufferedReader;
import java.io.InputStreamReader;

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
        return new DocumentProcessingClient() {
            @Override
            public DocumentProcessingResponse submit(DocumentProcessingRequest request) {
                if (!properties.isEnabled()) return new DocumentProcessingResponse("disabled", request.objectKey(), "DISABLED");
                return submitToAiService(properties, objectMapper, request);
            }

            @Override
            public DocumentProcessingResponse findTask(String taskId) {
                if (!properties.isEnabled()) return new DocumentProcessingResponse(taskId, "", "DISABLED");
                return findTaskInAiService(properties, objectMapper, taskId);
            }

            @Override
            public DocumentProcessingResponse retry(String taskId) {
                if (!properties.isEnabled()) return new DocumentProcessingResponse(taskId, "", "DISABLED");
                return retryInAiService(properties, objectMapper, taskId);
            }
        };
    }

    @Bean
    KnowledgeRetrievalClient knowledgeRetrievalClient(AiServiceProperties properties, ObjectMapper objectMapper) {
        return (knowledgeBaseId, query, limit) -> {
            if (!properties.isEnabled()) return List.of();
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(
                        properties.getBaseUrl() + "/api/v1/retrieval/search"
                ).openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                byte[] payload = objectMapper.writeValueAsBytes(new AiServiceRetrievalRequest(knowledgeBaseId, query, limit));
                connection.setFixedLengthStreamingMode(payload.length);
                try (var outputStream = connection.getOutputStream()) {
                    outputStream.write(payload);
                }
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("AI service rejected retrieval request");
                }
                try (InputStream inputStream = connection.getInputStream()) {
                    JsonNode response = objectMapper.readTree(inputStream);
                    if (!"0".equals(response.path("code").asText()) || !response.path("data").isArray()) {
                        throw new IllegalStateException("AI service rejected retrieval request");
                    }
                    return toRetrievalChunks(response.path("data"));
                } finally {
                    connection.disconnect();
                }
            } catch (IOException exception) {
                throw new IllegalStateException("failed to retrieve knowledge chunks", exception);
            }
        };
    }

    @Bean
    KnowledgeAnswerClient knowledgeAnswerClient(
            AiServiceProperties properties, ObjectMapper objectMapper
    ) {
        return new KnowledgeAnswerClient() {
            @Override
            public KnowledgeAnswer answer(Long knowledgeBaseId, String question, List<ConversationMessage> history) {
            if (!properties.isEnabled()) return new KnowledgeAnswer("AI service is disabled", List.of());
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(
                        properties.getBaseUrl() + "/api/v1/answers"
                ).openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(90_000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                byte[] payload = objectMapper.writeValueAsBytes(new AiServiceAnswerRequest(
                        knowledgeBaseId,
                        question,
                        history.stream()
                                .map(message -> new AiServiceHistoryMessage(message.role(), message.content()))
                                .toList()
                ));
                connection.setFixedLengthStreamingMode(payload.length);
                try (var outputStream = connection.getOutputStream()) {
                    outputStream.write(payload);
                }
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("AI service rejected answer request");
                }
                try (InputStream inputStream = connection.getInputStream()) {
                    JsonNode response = objectMapper.readTree(inputStream);
                    JsonNode data = response.path("data");
                    if (!"0".equals(response.path("code").asText()) || !data.isObject()) {
                        throw new IllegalStateException("AI service rejected answer request");
                    }
            return new KnowledgeAnswer(
                            data.path("answer").asText(),
                            toRetrievalChunks(data.path("sources"))
                    );
                }
            } catch (IOException exception) {
                throw new IllegalStateException("failed to answer knowledge question", exception);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            }

            @Override
            public KnowledgeAnswer stream(Long knowledgeBaseId, String question, List<ConversationMessage> history,
                                          Consumer<String> onDelta) {
                if (!properties.isEnabled()) {
                    String fallback = "AI service is disabled";
                    onDelta.accept(fallback);
                    return new KnowledgeAnswer(fallback, List.of());
                }
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(
                            properties.getBaseUrl() + "/api/v1/answers/stream"
                    ).openConnection();
                    connection.setConnectTimeout(10_000);
                    connection.setReadTimeout(90_000);
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                    byte[] payload = objectMapper.writeValueAsBytes(new AiServiceAnswerRequest(
                            knowledgeBaseId,
                            question,
                            history.stream()
                                    .map(message -> new AiServiceHistoryMessage(message.role(), message.content()))
                                    .toList()
                    ));
                    connection.setFixedLengthStreamingMode(payload.length);
                    try (var outputStream = connection.getOutputStream()) {
                        outputStream.write(payload);
                    }
                    if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                        throw new IllegalStateException("AI service rejected streaming answer request");
                    }
                    List<KnowledgeRetrievalChunk> sources = List.of();
                    StringBuilder answer = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        String event = null;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("event:")) {
                                event = line.substring(6).trim();
                            } else if (line.startsWith("data:") && event != null) {
                                JsonNode data = objectMapper.readTree(line.substring(5).trim());
                                if ("delta".equals(event)) {
                                    String content = data.path("content").asText("");
                                    if (!content.isEmpty()) {
                                        answer.append(content);
                                        onDelta.accept(content);
                                    }
                                } else if ("sources".equals(event)) {
                                    sources = toRetrievalChunks(data.path("sources"));
                                } else if ("complete".equals(event)) {
                                    if (data.has("answer")) answer = new StringBuilder(data.path("answer").asText());
                                    sources = toRetrievalChunks(data.path("sources"));
                                }
                                event = null;
                            }
                        }
                    }
                    return new KnowledgeAnswer(answer.toString(), sources);
                } catch (IOException exception) {
                    throw new IllegalStateException("failed to stream knowledge question", exception);
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        };
    }

    @Bean
    IndexedChunkClient indexedChunkClient(AiServiceProperties properties, ObjectMapper objectMapper) {
        return knowledgeBaseIds -> {
            if (!properties.isEnabled() || knowledgeBaseIds.isEmpty()) return 0L;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(
                        properties.getBaseUrl() + "/api/v1/retrieval/stats?knowledge_base_ids="
                                + knowledgeBaseIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))
                ).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5_000);
                connection.setReadTimeout(10_000);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    return null;
                }
                try (InputStream inputStream = connection.getInputStream()) {
                    JsonNode response = objectMapper.readTree(inputStream);
                    JsonNode count = response.path("data").path("indexed_chunk_count");
                    return "0".equals(response.path("code").asText()) && count.isNumber() ? count.longValue() : null;
                } finally {
                    connection.disconnect();
                }
            } catch (IOException exception) {
                return null;
            }
        };
    }

    private DocumentProcessingResponse findTaskInAiService(
            AiServiceProperties properties, ObjectMapper objectMapper, String taskId
    ) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(
                    properties.getBaseUrl() + "/api/v1/document-processing/tasks/" + taskId
            ).openConnection();
            connection.setRequestMethod("GET");
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
            throw new IllegalStateException("failed to retrieve document processing task", exception);
        }
    }

    private DocumentProcessingResponse submitToAiService(
            AiServiceProperties properties,
            ObjectMapper objectMapper,
            DocumentProcessingRequest request
    ) {
        try {
            String body = objectMapper.writeValueAsString(new AiServiceDocumentRequest(
                    request.knowledgeBaseId(), request.objectKey(), request.filename(), request.contentType(),
                    request.replaceExisting()
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

    private DocumentProcessingResponse retryInAiService(
            AiServiceProperties properties, ObjectMapper objectMapper, String taskId
    ) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(
                    properties.getBaseUrl() + "/api/v1/document-processing/tasks/" + taskId + "/retry"
            ).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            connection.setFixedLengthStreamingMode(0);
            try (var outputStream = connection.getOutputStream()) {
                outputStream.write(new byte[0]);
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
            throw new IllegalStateException("failed to retry document processing task", exception);
        }
    }

    private List<KnowledgeRetrievalChunk> toRetrievalChunks(JsonNode chunks) {
        if (!chunks.isArray()) {
            throw new IllegalStateException("AI service returned invalid retrieval sources");
        }
        return java.util.stream.StreamSupport.stream(chunks.spliterator(), false)
                .map(chunk -> new KnowledgeRetrievalChunk(
                        chunk.path("document_object_key").asText(null),
                        chunk.path("content").asText(),
                        chunk.path("score").asDouble()
                ))
                .toList();
    }

    private record AiServiceDocumentRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("knowledge_base_id") Long knowledgeBaseId,
            @com.fasterxml.jackson.annotation.JsonProperty("object_key") String objectKey,
            @com.fasterxml.jackson.annotation.JsonProperty("filename") String filename,
            @com.fasterxml.jackson.annotation.JsonProperty("content_type") String contentType,
            @com.fasterxml.jackson.annotation.JsonProperty("replace_existing") boolean replaceExisting
    ) {
    }

    private record AiServiceRetrievalRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("knowledge_base_id") Long knowledgeBaseId,
            String query,
            int limit
    ) {
    }

    private record AiServiceAnswerRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("knowledge_base_id") Long knowledgeBaseId,
            String question,
            List<AiServiceHistoryMessage> history
    ) {
    }

    private record AiServiceHistoryMessage(String role, String content) {
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
