package com.aicopilot.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.aicopilot.conversation.ConversationMessage;
import com.aicopilot.dashboard.IndexedChunkClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.io.BufferedReader;
import java.io.InputStreamReader;

@Configuration
@EnableConfigurationProperties(AiServiceProperties.class)
public class AiServiceConfiguration {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    @Bean
    RestClient aiServiceRestClient(RestClient.Builder builder, AiServiceProperties properties) {
        RestClient.Builder configured = builder.baseUrl(properties.getBaseUrl());
        if (hasInternalToken(properties)) {
            configured = configured.defaultHeader(INTERNAL_TOKEN_HEADER, properties.getInternalToken());
        }
        return configured.build();
    }

    private static boolean hasInternalToken(AiServiceProperties properties) {
        return properties.getInternalToken() != null && !properties.getInternalToken().isBlank();
    }

    private static void applyInternalToken(HttpURLConnection connection, AiServiceProperties properties) {
        if (hasInternalToken(properties)) {
            connection.setRequestProperty(INTERNAL_TOKEN_HEADER, properties.getInternalToken());
        }
    }

    @Bean
    DocumentProcessingClient documentProcessingClient(
            AiServiceProperties properties,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        AiClientMetrics metrics = new AiClientMetrics(meterRegistry);
        return new DocumentProcessingClient() {
            @Override
            public DocumentProcessingResponse submit(DocumentProcessingRequest request) {
                if (!properties.isEnabled()) return new DocumentProcessingResponse("disabled", request.objectKey(), "DISABLED");
                return metrics.timed("document.submit", () -> submitToAiService(properties, objectMapper, request));
            }

            @Override
            public DocumentProcessingResponse findTask(String taskId) {
                if (!properties.isEnabled()) return new DocumentProcessingResponse(taskId, "", "DISABLED");
                return metrics.timed("document.find", () -> findTaskInAiService(properties, objectMapper, taskId));
            }

            @Override
            public DocumentProcessingResponse retry(String taskId) {
                if (!properties.isEnabled()) return new DocumentProcessingResponse(taskId, "", "DISABLED");
                return metrics.timed("document.retry", () -> retryInAiService(properties, objectMapper, taskId));
            }
        };
    }

    @Bean
    KnowledgeIndexClient knowledgeIndexClient(AiServiceProperties properties, MeterRegistry meterRegistry) {
        AiClientMetrics metrics = new AiClientMetrics(meterRegistry);
        return new KnowledgeIndexClient() {
            @Override
            public void deleteKnowledgeBaseIndex(Long knowledgeBaseId) {
                metrics.timed("index.delete_kb", () ->
                        delete(properties, "/api/v1/knowledge-bases/" + knowledgeBaseId + "/index"));
            }

            @Override
            public void deleteDocumentIndex(Long knowledgeBaseId, String objectKey) {
                metrics.timed("index.delete_document", () ->
                        delete(properties, "/api/v1/knowledge-bases/" + knowledgeBaseId + "/documents/index?object_key="
                                + URLEncoder.encode(objectKey, StandardCharsets.UTF_8)));
            }

            private void delete(AiServiceProperties properties, String path) {
                if (!properties.isEnabled()) {
                    return;
                }
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(properties.getBaseUrl() + path).openConnection();
                    applyInternalToken(connection, properties);
                    connection.setConnectTimeout(5_000);
                    connection.setReadTimeout(30_000);
                    connection.setRequestMethod("DELETE");
                    int status = connection.getResponseCode();
                    if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_NO_CONTENT) {
                        throw new IllegalStateException("AI service rejected index deletion: HTTP " + status);
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("failed to delete knowledge index", exception);
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        };
    }

    @Bean
    DocumentPreviewClient documentPreviewClient(AiServiceProperties properties, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        AiClientMetrics metrics = new AiClientMetrics(meterRegistry);
        return (knowledgeBaseId, objectKey) -> {
            if (!properties.isEnabled()) {
                throw new IllegalStateException("AI service is disabled");
            }
            return metrics.timed("document.preview", () -> {
                HttpURLConnection connection = null;
                try {
                    // objectKey 里有斜杠和可能的中文，必须编码后才能拼进 query string
                    String encodedKey = URLEncoder.encode(objectKey, StandardCharsets.UTF_8);
                    connection = (HttpURLConnection) new URL(properties.getBaseUrl()
                            + "/api/v1/knowledge-bases/" + knowledgeBaseId + "/documents/preview?object_key=" + encodedKey
                    ).openConnection();
                    applyInternalToken(connection, properties);
                    connection.setConnectTimeout(5_000);
                    connection.setReadTimeout(30_000);
                    int statusCode = connection.getResponseCode();
                    if (statusCode != HttpURLConnection.HTTP_OK) {
                        throw new IllegalStateException("AI service rejected document preview request: HTTP " + statusCode);
                    }
                    try (InputStream inputStream = connection.getInputStream()) {
                        JsonNode response = objectMapper.readTree(inputStream);
                        JsonNode data = response.path("data");
                        if (!"0".equals(response.path("code").asText()) || data.isMissingNode()) {
                            throw new IllegalStateException("AI service rejected document preview request");
                        }
                        List<DocumentPreviewSection> sections = new java.util.ArrayList<>();
                        for (JsonNode section : data.path("sections")) {
                            List<String> titlePath = new java.util.ArrayList<>();
                            section.path("title_path").forEach(title -> titlePath.add(title.asText()));
                            List<String> blocks = new java.util.ArrayList<>();
                            section.path("blocks").forEach(block -> blocks.add(block.asText()));
                            sections.add(new DocumentPreviewSection(List.copyOf(titlePath), List.copyOf(blocks)));
                        }
                        return new DocumentPreview(
                                data.path("name").asText(""),
                                data.path("section_count").asInt(0),
                                data.path("block_count").asInt(0),
                                data.path("truncated").asBoolean(false),
                                List.copyOf(sections)
                        );
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("failed to preview document", exception);
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            });
        };
    }

    @Bean
    KnowledgeRetrievalClient knowledgeRetrievalClient(
            AiServiceProperties properties, ObjectMapper objectMapper, MeterRegistry meterRegistry
    ) {
        AiClientMetrics metrics = new AiClientMetrics(meterRegistry);
        return (knowledgeBaseId, query, limit, mode) -> {
            if (!properties.isEnabled()) return List.of();
            return metrics.timed("retrieval.search", () -> {
                try {
                    HttpURLConnection connection = (HttpURLConnection) new URL(
                            properties.getBaseUrl() + "/api/v1/retrieval/search"
                    ).openConnection();
                    applyInternalToken(connection, properties);
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(5_000);
                    connection.setReadTimeout(30_000);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                    byte[] payload = objectMapper.writeValueAsBytes(new AiServiceRetrievalRequest(knowledgeBaseId, query, limit, mode));
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
            });
        };
    }

    @Bean
    KnowledgeAnswerClient knowledgeAnswerClient(
            AiServiceProperties properties, ObjectMapper objectMapper, MeterRegistry meterRegistry
    ) {
        AiClientMetrics metrics = new AiClientMetrics(meterRegistry);
        return new KnowledgeAnswerClient() {
            @Override
            public KnowledgeAnswer answer(Long knowledgeBaseId, String question, List<ConversationMessage> history) {
            if (!properties.isEnabled()) return new KnowledgeAnswer("AI service is disabled", List.of());
            return metrics.timed("answer.ask", () -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(
                        properties.getBaseUrl() + "/api/v1/answers"
                ).openConnection();
                applyInternalToken(connection, properties);
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
            });
            }

            @Override
            public KnowledgeAnswer stream(Long knowledgeBaseId, String question, List<ConversationMessage> history,
                                          Consumer<String> onDelta) {
                if (!properties.isEnabled()) {
                    String fallback = "AI service is disabled";
                    onDelta.accept(fallback);
                    return new KnowledgeAnswer(fallback, List.of());
                }
                return metrics.timed("answer.stream", () -> {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(
                            properties.getBaseUrl() + "/api/v1/answers/stream"
                    ).openConnection();
                    applyInternalToken(connection, properties);
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
                    boolean completed = false;
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
                                } else if ("error".equals(event)) {
                                    throw new IllegalStateException(
                                            "AI service streaming failed: " + data.path("message").asText("unknown error"));
                                } else if ("complete".equals(event)) {
                                    completed = true;
                                    if (data.has("answer")) answer = new StringBuilder(data.path("answer").asText());
                                    sources = toRetrievalChunks(data.path("sources"));
                                }
                                event = null;
                            }
                        }
                    }
                    // 连接中途断开时 BufferedReader 只会安静地返回 null，不校验就会把半截答案当成功返回。
                    if (!completed) {
                        throw new IllegalStateException("AI service stream closed before completion");
                    }
                    return new KnowledgeAnswer(answer.toString(), sources);
                } catch (IOException exception) {
                    throw new IllegalStateException("failed to stream knowledge question", exception);
                } finally {
                    if (connection != null) connection.disconnect();
                }
                });
            }
        };
    }

    @Bean
    KnowledgeAgentClient knowledgeAgentClient(
            AiServiceProperties properties, ObjectMapper objectMapper, MeterRegistry meterRegistry
    ) {
        AiClientMetrics metrics = new AiClientMetrics(meterRegistry);
        return (knowledgeBaseId, question, history) -> {
            if (!properties.isEnabled()) {
                return new KnowledgeAgentAnswer("AI service is disabled", List.of(), List.of());
            }
            return metrics.timed("agent.run", () -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(
                        properties.getBaseUrl() + "/api/v1/agents/knowledge"
                ).openConnection();
                applyInternalToken(connection, properties);
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
                    throw new IllegalStateException("AI service rejected agent request");
                }
                try (InputStream inputStream = connection.getInputStream()) {
                    JsonNode response = objectMapper.readTree(inputStream);
                    JsonNode data = response.path("data");
                    if (!"0".equals(response.path("code").asText()) || !data.isObject()) {
                        throw new IllegalStateException("AI service rejected agent request");
                    }
                    List<KnowledgeAgentStep> steps = java.util.stream.StreamSupport.stream(
                            data.path("steps").spliterator(), false
                    ).map(step -> new KnowledgeAgentStep(
                            step.path("tool").asText(),
                            step.path("query").asText(),
                            step.path("result_count").asInt()
                    )).toList();
                    return new KnowledgeAgentAnswer(
                            data.path("answer").asText(),
                            toRetrievalChunks(data.path("sources")),
                            steps
                    );
                } finally {
                    connection.disconnect();
                }
            } catch (IOException exception) {
                throw new IllegalStateException("failed to run knowledge agent", exception);
            }
            });
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
                applyInternalToken(connection, properties);
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
            applyInternalToken(connection, properties);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(10_000);
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
            applyInternalToken(connection, properties);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(30_000);
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
            applyInternalToken(connection, properties);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(30_000);
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
                        chunk.path("score").asDouble(),
                        chunk.path("retriever").asText("vector")
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
            int limit,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String mode
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
