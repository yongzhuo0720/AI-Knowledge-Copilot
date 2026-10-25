package com.aicopilot.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiServiceConfigurationTest {

    private HttpServer server;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void rejectsAnExplicitStreamingError() throws IOException {
        KnowledgeAnswerClient client = streamingClient("""
                event: delta
                data: {"content":"partial"}

                event: error
                data: {"message":"model unavailable"}

                """);

        assertThatThrownBy(() -> client.stream(20L, "question", List.of(), content -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model unavailable");
    }

    @Test
    void rejectsAnIncompleteAnswerWhenTheStreamClosesBeforeCompletion() throws IOException {
        KnowledgeAnswerClient client = streamingClient("""
                event: delta
                data: {"content":"partial"}

                """);

        assertThatThrownBy(() -> client.stream(20L, "question", List.of(), content -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before completion");
    }

    @Test
    void returnsCompletedStreamingAnswerAndSources() throws IOException {
        KnowledgeAnswerClient client = streamingClient("""
                event: delta
                data: {"content":"answer"}

                event: complete
                data: {"answer":"answer", "sources":[{"document_object_key":"kb/20/guide.txt","content":"source","score":0.9,"retriever":"hybrid"}]}

                """);
        List<String> deltas = new ArrayList<>();

        KnowledgeAnswer answer = client.stream(20L, "question", List.of(), deltas::add);

        assertThat(deltas).containsExactly("answer");
        assertThat(answer.answer()).isEqualTo("answer");
        assertThat(answer.sources()).containsExactly(
                new KnowledgeRetrievalChunk("kb/20/guide.txt", "source", 0.9, "hybrid"));
    }

    @Test
    void sendsTheInternalTokenToTheAiService() throws IOException {
        List<String> tokens = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/answers/stream", exchange -> {
            tokens.add(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            exchange.getRequestBody().readAllBytes();
            byte[] payload = """
                    event: complete
                    data: {"answer":"ok", "sources":[]}

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, payload.length);
            try (var outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            } finally {
                exchange.close();
            }
        });
        server.start();
        AiServiceProperties properties = new AiServiceProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setInternalToken("secret-token");

        new AiServiceConfiguration()
                .knowledgeAnswerClient(properties, new ObjectMapper(), meterRegistry)
                .stream(20L, "question", List.of(), content -> {});

        assertThat(tokens).containsExactly("secret-token");
    }

    private KnowledgeAnswerClient streamingClient(String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/answers/stream", exchange -> {
            byte[] payload = response.getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, payload.length);
            try (var outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            } finally {
                exchange.close();
            }
        });
        server.start();
        AiServiceProperties properties = new AiServiceProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new AiServiceConfiguration().knowledgeAnswerClient(properties, new ObjectMapper(), meterRegistry);
    }

    @Test
    void recordsTimerOutcomeForStreamingCalls() throws IOException {
        KnowledgeAnswerClient client = streamingClient("""
                event: complete
                data: {"answer":"完成","sources":[]}

                """);

        client.stream(20L, "question", List.of(), content -> { });

        io.micrometer.core.instrument.Timer timer = meterRegistry.find("aicopilot.ai.request")
                .tag("capability", "answer.stream").tag("outcome", "ok").timer();
        org.assertj.core.api.Assertions.assertThat(timer).isNotNull();
        org.assertj.core.api.Assertions.assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordsErrorOutcomeWhenTheUpstreamFails() throws IOException {
        KnowledgeAnswerClient client = streamingClient("""
                event: error
                data: {"message":"model unavailable"}

                """);

        assertThatThrownBy(() -> client.stream(20L, "question", List.of(), content -> { }))
                .isInstanceOf(IllegalStateException.class);

        io.micrometer.core.instrument.Timer timer = meterRegistry.find("aicopilot.ai.request")
                .tag("capability", "answer.stream").tag("outcome", "error").timer();
        org.assertj.core.api.Assertions.assertThat(timer).isNotNull();
        org.assertj.core.api.Assertions.assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void doesNotCountDisabledCapabilityAsACall() {
        AiServiceProperties properties = new AiServiceProperties();
        properties.setEnabled(false);
        KnowledgeAnswerClient client = new AiServiceConfiguration()
                .knowledgeAnswerClient(properties, new ObjectMapper(), meterRegistry);

        // disabled 分支不发生远程调用，也不该被计入能力耗时（否则稀释真实延迟）
        client.answer(20L, "question", List.of());

        org.assertj.core.api.Assertions.assertThat(meterRegistry.find("aicopilot.ai.request").timers()).isEmpty();
    }
}
