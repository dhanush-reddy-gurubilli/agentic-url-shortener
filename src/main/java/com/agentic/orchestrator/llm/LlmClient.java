package com.agentic.orchestrator.llm;

import java.util.UUID;

/** Port to the language model. Adapters: Anthropic (live), Replay (recorded/scripted), Recording (live + save). */
public interface LlmClient {

    LlmResponse complete(LlmRequest request);

    /**
     * @param scenario scenario name, used by the replay adapter to find recorded responses (may be null)
     * @param stageKey stage the call is made for
     * @param attempt  1-based attempt within the stage; recordings are keyed by (stage, attempt)
     */
    record LlmRequest(UUID runId, String scenario, String stageKey, String agent, int attempt, String system,
                      String user) {
    }

    record LlmResponse(String text, long tokensUsed) {
    }
}
