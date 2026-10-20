package com.agentic.orchestrator.llm;

import com.agentic.orchestrator.OrchestratorProperties;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The live adapter against a local fake of the Messages API: what is sent, how a reply is read, and how refusals,
 * truncation, HTTP errors and timeouts surface. No network and no API key are involved.
 */
class AnthropicLlmClientTest {

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply;
    private volatile long delayMillis = 0;

    private static String message(String stopReason, String text) {
        return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
                + "\"content\":[{\"type\":\"text\",\"text\":" + jsonString(text) + "}],"
                + "\"stop_reason\":" + (stopReason == null ? "null" : "\"" + stopReason + "\"") + ",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":45}}";
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        reply = message("end_turn", "{\"ok\":true}");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AnthropicLlmClient client(Duration timeout) {
        AnthropicClient sdk = AnthropicOkHttpClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("test-key").maxRetries(0).timeout(timeout).build();
        var llm = new OrchestratorProperties.Llm("live", "claude-opus-5-5", 4096, timeout, Path.of("."));
        return new AnthropicLlmClient(llm, sdk);
    }

    private static LlmClient.LlmRequest request() {
        return new LlmClient.LlmRequest(UUID.randomUUID(), "s", "design", "ARCHITECT", 1, "You are an architect.", "Design it.");
    }

    @Test
    void sendsTheModelPromptAndKeyAndReadsTheReplyAndTokenUsage() {
        LlmClient.LlmResponse response = client(Duration.ofSeconds(5)).complete(request());

        assertThat(response.text()).isEqualTo("{\"ok\":true}");
        assertThat(response.tokensUsed()).isEqualTo(165);
        assertThat(apiKeyHeader.get()).isEqualTo("test-key");
        assertThat(requestBody.get()).contains("\"model\":\"claude-opus-5-5\"").contains("\"max_tokens\":4096")
                .contains("You are an architect.").contains("Design it.");
    }

    @Test
    void aRefusalIsAFailureTheEngineCanRetryOrFallBackFrom() {
        reply = message("refusal", "");
        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).complete(request()))
                .isInstanceOf(LlmException.class).hasMessageContaining("refusal");
    }

    @Test
    void outputCutOffAtMaxTokensIsRejectedRatherThanUsedAsIfComplete() {
        reply = message("max_tokens", "{\"truncated\":");
        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).complete(request()))
                .isInstanceOf(LlmException.class).hasMessageContaining("max_tokens");
    }

    @Test
    void anHttpErrorBecomesAnLlmException() {
        status = 529;
        reply = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";
        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).complete(request()))
                .isInstanceOf(LlmException.class).isNotInstanceOf(LlmException.Timeout.class);
    }

    @Test
    void aSlowServerTimesOut() {
        delayMillis = 3_000;
        assertThatThrownBy(() -> client(Duration.ofMillis(400)).complete(request()))
                .isInstanceOf(LlmException.Timeout.class);
    }
}
