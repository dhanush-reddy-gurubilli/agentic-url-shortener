package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.DesignDoc;
import com.agentic.orchestrator.artifact.Artifacts.ReleaseSummary;
import com.agentic.orchestrator.artifact.Artifacts.RequirementSpec;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.domain.OrchestratorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/** Fallback when the model is unavailable: the same checklist and verdict, with a templated narrative. */
@Component
public class ReleaseReadinessStaticAgent implements Agent {

    private final ReleaseChecklist checklist;
    private final OrchestratorStore store;
    private final Json json;

    public ReleaseReadinessStaticAgent(ReleaseChecklist checklist, OrchestratorStore store, Json json) {
        this.checklist = checklist;
        this.store = store;
        this.json = json;
    }

    @Override
    public String id() {
        return "RELEASE_READINESS_STATIC";
    }

    @Override
    public AgentResult execute(StageContext ctx) {
        RequirementSpec spec = ctx.requireOutput("requirements", RequirementSpec.class);
        DesignDoc design = ctx.requireOutput("design", DesignDoc.class);
        ReleaseChecklist.Result facts = checklist.evaluate(ctx);
        String narrative = "## What was built\n" + spec.title() + "\n\n"
                + spec.functionalRequirements().stream().map(r -> "- " + r).collect(Collectors.joining("\n"))
                + "\n\n## Design\n" + design.summary() + "\n\n## Stages\n"
                + ReleaseChecklist.stageSummary(store.listStages(ctx.run().id()))
                + "\n_This narrative was generated from a template because the model was unavailable._";
        ReleaseSummary summary = new ReleaseSummary(narrative, facts.goNoGo(),
                design.risks() == null ? List.of() : design.risks(), facts.items());
        return new AgentResult(Artifacts.RELEASE_SUMMARY, json.write(summary), 0);
    }
}
