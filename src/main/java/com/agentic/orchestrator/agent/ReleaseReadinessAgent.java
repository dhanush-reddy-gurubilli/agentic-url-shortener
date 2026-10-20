package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.ReleaseSummary;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.llm.LlmClient;
import com.agentic.orchestrator.llm.LlmClient.LlmResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Assembles the engineering summary. The model writes the narrative and risks; the checklist and the go/no-go
 * verdict are computed deterministically from the run's facts.
 */
@Component
public class ReleaseReadinessAgent extends LlmAgent {

    private static final String SYSTEM = PromptKit.COMMON_RULES + """
            Task: write the engineering summary for the reviewer who will decide whether to merge this branch. \
            State what was built and why, the key design decisions, what was validated and how, which approvals \
            humans gave, and the remaining risks and limitations. Be factual and specific; use only the facts given.
            JSON shape: {"narrative": "markdown text", "risks": ["..."]}
            """;

    record Narrative(String narrative, List<String> risks) {
    }

    private final ReleaseChecklist checklist;
    private final OrchestratorStore store;

    public ReleaseReadinessAgent(LlmClient llm, Json json, ReleaseChecklist checklist, OrchestratorStore store) {
        super(llm, json);
        this.checklist = checklist;
        this.store = store;
    }

    @Override
    public String id() {
        return "RELEASE_READINESS";
    }

    @Override
    public AgentResult execute(StageContext ctx) {
        RequirementSpec spec = ctx.requireOutput("requirements", RequirementSpec.class);
        ReleaseChecklist.Result facts = checklist.evaluate(ctx);
        String user = PromptKit.data("requirement-spec", json.writePretty(spec))
                + PromptKit.data("checklist", facts.asText())
                + PromptKit.data("stages", ReleaseChecklist.stageSummary(store.listStages(ctx.run().id())))
                + PromptKit.data("decisions", store.listDecisions(ctx.run().id()).stream()
                .map(d -> d.actor() + " " + d.type() + ": " + d.summary()).reduce("", (a, b) -> a + b + "\n"))
                + PromptKit.feedback(ctx.feedback());
        LlmResponse response = ask(ctx, SYSTEM, user);
        Narrative narrative = json.read(response.text(), Narrative.class);
        if (narrative.narrative() == null || narrative.narrative().isBlank()) {
            throw new IllegalStateException("The summary narrative is empty");
        }
        ReleaseSummary summary = new ReleaseSummary(narrative.narrative(), facts.goNoGo(),
                narrative.risks() == null ? List.of() : narrative.risks(), facts.items());
        return new AgentResult(Artifacts.RELEASE_SUMMARY, json.write(summary), response.tokensUsed());
    }
}
