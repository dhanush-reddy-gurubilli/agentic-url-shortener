package com.agentic.orchestrator.llm;

import com.agentic.orchestrator.OrchestratorProperties;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;

import java.io.InterruptedIOException;
import java.util.stream.Collectors;

/**
 * Live adapter over the official Anthropic Java SDK. Credentials come from the SDK's normal resolution
 * (ANTHROPIC_API_KEY or an {@code ant auth login} profile). Every call has a timeout; refusals and API errors
 * surface as {@link LlmException} so the engine's bounded retry and fallback handle them.
 */
public class AnthropicLlmClient implements LlmClient {

    private final OrchestratorProperties.Llm config;
    private volatile AnthropicClient client;

    public AnthropicLlmClient(OrchestratorProperties properties) {
        this.config = properties.llm();
    }

    /** For tests: use an already configured SDK client (for example one pointed at a local fake server). */
    AnthropicLlmClient(OrchestratorProperties.Llm config, AnthropicClient client) {
        this.config = config;
        this.client = client;
    }

    private AnthropicClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.builder().fromEnv().timeout(config.timeout()).maxRetries(1).build();
                }
            }
        }
        return client;
    }

    /** OkHttp reports read timeouts as SocketTimeoutException and whole-call timeouts as InterruptedIOException. */
    static boolean isTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(config.model())
                .maxTokens((long) config.maxTokens())
                .system(request.system())
                .addUserMessage(request.user())
                .build();
        Message message;
        try {
            message = client().messages().create(params);
        } catch (AnthropicException e) {
            if (isTimeout(e)) {
                throw new LlmException.Timeout("Anthropic call timed out: " + e.getMessage());
            }
            throw new LlmException("Anthropic call failed: " + e.getMessage(), e);
        }
        if (message.stopReason().filter(r -> r.equals(StopReason.REFUSAL)).isPresent()) {
            throw new LlmException("The model declined this request (refusal)");
        }
        if (message.stopReason().filter(r -> r.equals(StopReason.MAX_TOKENS)).isPresent()) {
            throw new LlmException("The model output was cut off at max_tokens");
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining());
        long tokens = message.usage().inputTokens() + message.usage().outputTokens();
        return new LlmResponse(text, tokens);
    }
}
