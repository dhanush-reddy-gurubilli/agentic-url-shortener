package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.llm.LlmClient;
import com.agentic.orchestrator.llm.LlmClient.LlmRequest;
import com.agentic.orchestrator.llm.LlmClient.LlmResponse;

/** Base for agents that ask the model for one JSON artifact. */
abstract class LlmAgent implements Agent {

    protected final LlmClient llm;
    protected final Json json;

    LlmAgent(LlmClient llm, Json json) {
        this.llm = llm;
        this.json = json;
    }

    protected LlmResponse ask(StageContext ctx, String system, String user) {
        return llm.complete(new LlmRequest(ctx.run().id(), ctx.run().scenario(), ctx.stage().stageKey(), id(),
                ctx.attempt(), system, user));
    }

    /**
     * Normalises valid output to canonical JSON. Output that does not parse is passed through unchanged so the
     * schema gate can report the problem back to the agent as feedback.
     */
    protected AgentResult result(String artifactType, Class<?> type, LlmResponse response) {
        String content;
        try {
            content = json.write(json.read(response.text(), type));
        } catch (RuntimeException e) {
            content = response.text();
        }
        return new AgentResult(artifactType, content, response.tokensUsed());
    }
}
