package com.agentic.orchestrator.agent;

import com.agentic.orchestrator.artifact.Artifacts;
import com.agentic.orchestrator.artifact.Artifacts.ChangeOp;
import com.agentic.orchestrator.artifact.Artifacts.FileChange;
import com.agentic.orchestrator.artifact.Artifacts.Patch;
import com.agentic.orchestrator.artifact.Artifacts.ReleaseSummary;
import com.agentic.orchestrator.artifact.Json;
import com.agentic.orchestrator.domain.OrchestratorStore;
import com.agentic.orchestrator.domain.Records.Decision;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prepares the release: writes the engineering summary (with the full decision log) onto the run's branch. It
 * only ever commits to {@code agent/<runId>}; merging is a human action.
 */
@Component
public class ReleaserAgent implements Agent {

    private final OrchestratorStore store;
    private final Json json;

    public ReleaserAgent(OrchestratorStore store, Json json) {
        this.store = store;
        this.json = json;
    }

    @Override
    public String id() {
        return "RELEASER";
    }

    @Override
    public AgentResult execute(StageContext ctx) {
        ReleaseSummary summary = ctx.requireOutput("release_readiness", ReleaseSummary.class);
        StringBuilder md = new StringBuilder();
        md.append("# Engineering summary\n\nRun `").append(ctx.run().id()).append("` · branch `agent/")
                .append(ctx.run().id()).append("` · recommendation: **").append(summary.goNoGo()).append("**\n\n");
        md.append(summary.markdown()).append("\n\n## Release checklist\n\n");
        summary.checklist().forEach(i -> md.append(i.ok() ? "- [x] " : "- [ ] ").append(i.item()).append(" — ")
                .append(i.detail()).append('\n'));
        if (!summary.risks().isEmpty()) {
            md.append("\n## Risks and limitations\n\n");
            summary.risks().forEach(r -> md.append("- ").append(r).append('\n'));
        }
        md.append("\n## Decision log\n\n| Actor | Type | Summary |\n| --- | --- | --- |\n");
        for (Decision d : store.listDecisions(ctx.run().id())) {
            md.append("| ").append(d.actor()).append(" | ").append(d.type()).append(" | ")
                    .append(d.summary().replace("|", "\\|").replace("\n", " ")).append(" |\n");
        }
        String path = "docs/runs/" + ctx.run().id() + "/ENGINEERING_SUMMARY.md";
        Patch patch = new Patch("Engineering summary for run " + ctx.run().id(),
                List.of(new FileChange(path, ChangeOp.WRITE, md.toString(), null, null)));
        return new AgentResult(Artifacts.PATCH, json.write(patch), 0);
    }
}
