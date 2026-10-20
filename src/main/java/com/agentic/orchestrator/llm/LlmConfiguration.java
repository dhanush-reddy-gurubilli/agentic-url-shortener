package com.agentic.orchestrator.llm;

import com.agentic.orchestrator.OrchestratorProperties;
import com.agentic.orchestrator.scenario.ScenarioCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfiguration {

    /** replay (default, no API key needed), live (Anthropic API) or record (live + save responses for replay). */
    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    LlmClient llmClient(OrchestratorProperties properties, ScenarioCatalog catalog) {
        return switch (properties.llm().mode()) {
            case "replay" -> new ReplayLlmClient(catalog);
            case "live" -> new AnthropicLlmClient(properties);
            case "record" -> new RecordingLlmClient(new AnthropicLlmClient(properties), catalog);
            default -> throw new IllegalStateException(
                    "orchestrator.llm.mode must be replay, live or record, was '" + properties.llm().mode() + "'");
        };
    }
}
